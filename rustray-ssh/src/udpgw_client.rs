use std::collections::HashMap;
use std::io::IoSlice;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, RwLock};
use tokio::time::interval;

use crate::protocol::{
    decode_server_packet, encode_client_packet_into, encode_keepalive_into,
    UDPGW_CLIENT_FLAG_DNS, UDPGW_CLIENT_FLAG_REBIND,
};
use crate::ssh_pool::SshPool;

const MAX_WRITEV_BATCH: usize = 32;
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(10);

pub type UdpReplyTx = mpsc::Sender<(SocketAddr, Vec<u8>)>;

pub struct UdpgwClient {
    tx: mpsc::Sender<Vec<u8>>,
    routes: Arc<RwLock<HashMap<u16, UdpReplyTx>>>,
    next_conid: AtomicU16,
    rebind_pending: Arc<AtomicBool>,
}

impl UdpgwClient {
    pub fn spawn(pool: Arc<SshPool>, shutdown: tokio::sync::watch::Receiver<bool>) -> Arc<Self> {
        let (tx, rx) = mpsc::channel::<Vec<u8>>(512);
        let routes: Arc<RwLock<HashMap<u16, UdpReplyTx>>> = Arc::new(RwLock::new(HashMap::new()));
        let rebind_pending = Arc::new(AtomicBool::new(false));

        let client = Arc::new(Self {
            tx,
            routes: Arc::clone(&routes),
            next_conid: AtomicU16::new(1),
            rebind_pending: Arc::clone(&rebind_pending),
        });

        tokio::spawn(run_udpgw_loop(pool, rx, routes, rebind_pending, shutdown));
        client
    }

    pub async fn register(&self, reply_tx: UdpReplyTx) -> u16 {
        let conid = self.next_conid.fetch_add(1, Ordering::Relaxed);
        let id = if conid == 0 { 1 } else { conid };
        self.routes.write().await.insert(id, reply_tx);
        id
    }

    pub async fn unregister(&self, conid: u16) {
        self.routes.write().await.remove(&conid);
    }

    pub fn trigger_rebind(&self) {
        self.rebind_pending.store(true, Ordering::Release);
    }

    pub async fn send_datagram(&self, conid: u16, dst: SocketAddr, payload: &[u8]) {
        let mut flags = 0u8;
        if dst.port() == 53 {
            flags |= UDPGW_CLIENT_FLAG_DNS;
        }
        if self.rebind_pending.swap(false, Ordering::AcqRel) {
            flags |= UDPGW_CLIENT_FLAG_REBIND;
        }
        let mut frame = Vec::with_capacity(24 + payload.len());
        if encode_client_packet_into(&mut frame, flags, conid, dst, payload) {
            let _ = self.tx.try_send(frame);
        }
    }
}

async fn run_udpgw_loop(
    pool: Arc<SshPool>,
    mut rx: mpsc::Receiver<Vec<u8>>,
    routes: Arc<RwLock<HashMap<u16, UdpReplyTx>>>,
    rebind_pending: Arc<AtomicBool>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) {
    while !*shutdown.borrow() {
        let first_frame = tokio::select! {
            _ = shutdown.changed() => break,
            frame = rx.recv() => match frame {
                Some(f) => f,
                None => break,
            }
        };

        let channel = match pool.open_udpgw_channel().await {
            Ok(ch) => ch,
            Err(err) => {
                log::warn!("UDPGW channel connect error: {err}");
                tokio::time::sleep(Duration::from_millis(400)).await;
                continue;
            }
        };

        let stream = channel.into_stream();
        let (mut reader, mut writer) = tokio::io::split(stream);

        if writer.write_all(&first_frame).await.is_err() {
            continue;
        }

        let routes_reader = Arc::clone(&routes);
        let read_task = tokio::spawn(async move {
            let mut len_buf = [0u8; 2];
            let mut body = Vec::with_capacity(2048);
            loop {
                if reader.read_exact(&mut len_buf).await.is_err() {
                    break;
                }
                let frame_len = u16::from_le_bytes(len_buf) as usize;
                if frame_len == 0 {
                    continue;
                }
                body.resize(frame_len, 0);
                if reader.read_exact(&mut body).await.is_err() {
                    break;
                }
                if let Some(pkt) = decode_server_packet(&body) {
                    let guard = routes_reader.read().await;
                    if let Some(reply_tx) = guard.get(&pkt.conid) {
                        let _ = reply_tx.try_send((pkt.src_addr, pkt.payload.to_vec()));
                    }
                }
            }
        });

        let mut keepalive_tick = interval(KEEPALIVE_INTERVAL);
        keepalive_tick.tick().await;
        let mut keepalive_buf = Vec::with_capacity(8);
        let mut batch: Vec<Vec<u8>> = Vec::with_capacity(MAX_WRITEV_BATCH);

        loop {
            if rebind_pending.load(Ordering::Acquire) {
                break;
            }
            tokio::select! {
                _ = shutdown.changed() => break,
                _ = keepalive_tick.tick() => {
                    encode_keepalive_into(&mut keepalive_buf, 0);
                    if writer.write_all(&keepalive_buf).await.is_err() {
                        break;
                    }
                }
                maybe_frame = rx.recv() => {
                    let Some(frame) = maybe_frame else { break; };
                    batch.clear();
                    batch.push(frame);
                    while batch.len() < MAX_WRITEV_BATCH {
                        match rx.try_recv() {
                            Ok(next) => batch.push(next),
                            Err(_) => break,
                        }
                    }
                    if write_all_vectored(&mut writer, &batch).await.is_err() {
                        break;
                    }
                }
            }
        }

        read_task.abort();
    }
}

async fn write_all_vectored<W: tokio::io::AsyncWrite + Unpin>(
    writer: &mut W,
    frames: &[Vec<u8>],
) -> std::io::Result<()> {
    let mut slice_idx = 0;
    let mut byte_offset = 0;
    while slice_idx < frames.len() {
        let mut io_slices: [IoSlice<'_>; MAX_WRITEV_BATCH] = [IoSlice::new(&[]); MAX_WRITEV_BATCH];
        let mut count = 0;
        for (i, frame) in frames[slice_idx..].iter().enumerate() {
            let data = if i == 0 { &frame[byte_offset..] } else { frame.as_slice() };
            io_slices[count] = IoSlice::new(data);
            count += 1;
            if count == MAX_WRITEV_BATCH {
                break;
            }
        }
        let mut written = writer.write_vectored(&io_slices[..count]).await?;
        if written == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "UDPGW write_vectored returned 0",
            ));
        }
        while written > 0 && slice_idx < frames.len() {
            let rem = frames[slice_idx].len() - byte_offset;
            if written >= rem {
                written -= rem;
                slice_idx += 1;
                byte_offset = 0;
            } else {
                byte_offset += written;
                written = 0;
            }
        }
    }
    Ok(())
}
