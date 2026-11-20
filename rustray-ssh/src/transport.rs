use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{lookup_host, TcpStream};
use tokio::time::{sleep, timeout};
use tokio_rustls::TlsConnector;

use crate::config::{ParsedProxy, SshBridgeConfig};

pub trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

pub type BoxedStream = Box<dyn AsyncStream>;
pub type SocketProtector = Arc<dyn Fn(i32) -> bool + Send + Sync>;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const DELAY_SPLIT_DURATION: Duration = Duration::from_millis(250);

#[derive(Debug)]
struct NoCertVerify;

impl ServerCertVerifier for NoCertVerify {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ECDSA_NISTP521_SHA512,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}

pub async fn open_transport(
    cfg: &SshBridgeConfig,
    protector: &SocketProtector,
) -> io::Result<BoxedStream> {
    let mode = cfg.transport_mode.trim().to_ascii_lowercase();
    let proxy = cfg.parsed_proxy();

    if mode == "proxy" && proxy.is_none() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Remote proxy (host:port) is required for SSH + Payload + Proxy mode",
        ));
    }

    let (dial_host, dial_port) = match (&mode[..], &proxy) {
        ("direct" | "", _) => (cfg.host.as_str(), cfg.port),
        (_, Some(p)) => (p.host.as_str(), p.port),
        (_, None) => (cfg.host.as_str(), cfg.port),
    };

    let tcp = connect_protected_tcp(dial_host, dial_port, protector).await?;

    match mode.as_str() {
        "direct" | "" => Ok(Box::new(tcp)),
        "payload" | "proxy" => {
            let mut stream: BoxedStream = Box::new(tcp);
            send_http_payload(&mut stream, cfg, proxy.as_ref()).await?;
            Ok(stream)
        }
        "ws" => {
            let mut stream: BoxedStream = Box::new(tcp);
            send_ws_upgrade(&mut stream, cfg, proxy.as_ref()).await?;
            Ok(stream)
        }
        "ssl" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            Ok(Box::new(tls))
        }
        "ssl_payload" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            let mut stream: BoxedStream = Box::new(tls);
            send_http_payload(&mut stream, cfg, proxy.as_ref()).await?;
            Ok(stream)
        }
        "ssl_ws" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            let mut stream: BoxedStream = Box::new(tls);
            send_ws_upgrade(&mut stream, cfg, proxy.as_ref()).await?;
            Ok(stream)
        }
        other => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("unsupported ssh transport mode: {other}"),
        )),
    }
}

async fn connect_protected_tcp(
    host: &str,
    port: u16,
    protector: &SocketProtector,
) -> io::Result<TcpStream> {
    let mut addrs = lookup_host((host, port)).await?;
    let addr: SocketAddr = addrs
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "DNS returned no address"))?;

    let domain = match addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };

    let sock = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    sock.set_nodelay(true)?;
    sock.set_keepalive(true)?;
    sock.set_nonblocking(true)?;

    if !protector(sock.as_raw_fd()) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "VpnService.protect(fd) rejected SSH socket",
        ));
    }

    match sock.connect(&addr.into()) {
        Ok(()) => {}
        Err(err) if err.raw_os_error() == Some(libc_einprogress()) => {}
        Err(err) => return Err(err),
    }

    let std_stream: std::net::TcpStream = sock.into();
    let tcp = TcpStream::from_std(std_stream)?;
    timeout(CONNECT_TIMEOUT, tcp.writable())
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TCP connect timed out"))??;

    if let Some(err) = tcp.take_error()? {
        return Err(err);
    }
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

#[cfg(unix)]
fn libc_einprogress() -> i32 {
    115 // EINPROGRESS on Linux/Android
}

#[cfg(not(unix))]
fn libc_einprogress() -> i32 {
    10035
}

async fn wrap_tls(
    tcp: TcpStream,
    sni: &str,
) -> io::Result<tokio_rustls::client::TlsStream<TcpStream>> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls_cfg = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoCertVerify))
        .with_no_client_auth();

    let server_name = ServerName::try_from(sni.to_string())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid SNI: {sni}")))?;

    let connector = TlsConnector::from(Arc::new(tls_cfg));
    timeout(HANDSHAKE_TIMEOUT, connector.connect(server_name, tcp))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TLS handshake timed out"))?
}

enum PayloadStep {
    Write(String),
    Delay(Duration),
}

async fn send_http_payload(
    stream: &mut BoxedStream,
    cfg: &SshBridgeConfig,
    proxy: Option<&ParsedProxy>,
) -> io::Result<()> {
    let steps = render_payload_steps(&cfg.payload, cfg, proxy);
    for step in steps {
        match step {
            PayloadStep::Write(chunk) => {
                if !chunk.is_empty() {
                    stream.write_all(chunk.as_bytes()).await?;
                    stream.flush().await?;
                }
            }
            PayloadStep::Delay(dur) => {
                sleep(dur).await;
            }
        }
    }
    drain_http_response_headers(stream).await
}

async fn send_ws_upgrade(
    stream: &mut BoxedStream,
    cfg: &SshBridgeConfig,
    proxy: Option<&ParsedProxy>,
) -> io::Result<()> {
    if !cfg.payload.trim().is_empty() {
        return send_http_payload(stream, cfg, proxy).await;
    }
    let key = base64::engine::general_purpose::STANDARD.encode(b"rustray-ws-16byt");
    let path = if cfg.ws_path.starts_with('/') {
        cfg.ws_path.as_str()
    } else {
        "/"
    };
    let host = cfg.effective_ws_host();
    let req = format!(
        "GET {path} HTTP/1.1\r\n\
         Host: {host}\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Key: {key}\r\n\
         Sec-WebSocket-Version: 13\r\n\r\n"
    );
    stream.write_all(req.as_bytes()).await?;
    stream.flush().await?;
    drain_http_response_headers(stream).await
}

fn render_payload_steps(
    template: &str,
    cfg: &SshBridgeConfig,
    proxy: Option<&ParsedProxy>,
) -> Vec<PayloadStep> {
    let host_port = format!("{}:{}", cfg.host, cfg.port);
    let auth_header = proxy
        .and_then(|p| p.auth.as_deref())
        .filter(|a| !a.trim().is_empty())
        .map(|creds| {
            let encoded = base64::engine::general_purpose::STANDARD.encode(creds.as_bytes());
            format!("Proxy-Authorization: Basic {encoded}")
        })
        .unwrap_or_default();

    let base = if template.trim().is_empty() {
        if proxy.is_some() || cfg.transport_mode.eq_ignore_ascii_case("proxy") {
            if auth_header.is_empty() {
                format!("CONNECT {host_port} HTTP/1.1[crlf]Host: {host_port}[crlf][crlf]")
            } else {
                format!("CONNECT {host_port} HTTP/1.1[crlf]Host: {host_port}[crlf][auth][crlf][crlf]")
            }
        } else {
            format!(
                "GET / HTTP/1.1[crlf]Host: {}[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
                cfg.effective_ws_host()
            )
        }
    } else {
        template.to_string()
    };

    // Normalize split markers into internal sentinels before expanding tags
    const INSTANT_SPLIT: &str = "\u{0000}SPLIT\u{0000}";
    const DELAY_SPLIT: &str = "\u{0000}DSPLIT\u{0000}";

    let expanded = base
        .replace("[instant_split]", INSTANT_SPLIT)
        .replace("[split]", INSTANT_SPLIT)
        .replace("[delay_split]", DELAY_SPLIT)
        .replace("[splitDelay]", DELAY_SPLIT)
        .replace("[crlf]", "\r\n")
        .replace("[lfcr]", "\n\r")
        .replace("[lf]", "\n")
        .replace("[cr]", "\r")
        .replace("[netData]", &format!("CONNECT {host_port} HTTP/1.1"))
        .replace("[real_raw]", &format!("CONNECT {host_port} HTTP/1.1\r\n\r\n"))
        .replace("[raw]", &format!("CONNECT {host_port} HTTP/1.1\r\n\r\n"))
        .replace("[host_port]", &host_port)
        .replace("[ssh]", &host_port)
        .replace("[host]", &cfg.host)
        .replace("[port]", &cfg.port.to_string())
        .replace("[ws_host]", &cfg.effective_ws_host())
        .replace("[sni]", &cfg.effective_sni())
        .replace("[protocol]", "HTTP/1.1")
        .replace("[ua]", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        .replace("[auth]", &auth_header);

    let mut steps = Vec::new();
    for (d_idx, delay_part) in expanded.split(DELAY_SPLIT).enumerate() {
        if d_idx > 0 {
            steps.push(PayloadStep::Delay(DELAY_SPLIT_DURATION));
        }
        for split_part in delay_part.split(INSTANT_SPLIT) {
            if !split_part.is_empty() {
                steps.push(PayloadStep::Write(split_part.to_string()));
            }
        }
    }
    steps
}

async fn drain_http_response_headers(stream: &mut BoxedStream) -> io::Result<()> {
    let mut header = Vec::with_capacity(512);
    let mut byte = [0u8; 1];
    timeout(HANDSHAKE_TIMEOUT, async {
        loop {
            let n = stream.read(&mut byte).await?;
            if n == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "EOF waiting for HTTP proxy/upgrade response",
                ));
            }
            header.push(byte[0]);
            if header.ends_with(b"\r\n\r\n") {
                let first_line = String::from_utf8_lossy(&header)
                    .lines()
                    .next()
                    .unwrap_or("")
                    .to_string();
                log::info!("HTTP transport response: {}", first_line);
                return Ok(());
            }
            if header.len() > 8192 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "HTTP response header exceeded 8KB",
                ));
            }
        }
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP proxy/upgrade timed out"))?
}
