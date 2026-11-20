use std::borrow::Cow;
use std::io;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use russh::client::{self, Handle, Msg};
use russh::keys::PublicKey;
use russh::{cipher, compression, Channel, Preferred};
use tokio::sync::Mutex;

use crate::config::SshBridgeConfig;
use crate::transport::{open_transport, SocketProtector};

pub struct ClientHandler;

impl client::Handler for ClientHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        _server_public_key: &PublicKey,
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

    /// Connects the first TCP slot (and optionally UDPGW slot) eagerly so errors surface on start.
    pub async fn warmup(&self) -> io::Result<()> {
        let _ = self.get_or_connect_slot(&self.tcp_slots[0]).await?;
        Ok(())
    }

    pub async fn open_tcp_channel(&self, dst_host: &str, dst_port: u16) -> io::Result<Channel<Msg>> {
        let idx = self.rr.fetch_add(1, Ordering::Relaxed) % self.tcp_slots.len();
        let slot = &self.tcp_slots[idx];

        for attempt in 0..2 {
            let handle = self.get_or_connect_slot(slot).await?;
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
        for attempt in 0..2 {
            let handle = self.get_or_connect_slot(&self.udpgw_slot).await?;
            match handle
                .channel_open_direct_tcpip("127.0.0.1", port as u32, "127.0.0.1", 0)
                .await
            {
                Ok(ch) => return Ok(ch),
                Err(err) => {
                    log::warn!("SSH UDPGW channel open failed (attempt {}): {}", attempt + 1, err);
                    let mut guard = self.udpgw_slot.lock().await;
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
            cipher::CHACHA20_POLY1305,
            cipher::AES_256_GCM,
            cipher::AES_128_CTR,
            cipher::AES_256_CTR,
        ]);
        preferred.compression = Cow::Borrowed(&[compression::NONE]);

        let config = Arc::new(client::Config {
            window_size: 4 * 1024 * 1024,
            maximum_packet_size: 32 * 1024,
            keepalive_interval: Some(Duration::from_secs(15)),
            keepalive_max: 2,
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
