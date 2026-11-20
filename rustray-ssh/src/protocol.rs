use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6};

pub const PACKETPROTO_MAX_PAYLOAD: usize = u16::MAX as usize;
pub const PACKETPROTO_HEADER_LEN: usize = 2;
pub const UDPGW_HEADER_LEN: usize = 3;
pub const UDPGW_ADDR_IPV4_LEN: usize = 6;
pub const UDPGW_ADDR_IPV6_LEN: usize = 18;

pub const UDPGW_CLIENT_FLAG_KEEPALIVE: u8 = 1 << 0;
pub const UDPGW_CLIENT_FLAG_REBIND: u8 = 1 << 1;
pub const UDPGW_CLIENT_FLAG_DNS: u8 = 1 << 2;
pub const UDPGW_CLIENT_FLAG_IPV6: u8 = 1 << 3;

#[derive(Debug, Clone)]
pub struct DecodedServerPacket<'a> {
    pub flags: u8,
    pub conid: u16,
    pub src_addr: SocketAddr,
    pub payload: &'a [u8],
}

pub fn encode_keepalive_into(out: &mut Vec<u8>, conid: u16) {
    out.clear();
    let body_len = UDPGW_HEADER_LEN as u16;
    out.extend_from_slice(&body_len.to_le_bytes());
    out.push(UDPGW_CLIENT_FLAG_KEEPALIVE);
    out.extend_from_slice(&conid.to_le_bytes());
}

pub fn encode_client_packet_into(
    out: &mut Vec<u8>,
    mut flags: u8,
    conid: u16,
    dst_addr: SocketAddr,
    payload: &[u8],
) -> bool {
    let addr_len = match dst_addr {
        SocketAddr::V4(_) => UDPGW_ADDR_IPV4_LEN,
        SocketAddr::V6(_) => {
            flags |= UDPGW_CLIENT_FLAG_IPV6;
            UDPGW_ADDR_IPV6_LEN
        }
    };
    let body_len = UDPGW_HEADER_LEN + addr_len + payload.len();
    if body_len > PACKETPROTO_MAX_PAYLOAD {
        return false;
    }

    out.clear();
    out.reserve(PACKETPROTO_HEADER_LEN + body_len);
    out.extend_from_slice(&(body_len as u16).to_le_bytes());
    out.push(flags);
    out.extend_from_slice(&conid.to_le_bytes());
    match dst_addr {
        SocketAddr::V4(v4) => {
            out.extend_from_slice(&v4.ip().octets());
            out.extend_from_slice(&v4.port().to_be_bytes());
        }
        SocketAddr::V6(v6) => {
            out.extend_from_slice(&v6.ip().octets());
            out.extend_from_slice(&v6.port().to_be_bytes());
        }
    }
    out.extend_from_slice(payload);
    true
}

pub fn decode_server_packet(buf: &[u8]) -> Option<DecodedServerPacket<'_>> {
    if buf.len() < UDPGW_HEADER_LEN {
        return None;
    }
    let flags = buf[0];
    let conid = u16::from_le_bytes([buf[1], buf[2]]);
    if (flags & UDPGW_CLIENT_FLAG_KEEPALIVE) != 0 {
        return None;
    }
    let rest = &buf[UDPGW_HEADER_LEN..];
    if (flags & UDPGW_CLIENT_FLAG_IPV6) != 0 {
        if rest.len() < UDPGW_ADDR_IPV6_LEN {
            return None;
        }
        let mut ip = [0u8; 16];
        ip.copy_from_slice(&rest[..16]);
        let port = u16::from_be_bytes([rest[16], rest[17]]);
        Some(DecodedServerPacket {
            flags,
            conid,
            src_addr: SocketAddr::V6(SocketAddrV6::new(Ipv6Addr::from(ip), port, 0, 0)),
            payload: &rest[UDPGW_ADDR_IPV6_LEN..],
        })
    } else {
        if rest.len() < UDPGW_ADDR_IPV4_LEN {
            return None;
        }
        let ip = Ipv4Addr::new(rest[0], rest[1], rest[2], rest[3]);
        let port = u16::from_be_bytes([rest[4], rest[5]]);
        Some(DecodedServerPacket {
            flags,
            conid,
            src_addr: SocketAddr::V4(SocketAddrV4::new(ip, port)),
            payload: &rest[UDPGW_ADDR_IPV4_LEN..],
        })
    }
}
