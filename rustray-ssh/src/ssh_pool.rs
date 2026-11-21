use std::borrow::Cow;
use std::io;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use russh::client::{self, Handle, Msg};
use russh::keys::PublicKeyOrCertificate;
use russh::{cipher, compression, Channel, Preferred};
use tokio::sync::Mutex;

use crate::config::SshBridgeConfig;
use crate::transport::{open_transport, SocketProtector};

pub struct ClientHandler;

impl client::Handler for ClientHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        _server_public_key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

pub struct SshPool {
    cfg: Arc<SshBridgeConfig>,
    protector: SocketProtector,
    tcp_slots: Vec<Mutex<Option<Arc<Handle<ClientHandler>>>>>,
    udpgw_slot: Mutex<Option<Arc<Handle<ClientHandler>>>>,
    rr: AtomicUsize,
}

impl SshPool {
    pub fn new(cfg: Arc<SshBridgeConfig>, protector: SocketProtector) -> Self {
        let count = cfg.effective_pool_size();
        let mut tcp_slots = Vec::with_capacity(count);
        for _ in 0..count {
            tcp_slots.push(Mutex::new(None));
        }
        Self {
            cfg,
            protector,
            tcp_slots,
            udpgw_slot: Mutex::new(None),
            rr: AtomicUsize::new(0),
        }
    }

    pub fn is_enhanced(&self) -> bool {
        self.cfg.enhanced
    }

    /// Connects the primary SSH slot eagerly so handshake/auth errors surface immediately on start.
    pub async fn warmup(&self) -> io::Result<()> {
        let _ = self.get_or_connect_slot(&self.tcp_slots[0]).await?;
        Ok(())
    }

    pub async fn open_tcp_channel(&self, dst_host: &str, dst_port: u16) -> io::Result<Channel<Msg>> {
        let idx = self.rr.fetch_add(1, Ordering::Relaxed) % self.tcp_slots.len();
        let slot = &self.tcp_slots[idx];

        for attempt in 0..2 {
            let handle = match self.get_or_connect_slot(slot).await {
                Ok(h) => h,
                Err(err) if idx != 0 => {
                    // Fallback to primary slot if secondary SSH session is rejected (e.g. single-login server)
                    log::warn!("SSH secondary slot {idx} failed ({err}), falling back to slot 0");
                    self.get_or_connect_slot(&self.tcp_slots[0]).await?
                }
                Err(err) => return Err(err),
            };
            match handle
                .channel_open_direct_tcpip(dst_host, dst_port as u32, "127.0.0.1", 0)
                .await
            {
                Ok(ch) => return Ok(ch),
                Err(err) => {
                    log::warn!("SSH TCP channel open failed (attempt {}): {}", attempt + 1, err);
                    let mut guard = slot.lock().await;
                    if guard.as_ref().is_some_and(|h| Arc::ptr_eq(h, &handle)) {
                        *guard = None;
                    }
                    if attempt == 1 {
                        return Err(io::Error::new(io::ErrorKind::BrokenPipe, err.to_string()));
                    }
                }
            }
        }
        unreachable!()
    }

    pub async fn open_udpgw_channel(&self) -> io::Result<Channel<Msg>> {
        let port = self.cfg.udpgw_port;
        // When pool_size == 1, multiplex UDPGW directly on the already-connected primary SSH session
        // (zero extra handshake latency, works on strict 1-session SSH accounts/proxies).
        // When pool_size >= 2, prefer a dedicated SSH session for UDPGW to eliminate TCP HOL blocking,
        // with automatic fallback to primary slot if the server rejects a second login.
        let use_dedicated = self.tcp_slots.len() >= 2;
        let primary_slot = if use_dedicated {
            &self.udpgw_slot
        } else {
            &self.tcp_slots[0]
        };

        for attempt in 0..2 {
            let handle = match self.get_or_connect_slot(primary_slot).await {
                Ok(h) => h,
                Err(err) if use_dedicated => {
                    log::warn!("Dedicated UDPGW SSH session failed ({err}), falling back to primary session");
                    self.get_or_connect_slot(&self.tcp_slots[0]).await?
                }
                Err(err) => return Err(err),
            };
            match handle
                .channel_open_direct_tcpip("127.0.0.1", port as u32, "127.0.0.1", 0)
                .await
            {
                Ok(ch) => {
                    log::info!("UDPGW channel opened to 127.0.0.1:{port}");
                    return Ok(ch);
                }
                Err(err) => {
                    log::warn!("SSH UDPGW channel open failed (attempt {}): {}", attempt + 1, err);
                    let mut guard = primary_slot.lock().await;
                    if guard.as_ref().is_some_and(|h| Arc::ptr_eq(h, &handle)) {
                        *guard = None;
                    }
                    if attempt == 1 {
                        return Err(io::Error::new(io::ErrorKind::BrokenPipe, err.to_string()));
                    }
                }
            }
        }
        unreachable!()
    }

    pub async fn reset_all(&self) {
        for slot in &self.tcp_slots {
            *slot.lock().await = None;
        }
        *self.udpgw_slot.lock().await = None;
    }

    async fn get_or_connect_slot(
        &self,
        slot: &Mutex<Option<Arc<Handle<ClientHandler>>>>,
    ) -> io::Result<Arc<Handle<ClientHandler>>> {
        let mut guard = slot.lock().await;
        if let Some(existing) = guard.as_ref() {
            if !existing.is_closed() {
                return Ok(Arc::clone(existing));
            }
        }
        let fresh = Arc::new(self.connect_session().await?);
        *guard = Some(Arc::clone(&fresh));
        Ok(fresh)
    }

    async fn connect_session(&self) -> io::Result<Handle<ClientHandler>> {
        let stream = open_transport(&self.cfg, &self.protector).await?;

        let mut preferred = Preferred::default();
        preferred.cipher = Cow::Borrowed(&[
            cipher::AES_128_GCM,
            cipher::AES_256_GCM,
            cipher::CHACHA20_POLY1305,
            cipher::AES_128_CTR,
            cipher::AES_256_CTR,
        ]);
        preferred.compression = Cow::Borrowed(&[compression::NONE]);

        let window_size = if self.cfg.enhanced { 8 * 1024 * 1024 } else { 4 * 1024 * 1024 };
        let keepalive_secs = if self.cfg.enhanced { 8 } else { 15 };
        let config = Arc::new(client::Config {
            window_size,
            maximum_packet_size: 32 * 1024,
            keepalive_interval: Some(Duration::from_secs(keepalive_secs)),
            keepalive_max: 2,
            nodelay: true,
            preferred,
            ..Default::default()
        });

        let mut session = client::connect_stream(config, stream, ClientHandler)
            .await
            .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("SSH handshake failed: {e}")))?;

        let auth = session
            .authenticate_password(&self.cfg.username, &self.cfg.password)
            .await
            .map_err(|e| io::Error::new(io::ErrorKind::PermissionDenied, format!("SSH auth error: {e}")))?;

        if !auth.success() {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "SSH authentication rejected",
            ));
        }
        Ok(session)
    }
}
