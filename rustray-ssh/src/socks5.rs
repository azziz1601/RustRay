use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{lookup_host, TcpListener, TcpStream, UdpSocket};
use tokio::sync::{mpsc, watch};

use crate::ssh_pool::SshPool;
use crate::udpgw_client::UdpgwClient;

pub async fn start_socks5_server(
    pool: Arc<SshPool>,
    udpgw: Arc<UdpgwClient>,
    mut shutdown: watch::Receiver<bool>,
) -> io::Result<u16> {
    let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await?;
    let port = listener.local_addr()?.port();

    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = shutdown.changed() => break,
                accepted = listener.accept() => {
                    let Ok((stream, _)) = accepted else { break; };
                    let _ = stream.set_nodelay(true);
                    let pool_c = Arc::clone(&pool);
                    let udpgw_c = Arc::clone(&udpgw);
                    tokio::spawn(async move {
                        let _ = handle_socks5_client(stream, pool_c, udpgw_c).await;
                    });
                }
            }
        }
    });

    Ok(port)
}

async fn handle_socks5_client(
    mut stream: TcpStream,
    pool: Arc<SshPool>,
    udpgw: Arc<UdpgwClient>,
) -> io::Result<()> {
    // 1. Greeting: VER, NMETHODS, METHODS...
    let mut head = [0u8; 2];
    stream.read_exact(&mut head).await?;
    if head[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Not SOCKS5"));
    }
    let mut methods = vec![0u8; head[1] as usize];
    stream.read_exact(&mut methods).await?;
    // Reply: NO AUTH (0x00)
    stream.write_all(&[0x05, 0x00]).await?;

    // 2. Request: VER, CMD, RSV, ATYP
    let mut req = [0u8; 4];
    stream.read_exact(&mut req).await?;
    if req[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Bad SOCKS5 request"));
    }
    let cmd = req[1];
    let (dst_host, dst_port) = read_socks_addr(&mut stream, req[3]).await?;

    match cmd {
        0x01 => {
            // CONNECT (TCP)
            let channel = match pool.open_tcp_channel(&dst_host, dst_port).await {
                Ok(ch) => ch,
                Err(err) => {
                    let _ = send_socks_reply(&mut stream, 0x05, SocketAddr::from(([0, 0, 0, 0], 0))).await;
                    return Err(err);
                }
            };
            send_socks_reply(&mut stream, 0x00, SocketAddr::from(([127, 0, 0, 1], 0))).await?;
            let mut remote = channel.into_stream();
            let _ = tokio::io::copy_bidirectional_with_sizes(&mut stream, &mut remote, 32 * 1024, 32 * 1024).await;
            Ok(())
        }
        0x03 => {
            // UDP ASSOCIATE
            let udp = Arc::new(UdpSocket::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await?);
            let bind_addr = udp.local_addr()?;
            send_socks_reply(&mut stream, 0x00, bind_addr).await?;

            let (reply_tx, mut reply_rx) = mpsc::channel::<(SocketAddr, Vec<u8>)>(128);
            let conid = udpgw.register(reply_tx).await;

            let udp_recv = Arc::clone(&udp);
            let udpgw_send = Arc::clone(&udpgw);
            let client_peer: Arc<tokio::sync::RwLock<Option<SocketAddr>>> =
                Arc::new(tokio::sync::RwLock::new(None));
            let peer_for_reply = Arc::clone(&client_peer);
            let udp_reply = Arc::clone(&udp);

            let reply_task = tokio::spawn(async move {
                let mut out_buf = Vec::with_capacity(2048);
                while let Some((src_addr, payload)) = reply_rx.recv().await {
                    let Some(peer) = *peer_for_reply.read().await else {
                        continue;
                    };
                    encode_socks5_udp_header(&mut out_buf, src_addr, &payload);
                    let _ = udp_reply.send_to(&out_buf, peer).await;
                }
            });

            let mut buf = vec![0u8; 65535];
            let mut tcp_eof = [0u8; 16];
            loop {
                tokio::select! {
                    n = stream.read(&mut tcp_eof) => {
                        if matches!(n, Ok(0) | Err(_)) {
                            break;
                        }
                    }
                    res = udp_recv.recv_from(&mut buf) => {
                        let Ok((len, peer)) = res else { break; };
                        {
                            let mut guard = client_peer.write().await;
                            *guard = Some(peer);
                        }
                        if let Some((dst, payload)) = parse_socks5_udp(&buf[..len]).await {
                            udpgw_send.send_datagram(conid, dst, payload).await;
                        }
                    }
                }
            }

            reply_task.abort();
            udpgw.unregister(conid).await;
            Ok(())
        }
        _ => {
            let _ = send_socks_reply(&mut stream, 0x07, SocketAddr::from(([0, 0, 0, 0], 0))).await;
            Ok(())
        }
    }
}

async fn read_socks_addr(stream: &mut TcpStream, atyp: u8) -> io::Result<(String, u16)> {
    let host = match atyp {
        0x01 => {
            let mut ip = [0u8; 4];
            stream.read_exact(&mut ip).await?;
            Ipv4Addr::from(ip).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut domain = vec![0u8; len[0] as usize];
            stream.read_exact(&mut domain).await?;
            String::from_utf8_lossy(&domain).into_owned()
        }
        0x04 => {
            let mut ip = [0u8; 16];
            stream.read_exact(&mut ip).await?;
            Ipv6Addr::from(ip).to_string()
        }
        _ => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Unsupported ATYP"));
        }
    };
    let mut port_buf = [0u8; 2];
    stream.read_exact(&mut port_buf).await?;
    let port = u16::from_be_bytes(port_buf);
    Ok((host, port))
}

async fn send_socks_reply(stream: &mut TcpStream, rep: u8, addr: SocketAddr) -> io::Result<()> {
    let mut resp = Vec::with_capacity(22);
    resp.push(0x05);
    resp.push(rep);
    resp.push(0x00);
    match addr.ip() {
        IpAddr::V4(v4) => {
            resp.push(0x01);
            resp.extend_from_slice(&v4.octets());
        }
        IpAddr::V6(v6) => {
            resp.push(0x04);
            resp.extend_from_slice(&v6.octets());
        }
    }
    resp.extend_from_slice(&addr.port().to_be_bytes());
    stream.write_all(&resp).await
}

async fn parse_socks5_udp(packet: &[u8]) -> Option<(SocketAddr, &[u8])> {
    if packet.len() < 4 || packet[2] != 0x00 {
        return None;
    }
    let atyp = packet[3];
    let (addr, header_len) = match atyp {
        0x01 => {
            if packet.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
            let port = u16::from_be_bytes([packet[8], packet[9]]);
            (SocketAddr::new(IpAddr::V4(ip), port), 10)
        }
        0x04 => {
            if packet.len() < 22 {
                return None;
            }
            let mut ip = [0u8; 16];
            ip.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            (SocketAddr::new(IpAddr::V6(Ipv6Addr::from(ip)), port), 22)
        }
        0x03 => {
            if packet.len() < 5 {
                return None;
            }
            let len = packet[4] as usize;
            if packet.len() < 5 + len + 2 {
                return None;
            }
            let domain = std::str::from_utf8(&packet[5..5 + len]).ok()?;
            let port = u16::from_be_bytes([packet[5 + len], packet[6 + len]]);
            let resolved = lookup_host((domain, port)).await.ok()?.next()?;
            (resolved, 7 + len)
        }
        _ => return None,
    };
    Some((addr, &packet[header_len..]))
}

fn encode_socks5_udp_header(out: &mut Vec<u8>, src: SocketAddr, payload: &[u8]) {
    out.clear();
    out.extend_from_slice(&[0x00, 0x00, 0x00]);
    match src.ip() {
        IpAddr::V4(v4) => {
            out.push(0x01);
            out.extend_from_slice(&v4.octets());
        }
        IpAddr::V6(v6) => {
            out.push(0x04);
            out.extend_from_slice(&v6.octets());
        }
    }
    out.extend_from_slice(&src.port().to_be_bytes());
    out.extend_from_slice(payload);
}
