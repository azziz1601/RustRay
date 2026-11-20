use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SshBridgeConfig {
    pub host: String,
    pub port: u16,
    pub username: String,
    #[serde(default)]
    pub password: String,
    #[serde(default = "default_mode")]
    pub transport_mode: String, // "direct" | "payload" | "ssl" | "ws" | "ssl_ws"
    #[serde(default)]
    pub payload: String,
    #[serde(default)]
    pub sni: String,
    #[serde(default)]
    pub ws_host: String,
    #[serde(default = "default_ws_path")]
    pub ws_path: String,
    #[serde(default = "default_udpgw_port")]
    pub udpgw_port: u16,
    #[serde(default = "default_pool_size")]
    pub pool_size: usize,
    #[serde(default = "default_udp_mtu")]
    pub udp_mtu: usize,
}

fn default_mode() -> String {
    "direct".to_string()
}

fn default_ws_path() -> String {
    "/".to_string()
}

fn default_udpgw_port() -> u16 {
    7300
}

fn default_pool_size() -> usize {
    2
}

fn default_udp_mtu() -> usize {
    1480
}

impl SshBridgeConfig {
    pub fn effective_pool_size(&self) -> usize {
        self.pool_size.clamp(1, 4)
    }

    pub fn effective_sni(&self) -> String {
        if !self.sni.trim().is_empty() {
            self.sni.trim().to_string()
        } else if !self.ws_host.trim().is_empty() {
            self.ws_host.trim().to_string()
        } else {
            self.host.trim().to_string()
        }
    }

    pub fn effective_ws_host(&self) -> String {
        if !self.ws_host.trim().is_empty() {
            self.ws_host.trim().to_string()
        } else if !self.sni.trim().is_empty() {
            self.sni.trim().to_string()
        } else {
            self.host.trim().to_string()
        }
    }
}
