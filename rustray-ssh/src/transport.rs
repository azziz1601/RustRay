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
use tokio::time::timeout;
use tokio_rustls::TlsConnector;

use crate::config::SshBridgeConfig;

pub trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

pub type BoxedStream = Box<dyn AsyncStream>;
pub type SocketProtector = Arc<dyn Fn(i32) -> bool + Send + Sync>;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

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
    let tcp = connect_protected_tcp(&cfg.host, cfg.port, protector).await?;
    let mode = cfg.transport_mode.trim().to_ascii_lowercase();

    match mode.as_str() {
        "direct" | "" => Ok(Box::new(tcp)),
        "payload" => {
            let mut stream: BoxedStream = Box::new(tcp);
            send_http_payload(&mut stream, cfg).await?;
            Ok(stream)
        }
        "ws" => {
            let mut stream: BoxedStream = Box::new(tcp);
            send_ws_upgrade(&mut stream, cfg).await?;
            Ok(stream)
        }
        "ssl" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            Ok(Box::new(tls))
        }
        "ssl_ws" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            let mut stream: BoxedStream = Box::new(tls);
            send_ws_upgrade(&mut stream, cfg).await?;
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

async fn send_http_payload(stream: &mut BoxedStream, cfg: &SshBridgeConfig) -> io::Result<()> {
    let rendered = render_payload(&cfg.payload, cfg);
    stream.write_all(rendered.as_bytes()).await?;
    stream.flush().await?;
    drain_http_response_headers(stream).await
}

async fn send_ws_upgrade(stream: &mut BoxedStream, cfg: &SshBridgeConfig) -> io::Result<()> {
    if !cfg.payload.trim().is_empty() {
        return send_http_payload(stream, cfg).await;
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

fn render_payload(template: &str, cfg: &SshBridgeConfig) -> String {
    let base = if template.trim().is_empty() {
        format!(
            "GET / HTTP/1.1[crlf]Host: {}[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
            cfg.effective_ws_host()
        )
    } else {
        template.to_string()
    };

    base.replace("[crlf]", "\r\n")
        .replace("[lf]", "\n")
        .replace("[cr]", "\r")
        .replace("[host]", &cfg.effective_ws_host())
        .replace("[host_port]", &format!("{}:{}", cfg.host, cfg.port))
        .replace("[sni]", &cfg.effective_sni())
        .replace("[protocol]", "HTTP/1.1")
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
                    "EOF waiting for HTTP upgrade response",
                ));
            }
            header.push(byte[0]);
            if header.ends_with(b"\r\n\r\n") {
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
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP upgrade timed out"))?
}
