use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use russh::ChannelMsg;
use tokio::sync::{mpsc, RwLock};
use tokio::time::{interval, timeout};

use crate::protocol::{
    decode_server_packet, encode_client_packet_into, encode_keepalive_into,
    UDPGW_CLIENT_FLAG_REBIND,
};
use crate::ssh_pool::SshPool;
use crate::stats::BridgeStats;

const MAX_WRITE_BATCH: usize = 32;
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(10);
const DNS_CACHE_TTL: Duration = Duration::from_secs(300);
const DNS_QUERY_TIMEOUT: Duration = Duration::from_secs(2);

pub type UdpReplyTx = mpsc::Sender<(SocketAddr, Vec<u8>)>;

pub struct UdpgwClient {
    tx: mpsc::Sender<Vec<u8>>,
    routes: Arc<RwLock<HashMap<u16, UdpReplyTx>>>,
    dns_cache: Arc<RwLock<HashMap<String, (IpAddr, Instant)>>>,
    next_conid: AtomicU16,
    dns_txid: AtomicU16,
    rebind_pending: Arc<AtomicBool>,
    stats: Arc<BridgeStats>,
}

impl UdpgwClient {
    pub fn spawn(
        pool: Arc<SshPool>,
        stats: Arc<BridgeStats>,
        shutdown: tokio::sync::watch::Receiver<bool>,
    ) -> Arc<Self> {
        let (tx, rx) = mpsc::channel::<Vec<u8>>(1024);
        let routes: Arc<RwLock<HashMap<u16, UdpReplyTx>>> = Arc::new(RwLock::new(HashMap::new()));
        let dns_cache: Arc<RwLock<HashMap<String, (IpAddr, Instant)>>> =
            Arc::new(RwLock::new(HashMap::new()));
        let rebind_pending = Arc::new(AtomicBool::new(false));

        let client = Arc::new(Self {
            tx,
            routes: Arc::clone(&routes),
            dns_cache,
            next_conid: AtomicU16::new(1),
            dns_txid: AtomicU16::new(0x1234),
            rebind_pending: Arc::clone(&rebind_pending),
            stats: Arc::clone(&stats),
        });

        tokio::spawn(run_udpgw_loop(pool, stats, rx, routes, rebind_pending, shutdown));
        client
    }

    pub async fn register(&self, reply_tx: UdpReplyTx) -> u16 {
        let mut conid = self.next_conid.fetch_add(1, Ordering::Relaxed);
        if conid == 0 {
            conid = self.next_conid.fetch_add(1, Ordering::Relaxed).max(1);
        }
        self.routes.write().await.insert(conid, reply_tx);
        conid
    }

    pub async fn unregister(&self, conid: u16) {
        self.routes.write().await.remove(&conid);
    }

    pub async fn unregister_many(&self, conids: &[u16]) {
        if conids.is_empty() {
            return;
        }
        let mut guard = self.routes.write().await;
        for id in conids {
            guard.remove(id);
        }
    }

    pub fn trigger_rebind(&self) {
        self.rebind_pending.store(true, Ordering::Release);
    }

    pub async fn send_datagram(&self, conid: u16, dst: SocketAddr, payload: &[u8]) {
        let normalized_dst = normalize_socket_addr(dst);
        let mut flags = 0u8;
        if self.rebind_pending.swap(false, Ordering::AcqRel) {
            flags |= UDPGW_CLIENT_FLAG_REBIND;
        }
        let mut frame = Vec::with_capacity(24 + payload.len());
        if encode_client_packet_into(&mut frame, flags, conid, normalized_dst, payload) {
            if self.tx.try_send(frame).is_ok() {
                self.stats.udp_tx_packets.fetch_add(1, Ordering::Relaxed);
            }
        }
    }

    /// Resolves a domain name remotely via UDPGW (1.1.1.1:53 with fallback 8.8.8.8:53)
    /// to avoid Android TUN/FakeIP DNS loops.
    pub async fn resolve_domain(&self, domain: &str) -> Option<IpAddr> {
        if let Ok(ip) = domain.parse::<IpAddr>() {
            return Some(ip);
        }
        let key = domain.trim_end_matches('.').to_ascii_lowercase();
        if let Some((ip, expires)) = self.dns_cache.read().await.get(&key).copied() {
            if Instant::now() < expires {
                return Some(ip);
            }
        }

        let dns_servers = [
            SocketAddr::from(([1, 1, 1, 1], 53)),
            SocketAddr::from(([8, 8, 8, 8], 53)),
        ];

        for server in dns_servers {
            let txid = self.dns_txid.fetch_add(1, Ordering::Relaxed);
            let Some(query) = build_dns_a_query(txid, &key) else {
                return None;
            };
            let (reply_tx, mut reply_rx) = mpsc::channel::<(SocketAddr, Vec<u8>)>(4);
            let conid = self.register(reply_tx).await;
            self.send_datagram(conid, server, &query).await;

            let resolved = match timeout(DNS_QUERY_TIMEOUT, reply_rx.recv()).await {
                Ok(Some((_, resp))) => parse_dns_a_response(&resp, txid),
                _ => None,
            };
            self.unregister(conid).await;

            if let Some(ip) = resolved {
                self.stats.udp_domain_resolved.fetch_add(1, Ordering::Relaxed);
                let mut cache = self.dns_cache.write().await;
                if cache.len() > 512 {
                    cache.clear();
                }
                cache.insert(key, (ip, Instant::now() + DNS_CACHE_TTL));
                return Some(ip);
            }
        }

        self.stats.udp_domain_failed.fetch_add(1, Ordering::Relaxed);
        None
    }
}

fn normalize_socket_addr(addr: SocketAddr) -> SocketAddr {
    match addr {
        SocketAddr::V6(v6) => {
            if let Some(v4) = v6.ip().to_ipv4_mapped() {
                SocketAddr::new(IpAddr::V4(v4), v6.port())
            } else {
                addr
            }
        }
        SocketAddr::V4(_) => addr,
    }
}

async fn run_udpgw_loop(
    pool: Arc<SshPool>,
    stats: Arc<BridgeStats>,
    mut rx: mpsc::Receiver<Vec<u8>>,
    routes: Arc<RwLock<HashMap<u16, UdpReplyTx>>>,
    rebind_pending: Arc<AtomicBool>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) {
    let mut backoff_ms: u64 = 300;
    while !*shutdown.borrow() {
        stats.set_udpgw_status("connecting");
        let channel = match pool.open_udpgw_channel().await {
            Ok(ch) => {
                backoff_ms = 300;
                stats.set_udpgw_status("connected");
                ch
            }
            Err(err) => {
                let msg = format!("connect failed: {err}");
                log::warn!("UDPGW {msg}");
                stats.set_udpgw_status("error");
                stats.set_last_error(msg);
                tokio::select! {
                    _ = shutdown.changed() => break,
                    _ = tokio::time::sleep(Duration::from_millis(backoff_ms)) => {}
                }
                backoff_ms = (backoff_ms * 2).min(4000);
                continue;
            }
        };

        // Use russh native lock-free Channel::split() instead of tokio::io::split(into_stream())
        // to eliminate BiLock contention and ensure immediate SSH_MSG_CHANNEL_DATA delivery.
        let (mut rx_ch, tx_ch) = channel.split();

        let mut keepalive_buf = Vec::with_capacity(8);
        encode_keepalive_into(&mut keepalive_buf, 0);
        if let Err(err) = tx_ch.data(&keepalive_buf[..]).await {
            stats.set_udpgw_status("error");
            stats.set_last_error(format!("initial keepalive failed: {err}"));
            continue;
        }
        stats
            .udpgw_written_bytes
            .fetch_add(keepalive_buf.len() as u64, Ordering::Relaxed);

        let routes_reader = Arc::clone(&routes);
        let stats_reader = Arc::clone(&stats);
        let mut read_task = tokio::spawn(async move {
            let mut acc: Vec<u8> = Vec::with_capacity(8192);
            while let Some(msg) = rx_ch.wait().await {
                match msg {
                    ChannelMsg::Data { data } => {
                        let slice: &[u8] = &data;
                        stats_reader
                            .udpgw_raw_rx_bytes
                            .fetch_add(slice.len() as u64, Ordering::Relaxed);
                        acc.extend_from_slice(slice);

                        let mut offset = 0usize;
                        while acc.len().saturating_sub(offset) >= 2 {
                            let frame_len =
                                u16::from_le_bytes([acc[offset], acc[offset + 1]]) as usize;
                            let total = 2 + frame_len;
                            if acc.len().saturating_sub(offset) < total {
                                break;
                            }
                            if frame_len > 0 {
                                let body = &acc[offset + 2..offset + total];
                                if let Some(pkt) = decode_server_packet(body) {
                                    stats_reader.udp_rx_packets.fetch_add(1, Ordering::Relaxed);
                                    let guard = routes_reader.read().await;
                                    if let Some(reply_tx) = guard.get(&pkt.conid) {
                                        let _ = reply_tx
                                            .try_send((pkt.src_addr, pkt.payload.to_vec()));
                                    }
                                }
                            }
                            offset += total;
                        }
                        if offset > 0 {
                            acc.drain(..offset);
                        }
                    }
                    ChannelMsg::Eof | ChannelMsg::Close => break,
                    _ => {}
                }
            }
        });

        let mut keepalive_tick = interval(KEEPALIVE_INTERVAL);
        keepalive_tick.tick().await;
        let mut coalesced: Vec<u8> = Vec::with_capacity(8192);

        loop {
            if rebind_pending.load(Ordering::Acquire) {
                break;
            }
            tokio::select! {
                _ = shutdown.changed() => break,
                _ = &mut read_task => {
                    log::warn!("UDPGW reader closed, reconnecting channel");
                    stats.set_udpgw_status("reconnecting");
                    break;
                }
                _ = keepalive_tick.tick() => {
                    encode_keepalive_into(&mut keepalive_buf, 0);
                    if let Err(err) = tx_ch.data(&keepalive_buf[..]).await {
                        stats.set_last_error(format!("keepalive write err: {err}"));
                        break;
                    }
                    stats
                        .udpgw_written_bytes
                        .fetch_add(keepalive_buf.len() as u64, Ordering::Relaxed);
                }
                maybe_frame = rx.recv() => {
                    let Some(frame) = maybe_frame else { break; };
                    coalesced.clear();
                    coalesced.extend_from_slice(&frame);
                    let mut count = 1usize;
                    while count < MAX_WRITE_BATCH && coalesced.len() < 16384 {
                        match rx.try_recv() {
                            Ok(next) => {
                                coalesced.extend_from_slice(&next);
                                count += 1;
                            }
                            Err(_) => break,
                        }
                    }
                    if let Err(err) = tx_ch.data(&coalesced[..]).await {
                        stats.set_last_error(format!("udpgw write err: {err}"));
                        break;
                    }
                    stats
                        .udpgw_written_bytes
                        .fetch_add(coalesced.len() as u64, Ordering::Relaxed);
                }
            }
        }

        read_task.abort();
    }
}

fn build_dns_a_query(txid: u16, domain: &str) -> Option<Vec<u8>> {
    let mut buf = Vec::with_capacity(64);
    buf.extend_from_slice(&txid.to_be_bytes());
    buf.extend_from_slice(&[0x01, 0x00]); // standard query, RD=1
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]); // QDCOUNT=1
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return None;
        }
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0x00);
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]); // QTYPE=A, QCLASS=IN
    Some(buf)
}

fn parse_dns_a_response(resp: &[u8], expected_txid: u16) -> Option<IpAddr> {
    if resp.len() < 12 {
        return None;
    }
    if u16::from_be_bytes([resp[0], resp[1]]) != expected_txid {
        return None;
    }
    let qdcount = u16::from_be_bytes([resp[4], resp[5]]) as usize;
    let ancount = u16::from_be_bytes([resp[6], resp[7]]) as usize;
    let mut pos = 12;

    for _ in 0..qdcount {
        pos = skip_dns_name(resp, pos)?;
        if pos + 4 > resp.len() {
            return None;
        }
        pos += 4;
    }

    for _ in 0..ancount {
        pos = skip_dns_name(resp, pos)?;
        if pos + 10 > resp.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([resp[pos], resp[pos + 1]]);
        let rdlen = u16::from_be_bytes([resp[pos + 8], resp[pos + 9]]) as usize;
        pos += 10;
        if pos + rdlen > resp.len() {
            return None;
        }
        if rtype == 1 && rdlen == 4 {
            return Some(IpAddr::V4(Ipv4Addr::new(
                resp[pos],
                resp[pos + 1],
                resp[pos + 2],
                resp[pos + 3],
            )));
        }
        pos += rdlen;
    }
    None
}

fn skip_dns_name(buf: &[u8], mut pos: usize) -> Option<usize> {
    loop {
        if pos >= buf.len() {
            return None;
        }
        let len = buf[pos];
        if len == 0 {
            return Some(pos + 1);
        }
        if (len & 0xC0) == 0xC0 {
            return if pos + 2 <= buf.len() { Some(pos + 2) } else { None };
        }
        pos += 1 + (len as usize);
    }
}
