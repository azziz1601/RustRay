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
const MULTI_ADDR_CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const INSTANT_SPLIT_GAP: Duration = Duration::from_millis(15);
const DELAY_SPLIT_DURATION: Duration = Duration::from_millis(250);
const PEEK_SECOND_RESPONSE_TIMEOUT: Duration = Duration::from_millis(250);
const MAX_DRAIN_BODY: usize = 256 * 1024;
const MAX_SCAN_BYTES: usize = 128 * 1024;

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
        "payload" | "proxy" | "v2ray_payload" => {
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
        "ssl_payload" | "v2ray_ssl_payload" => {
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
    let resolved = lookup_host((host, port)).await?;
    let mut addrs: Vec<SocketAddr> = Vec::new();
    for addr in resolved {
        if !addrs.contains(&addr) {
            addrs.push(addr);
        }
    }
    if addrs.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("DNS returned no address for {host}:{port}"),
        ));
    }

    // Prioritize IPv4 before IPv6 so 5G/dual-stack networks with blackholed IPv6
    // routes don't stall for 12s before falling back to IPv4.
    addrs.sort_by_key(|a| if a.is_ipv4() { 0u8 } else { 1u8 });
    addrs.truncate(4);

    let per_addr_timeout = if addrs.len() > 1 {
        MULTI_ADDR_CONNECT_TIMEOUT
    } else {
        CONNECT_TIMEOUT
    };

    let mut last_err: Option<io::Error> = None;
    for addr in addrs {
        match connect_single_addr(host, port, addr, enhanced, per_addr_timeout, protector).await {
            Ok(tcp) => return Ok(tcp),
            Err(err) if err.kind() == io::ErrorKind::PermissionDenied => return Err(err),
            Err(err) => {
                log::warn!("TCP connect to {host}:{port} ({addr}) failed: {err}");
                last_err = Some(err);
            }
        }
    }

    Err(last_err.unwrap_or_else(|| {
        io::Error::new(
            io::ErrorKind::TimedOut,
            format!("TCP connect timed out ({host}:{port})"),
        )
    }))
}

async fn connect_single_addr(
    host: &str,
    port: u16,
    addr: SocketAddr,
    enhanced: bool,
    wait_timeout: Duration,
    protector: &SocketProtector,
) -> io::Result<TcpStream> {
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

    let target_label = if host == addr.ip().to_string() {
        format!("{addr}")
    } else {
        format!("{host}:{port} -> {addr}")
    };

    match sock.connect(&addr.into()) {
        Ok(()) => {}
        Err(err) if err.raw_os_error() == Some(libc_einprogress()) => {}
        Err(err) => {
            return Err(io::Error::new(
                err.kind(),
                format!("TCP connect error ({target_label}): {err}"),
            ));
        }
    }

    let std_stream: std::net::TcpStream = sock.into();
    let tcp = TcpStream::from_std(std_stream)?;
    timeout(wait_timeout, tcp.writable())
        .await
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::TimedOut,
                format!("TCP connect timed out ({target_label})"),
            )
        })??;

    if let Some(err) = tcp.take_error()? {
        return Err(io::Error::new(
            err.kind(),
            format!("TCP connect failed ({target_label}): {err}"),
        ));
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
    let mut first_req_is_head = false;
    let mut saw_first_write = false;

    let mut counted_blocks = 0usize;
    for step in &steps {
        if let PayloadStep::Write(chunk) = step {
            let trimmed = chunk.trim_start();
            // Do not count trailing fake HTTP response lines (e.g. `[split]HTTP/69[crlf][crlf]`)
            let is_fake_status = counted_blocks > 0
                && trimmed
                    .get(..5)
                    .is_some_and(|p| p.eq_ignore_ascii_case("HTTP/"));
            if is_fake_status {
                continue;
            }
            if !saw_first_write && !trimmed.is_empty() {
                saw_first_write = true;
                first_req_is_head = trimmed
                    .get(..5)
                    .is_some_and(|p| p.eq_ignore_ascii_case("HEAD "));
            }
            let blocks = chunk.matches("\r\n\r\n").count();
            http_blocks += blocks;
            counted_blocks += blocks;
        }
    }
    let expected_responses = http_blocks.clamp(1, 4);

    let mut completed_blocks = 0usize;
    let mut prev_was_write = false;

    for step in steps {
        match step {
            PayloadStep::Write(chunk) => {
                if chunk.is_empty() {
                    continue;
                }
                // Skip trailing dummy HTTP response lines placed after `[split]` in HTTP Custom /
                // 1FTunnel templates (e.g. `[split]HTTP/enhanced 200 Ok[crlf]` or `[split]HTTP/69[crlf][crlf]`)
                // so they never leak into the upgraded SSH or V2Ray HTTPUpgrade stream.
                let trimmed = chunk.trim_start();
                let is_trailing_fake_status = completed_blocks > 0
                    && trimmed
                        .get(..5)
                        .is_some_and(|p| p.eq_ignore_ascii_case("HTTP/"));
                if is_trailing_fake_status {
                    log::info!("Skipping trailing fake HTTP status line after [split]");
                    continue;
                }

                if prev_was_write {
                    sleep(INSTANT_SPLIT_GAP).await;
                }
                stream.write_all(chunk.as_bytes()).await?;
                stream.flush().await?;
                completed_blocks += chunk.matches("\r\n\r\n").count();
                prev_was_write = true;
            }
            PayloadStep::Delay(dur) => {
                sleep(dur).await;
                prev_was_write = false;
            }
        }
    }
    consume_http_responses(stream, expected_responses, first_req_is_head).await
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
    consume_http_responses(stream, 1, false).await
}

fn inject_v2ray_upgrade_path(expanded: String, path: &str) -> String {
    if path.is_empty() || path == "/" || expanded.contains(path) {
        return expanded;
    }
    let parts: Vec<&str> = expanded.split("\r\n\r\n").collect();
    if parts.len() <= 1 {
        return expanded;
    }
    let mut out = Vec::with_capacity(parts.len());
    for part in parts {
        if part.to_ascii_lowercase().contains("upgrade:") {
            if let Some((first_line, rest)) = part.split_once("\r\n") {
                if first_line.contains(" / ") {
                    let patched = first_line.replacen(" / ", &format!(" {path} "), 1);
                    out.push(format!("{patched}\r\n{rest}"));
                    continue;
                }
            }
        }
        out.push(part.to_string());
    }
    out.join("\r\n\r\n")
}

fn render_payload_steps(
    template: &str,
    cfg: &SshBridgeConfig,
    proxy: Option<&ParsedProxy>,
) -> Vec<PayloadStep> {
    let is_v2ray = cfg.transport_mode.trim().to_ascii_lowercase().starts_with("v2ray_");
    let effective_host = if is_v2ray {
        cfg.effective_ws_host()
    } else {
        cfg.host.clone()
    };
    let effective_path = if cfg.ws_path.trim().starts_with('/') {
        cfg.ws_path.trim().to_string()
    } else if !cfg.ws_path.trim().is_empty() {
        format!("/{}", cfg.ws_path.trim())
    } else {
        "/".to_string()
    };
    let host_port = format!("{}:{}", effective_host, cfg.port);
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
                "GET {effective_path} HTTP/1.1[crlf]Host: {}[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
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
        .replace("[host]", &effective_host)
        .replace("[port]", &cfg.port.to_string())
        .replace("[path]", &effective_path)
        .replace("[ws_host]", &cfg.effective_ws_host())
        .replace("[sni]", &cfg.effective_sni())
        .replace("[protocol]", "HTTP/1.1")
        .replace("[ua]", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        .replace("[auth]", &auth_header);

    let expanded = if is_v2ray {
        inject_v2ray_upgrade_path(expanded, &effective_path)
    } else {
        expanded
    };

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
/// - Immediately hands off to SSH on `101 Switching Protocols` (protocol upgraded).
/// - Handles stacked HTTP responses (`200`, `400`, `302`, `101`) including `Transfer-Encoding: chunked`
///   and `Content-Length` bodies emitted by CDN/proxy responses before the real `101`/`SSH-` stream.
/// - Never stalls trying to drain bodies on `HEAD` responses or `200` tunnel handoffs.
/// - Scans forward for `SSH-` or `HTTP/` if a proxy emits custom banner/body text before SSH starts.
/// - Preserves any peeked `SSH-` banner via `PrefixedStream`.
async fn consume_http_responses(
    mut stream: BoxedStream,
    expected_responses: usize,
    first_req_is_head: bool,
) -> io::Result<BoxedStream> {
    let mut responses_seen = 0usize;
    let mut last_status_line = String::new();
    let mut last_code: u16 = 0;

    for attempt in 0..6usize {
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
                if last_code == 101 || last_code == 200 {
                    return Ok(stream);
                }
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "HTTP proxy/upgrade timed out",
                ));
            }
        };

        if first_read >= 4 && &probe[..4] == b"SSH-" {
            return Ok(PrefixedStream::new(probe[..first_read].to_vec(), stream));
        }

        let header = if first_read >= 4 && &probe[..4] == b"HTTP" {
            read_http_header_remainder(&mut stream, probe[..first_read].to_vec()).await?
        } else {
            // Some CDN/proxy setups emit a chunked/text body before the next HTTP header or SSH banner.
            match scan_for_http_or_ssh(&mut stream, &probe[..first_read]).await? {
                ScanHit::Ssh(prefix) => return Ok(PrefixedStream::new(prefix, stream)),
                ScanHit::Http(prefix) => read_http_header_remainder(&mut stream, prefix).await?,
                ScanHit::None => {
                    if last_code == 101 || last_code == 200 {
                        return Ok(stream);
                    }
                    return Err(io::Error::new(
                        io::ErrorKind::ConnectionRefused,
                        if last_status_line.is_empty() {
                            format!(
                                "Unexpected proxy response: {}",
                                String::from_utf8_lossy(&probe[..first_read]).trim()
                            )
                        } else {
                            format!("Proxy/bug rejected: {last_status_line}")
                        },
                    ));
                }
            }
        };

        let (code, status_line, content_len, is_chunked) = parse_http_meta(&header);
        log::info!("HTTP transport response #{}: {}", responses_seen + 1, status_line);
        responses_seen += 1;
        last_status_line = status_line.clone();
        last_code = code;

        // Once 101 Switching Protocols is received, the stream is upgraded to raw SSH/WS tunnel.
        if code == 101 {
            return Ok(stream);
        }

        let no_body_allowed = (first_req_is_head && responses_seen == 1) || code == 204 || code == 304;

        if code == 200 {
            if !no_body_allowed && (is_chunked || content_len > 0) && responses_seen < expected_responses {
                drain_http_body_best_effort(&mut stream, content_len, is_chunked).await?;
            }
            continue;
        }

        // Non-101/200 (301, 302, 400, 403, 502): drain any body (chunked or Content-Length)
        // when more responses are expected from pipelined / `[split]` payloads.
        if responses_seen < expected_responses {
            if !no_body_allowed && (is_chunked || content_len > 0) {
                drain_http_body_best_effort(&mut stream, content_len, is_chunked).await?;
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

async fn read_http_header_remainder(
    stream: &mut BoxedStream,
    mut header: Vec<u8>,
) -> io::Result<Vec<u8>> {
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
    Ok(header)
}

fn parse_http_meta(header: &[u8]) -> (u16, String, usize, bool) {
    let header_str = String::from_utf8_lossy(header);
    let status_line = header_str.lines().next().unwrap_or("").trim().to_string();
    let code = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(0);
    let mut content_len = 0usize;
    let mut is_chunked = false;
    for line in header_str.lines().skip(1) {
        if let Some((k, v)) = line.split_once(':') {
            let key = k.trim();
            let val = v.trim();
            if key.eq_ignore_ascii_case("content-length") {
                if let Ok(n) = val.parse::<usize>() {
                    content_len = n;
                }
            } else if key.eq_ignore_ascii_case("transfer-encoding")
                && val.to_ascii_lowercase().contains("chunked")
            {
                is_chunked = true;
            }
        }
    }
    (code, status_line, content_len, is_chunked)
}

/// Best-effort HTTP body drainer for pipelined payloads:
/// drains chunked or `Content-Length` bodies up to `MAX_DRAIN_BODY`, and stops gracefully on
/// timeout rather than aborting so the next loop can still match `HTTP/` or `SSH-`.
async fn drain_http_body_best_effort(
    stream: &mut BoxedStream,
    content_len: usize,
    is_chunked: bool,
) -> io::Result<()> {
    if is_chunked {
        let mut total_drained = 0usize;
        loop {
            let line = match read_crlf_line(stream, 64).await {
                Ok(l) => l,
                Err(err) if err.kind() == io::ErrorKind::TimedOut => break,
                Err(err) => return Err(err),
            };
            let size_hex = line.split(';').next().unwrap_or("").trim();
            let chunk_size = match usize::from_str_radix(size_hex, 16) {
                Ok(sz) => sz,
                Err(_) => break,
            };
            if chunk_size == 0 {
                let _ = read_crlf_line(stream, 64).await;
                break;
            }
            if total_drained + chunk_size > MAX_DRAIN_BODY {
                break;
            }
            let mut discard = vec![0u8; chunk_size + 2];
            match timeout(Duration::from_secs(3), stream.read_exact(&mut discard)).await {
                Ok(Ok(_)) => total_drained += chunk_size,
                Ok(Err(e)) => return Err(e),
                Err(_) => break,
            }
        }
    } else if content_len > 0 && content_len <= MAX_DRAIN_BODY {
        let mut remaining = content_len;
        let mut buf = [0u8; 4096];
        while remaining > 0 {
            let to_read = remaining.min(buf.len());
            match timeout(Duration::from_secs(3), stream.read(&mut buf[..to_read])).await {
                Ok(Ok(0)) => break,
                Ok(Ok(n)) => remaining -= n,
                Ok(Err(e)) => return Err(e),
                Err(_) => break,
            }
        }
    }
    Ok(())
}

async fn read_crlf_line(stream: &mut BoxedStream, max_len: usize) -> io::Result<String> {
    let mut buf = Vec::with_capacity(32);
    let mut b = [0u8; 1];
    timeout(Duration::from_secs(3), async {
        while buf.len() < max_len {
            let n = stream.read(&mut b).await?;
            if n == 0 {
                break;
            }
            buf.push(b[0]);
            if buf.ends_with(b"\r\n") {
                break;
            }
        }
        Ok::<(), io::Error>(())
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Line read timed out"))??;
    Ok(String::from_utf8_lossy(&buf).trim().to_string())
}

enum ScanHit {
    Ssh(Vec<u8>),
    Http(Vec<u8>),
    None,
}

async fn scan_for_http_or_ssh(stream: &mut BoxedStream, initial: &[u8]) -> io::Result<ScanHit> {
    let mut ring: Vec<u8> = initial.to_vec();
    let mut b = [0u8; 1];
    let mut scanned = initial.len();
    while scanned < MAX_SCAN_BYTES {
        if ring.ends_with(b"SSH-") {
            return Ok(ScanHit::Ssh(b"SSH-".to_vec()));
        }
        if ring.ends_with(b"HTTP/") {
            return Ok(ScanHit::Http(b"HTTP/".to_vec()));
        }
        match timeout(Duration::from_secs(2), stream.read(&mut b)).await {
            Ok(Ok(0)) | Err(_) => return Ok(ScanHit::None),
            Ok(Ok(_)) => {
                ring.push(b[0]);
                scanned += 1;
                if ring.len() > 8 {
                    let drop_n = ring.len() - 8;
                    ring.drain(..drop_n);
                }
            }
            Ok(Err(e)) => return Err(e),
        }
    }
    Ok(ScanHit::None)
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
