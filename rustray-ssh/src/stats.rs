use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

pub struct BridgeStats {
    pub tcp_opened: AtomicU64,
    pub tcp_failed: AtomicU64,
    pub udp_assoc_opened: AtomicU64,
    pub udp_tx_packets: AtomicU64,
    pub udp_rx_packets: AtomicU64,
    pub udpgw_written_bytes: AtomicU64,
    pub udpgw_raw_rx_bytes: AtomicU64,
    pub udp_domain_resolved: AtomicU64,
    pub udp_domain_failed: AtomicU64,
    pub udpgw_probe_ok: AtomicBool,
    udpgw_status: Mutex<String>,
    last_dst: Mutex<String>,
    last_error: Mutex<String>,
}

impl BridgeStats {
    pub fn new() -> Self {
        Self {
            tcp_opened: AtomicU64::new(0),
            tcp_failed: AtomicU64::new(0),
            udp_assoc_opened: AtomicU64::new(0),
            udp_tx_packets: AtomicU64::new(0),
            udp_rx_packets: AtomicU64::new(0),
            udpgw_written_bytes: AtomicU64::new(0),
            udpgw_raw_rx_bytes: AtomicU64::new(0),
            udp_domain_resolved: AtomicU64::new(0),
            udp_domain_failed: AtomicU64::new(0),
            udpgw_probe_ok: AtomicBool::new(false),
            udpgw_status: Mutex::new("initializing".to_string()),
            last_dst: Mutex::new(String::new()),
            last_error: Mutex::new(String::new()),
        }
    }

    pub fn set_udpgw_status(&self, status: impl Into<String>) {
        if let Ok(mut guard) = self.udpgw_status.lock() {
            *guard = status.into();
        }
    }

    pub fn set_last_dst(&self, dst: impl Into<String>) {
        if let Ok(mut guard) = self.last_dst.lock() {
            *guard = dst.into();
        }
    }

    pub fn set_last_error(&self, err: impl Into<String>) {
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = err.into();
        }
    }

    pub fn summary(&self) -> String {
        let status = self
            .udpgw_status
            .lock()
            .map(|g| g.clone())
            .unwrap_or_else(|_| "unknown".to_string());
        let last_dst = self
            .last_dst
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default();
        let last_err = self
            .last_error
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default();
        let probe = if self.udpgw_probe_ok.load(Ordering::Relaxed) {
            "ok"
        } else {
            "no-reply"
        };
        let dst_part = if last_dst.is_empty() {
            String::new()
        } else {
            format!(" | last_dst={last_dst}")
        };
        let err_part = if last_err.is_empty() {
            String::new()
        } else {
            format!(" | last_err={last_err}")
        };
        format!(
            "SSH Engine (russh 0.65): udpgw={} probe={} (txB={} rxB={}) | tcp_ok={} tcp_err={} | udp_assoc={} tx={} rx={} | dns_ok={} dns_err={}{}{}",
            status,
            probe,
            self.udpgw_written_bytes.load(Ordering::Relaxed),
            self.udpgw_raw_rx_bytes.load(Ordering::Relaxed),
            self.tcp_opened.load(Ordering::Relaxed),
            self.tcp_failed.load(Ordering::Relaxed),
            self.udp_assoc_opened.load(Ordering::Relaxed),
            self.udp_tx_packets.load(Ordering::Relaxed),
            self.udp_rx_packets.load(Ordering::Relaxed),
            self.udp_domain_resolved.load(Ordering::Relaxed),
            self.udp_domain_failed.load(Ordering::Relaxed),
            dst_part,
            err_part,
        )
    }
}
