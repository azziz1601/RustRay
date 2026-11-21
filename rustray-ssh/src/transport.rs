use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use base64::Engine;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
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
const PEEK_SECOND_RESPONSE_TIMEOUT: Duration = Duration::from_millis(350);

/// Wraps an `AsyncStream` with already-peeked prefix bytes (e.g. `SSH-` banner) so `russh`
/// receives the complete SSH identification string from byte 0.
struct PrefixedStream {
    prefix: Vec<u8>,
    offset: usize,
    inner: BoxedStream,
}

impl PrefixedStream {
    fn new(prefix: Vec<u8>, inner: BoxedStream) -> BoxedStream {
        if prefix.is_empty() {
            inner
        } else {
            Box::new(Self {
                prefix,
                offset: 0,
                inner,
            })
        }
    }
}

impl AsyncRead for PrefixedStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        if self.offset < self.prefix.len() {
            let rem = &self.prefix[self.offset..];
            let to_copy = rem.len().min(buf.remaining());
            buf.put_slice(&rem[..to_copy]);
            self.offset += to_copy;
            if self.offset >= self.prefix.len() {
                self.prefix.clear();
                self.offset = 0;
            }
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut *self.inner).poll_read(cx, buf)
    }
}

impl AsyncWrite for PrefixedStream {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut *self.inner).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.inner).poll_shutdown(cx)
    }
}

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

    let tcp = connect_protected_tcp(dial_host, dial_port, cfg.enhanced, protector).await?;

    match mode.as_str() {
        "direct" | "" => Ok(Box::new(tcp)),
        "payload" | "proxy" => {
            let stream: BoxedStream = Box::new(tcp);
            send_http_payload(stream, cfg, proxy.as_ref()).await
        }
        "ws" => {
            let stream: BoxedStream = Box::new(tcp);
            send_ws_upgrade(stream, cfg, proxy.as_ref()).await
        }
        "ssl" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            Ok(Box::new(tls))
        }
        "ssl_payload" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            let stream: BoxedStream = Box::new(tls);
            send_http_payload(stream, cfg, proxy.as_ref()).await
        }
        "ssl_ws" => {
            let tls = wrap_tls(tcp, &cfg.effective_sni()).await?;
            let stream: BoxedStream = Box::new(tls);
            send_ws_upgrade(stream, cfg, proxy.as_ref()).await
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
    enhanced: bool,
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
    if enhanced {
        let _ = sock.set_recv_buffer_size(256 * 1024);
        let _ = sock.set_send_buffer_size(256 * 1024);
    }
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
    mut stream: BoxedStream,
    cfg: &SshBridgeConfig,
    proxy: Option<&ParsedProxy>,
) -> io::Result<BoxedStream> {
    let steps = render_payload_steps(&cfg.payload, cfg, proxy);
    let mut http_blocks = 0usize;
    for step in &steps {
        if let PayloadStep::Write(chunk) = step {
            http_blocks += chunk.matches("\r\n\r\n").count();
        }
    }
    let expected_responses = http_blocks.clamp(1, 3);

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
    consume_http_responses(stream, expected_responses).await
}

async fn send_ws_upgrade(
    mut stream: BoxedStream,
    cfg: &SshBridgeConfig,
    proxy: Option<&ParsedProxy>,
) -> io::Result<BoxedStream> {
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
    consume_http_responses(stream, 1).await
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

    const INSTANT_SPLIT: &str = "\u{0000}SPLIT\u{0000}";
    const DELAY_SPLIT: &str = "\u{0000}DSPLIT\u{0000}";

    let expanded = base
        .replace("[instant_split]", INSTANT_SPLIT)
        .replace("[splitNoDelay]", INSTANT_SPLIT)
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
        .replace("[method]", "CONNECT")
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

/// HTTP Custom-style response handler:
/// - Handles single or stacked HTTP responses (e.g. `200 OK` followed by `101 Switching Protocols` on `[split]` payloads).
/// - Preserves any `SSH-` banner immediately via `PrefixedStream` if the server skips HTTP framing.
/// - Surfaces clear HTTP error status lines (e.g. `HTTP/1.1 403 Forbidden`, `HTTP/1.1 302 Found`) instead of passing HTML to `russh`.
async fn consume_http_responses(
    mut stream: BoxedStream,
    expected_responses: usize,
) -> io::Result<BoxedStream> {
    let mut responses_seen = 0usize;
    let mut last_status_line = String::new();
    let mut last_code: u16 = 0;

    for attempt in 0..4usize {
        let mut probe = [0u8; 4];
        let wait_dur = if attempt == 0 || responses_seen < expected_responses {
            HANDSHAKE_TIMEOUT
        } else {
            PEEK_SECOND_RESPONSE_TIMEOUT
        };

        let first_read = match timeout(wait_dur, read_exact_or_partial(&mut stream, &mut probe)).await {
            Ok(Ok(0)) => {
                if last_code == 101 || last_code == 200 {
                    return Ok(stream);
                }
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    if last_status_line.is_empty() {
                        "EOF waiting for HTTP proxy/upgrade response".to_string()
                    } else {
                        format!("Proxy closed after: {last_status_line}")
                    },
                ));
            }
            Ok(Ok(n)) => n,
            Ok(Err(e)) => return Err(e),
            Err(_) => {
                // Timed out peeking for an optional second HTTP header after a 200/101 -> tunnel is ready!
                if last_code == 101 || last_code == 200 {
                    return Ok(stream);
                }
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "HTTP proxy/upgrade timed out",
                ));
            }
        };

        // If server already sent the SSH banner (`SSH-...`), hand it directly to russh intact.
        if first_read >= 4 && &probe[..4] == b"SSH-" {
            return Ok(PrefixedStream::new(probe[..first_read].to_vec(), stream));
        }

        // If not an HTTP response header and we already saw a 101/200, pass bytes through to russh.
        if !(first_read >= 4 && &probe[..4] == b"HTTP") {
            if last_code == 101 || last_code == 200 {
                return Ok(PrefixedStream::new(probe[..first_read].to_vec(), stream));
            }
            let preview = String::from_utf8_lossy(&probe[..first_read]);
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Unexpected proxy response: {preview}"),
            ));
        }

        // Read the remainder of this HTTP header block until `\r\n\r\n`.
        let mut header = Vec::with_capacity(512);
        header.extend_from_slice(&probe[..first_read]);
        let mut byte = [0u8; 1];
        timeout(HANDSHAKE_TIMEOUT, async {
            while !header.ends_with(b"\r\n\r\n") {
                let n = stream.read(&mut byte).await?;
                if n == 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "EOF while reading HTTP response headers",
                    ));
                }
                header.push(byte[0]);
                if header.len() > 8192 {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "HTTP response header exceeded 8KB",
                    ));
                }
            }
            Ok(())
        })
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP header read timed out"))??;

        let header_str = String::from_utf8_lossy(&header);
        let status_line = header_str.lines().next().unwrap_or("").trim().to_string();
        let code = status_line
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse::<u16>().ok())
            .unwrap_or(0);
        let content_len = header_str
            .lines()
            .skip(1)
            .find_map(|line| {
                let (k, v) = line.split_once(':')?;
                if k.trim().eq_ignore_ascii_case("content-length") {
                    v.trim().parse::<usize>().ok()
                } else {
                    None
                }
            })
            .unwrap_or(0);

        log::info!("HTTP transport response #{}: {}", responses_seen + 1, status_line);
        responses_seen += 1;
        last_status_line = status_line.clone();
        last_code = code;

        // If 101 Switching Protocols, upgrade succeeded — check once if another stacked header follows only when expected_responses > responses_seen.
        if code == 101 {
            if responses_seen >= expected_responses {
                return Ok(stream);
            }
            continue;
        }

        // If 200 OK / Connection Established:
        // - If we sent multiple HTTP blocks (`[split]`), continue loop to consume the next `101`/`200` or peek `SSH-`.
        // - If single request, still peek briefly in case proxy sends a second stacked HTTP header (common on WS/CDN proxies).
        if code == 200 {
            continue;
        }

        // Non-101/200 (e.g. 302, 400, 403, 502):
        // On multi-block `[split]` payloads, the first dummy block may intentionally get 400/302 before the real `101` arrives.
        if responses_seen < expected_responses {
            if content_len > 0 && content_len <= 16384 {
                let mut discard = vec![0u8; content_len];
                let _ = timeout(Duration::from_secs(2), stream.read_exact(&mut discard)).await;
            }
            continue;
        }

        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("Proxy/bug rejected: {status_line}"),
        ));
    }

    if last_code == 101 || last_code == 200 {
        Ok(stream)
    } else {
        Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("Proxy/bug rejected: {last_status_line}"),
        ))
    }
}

async fn read_exact_or_partial(stream: &mut BoxedStream, buf: &mut [u8]) -> io::Result<usize> {
    let mut read_total = 0usize;
    while read_total < buf.len() {
        let n = stream.read(&mut buf[read_total..]).await?;
        if n == 0 {
            break;
        }
        read_total += n;
    }
    Ok(read_total)
}
