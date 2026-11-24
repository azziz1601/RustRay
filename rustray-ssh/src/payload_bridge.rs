use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;

use crate::config::SshBridgeConfig;
use crate::transport::{open_transport, SocketProtector};

pub struct PayloadBridgeStats {
    pub opened: AtomicU64,
    pub failed: AtomicU64,
    last_error: Mutex<String>,
}

impl PayloadBridgeStats {
    pub fn new() -> Self {
        Self {
            opened: AtomicU64::new(0),
            failed: AtomicU64::new(0),
            last_error: Mutex::new(String::new()),
        }
    }

    pub fn record_error(&self, err: &str) {
        self.failed.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = err.to_string();
        }
    }

    pub fn summary(&self) -> String {
        let ok = self.opened.load(Ordering::Relaxed);
        let fail = self.failed.load(Ordering::Relaxed);
        let last = self
            .last_error
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default();
        if last.is_empty() {
            format!("Payload bridge: ok={ok} fail={fail}")
        } else {
            format!("Payload bridge: ok={ok} fail={fail} (last: {last})")
        }
    }
}

/// Starts a local TCP listener on `127.0.0.1:0` that wraps each incoming Xray TCP connection
/// in an HTTP Custom-style payload + HTTPUpgrade handshake to the configured proxy/bug.
pub async fn start_payload_bridge(
    cfg: Arc<SshBridgeConfig>,
    protector: SocketProtector,
    stats: Arc<PayloadBridgeStats>,
    mut shutdown: watch::Receiver<bool>,
) -> io::Result<u16> {
    let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await?;
    let port = listener.local_addr()?.port();

    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = shutdown.changed() => break,
                accepted = listener.accept() => {
                    let Ok((local, _)) = accepted else { break; };
                    let _ = local.set_nodelay(true);
                    let cfg_c = Arc::clone(&cfg);
                    let prot_c = Arc::clone(&protector);
                    let stats_c = Arc::clone(&stats);
                    tokio::spawn(async move {
                        handle_bridge_conn(local, cfg_c, prot_c, stats_c).await;
                    });
                }
            }
        }
    });

    Ok(port)
}

async fn handle_bridge_conn(
    mut local: TcpStream,
    cfg: Arc<SshBridgeConfig>,
    protector: SocketProtector,
    stats: Arc<PayloadBridgeStats>,
) {
    let mut remote = match open_transport(&cfg, &protector).await {
        Ok(stream) => {
            stats.opened.fetch_add(1, Ordering::Relaxed);
            stream
        }
        Err(err) => {
            log::warn!("V2Ray payload bridge dial failed: {err}");
            stats.record_error(&err.to_string());
            return;
        }
    };

    let _ = tokio::io::copy_bidirectional_with_sizes(&mut local, &mut remote, 16 * 1024, 16 * 1024)
        .await;
}
