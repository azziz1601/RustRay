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
    pub transport_mode: String, // "direct" | "payload" | "proxy" | "ssl" | "ssl_payload" | "ws" | "ssl_ws"
    #[serde(default)]
    pub payload: String,
    #[serde(default)]
    pub proxy: String, // "host:port", "host:port@user:pass", or "user:pass@host:port"
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

#[derive(Debug, Clone)]
pub struct ParsedProxy {
    pub host: String,
    pub port: u16,
    pub auth: Option<String>,
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

    pub fn parsed_proxy(&self) -> Option<ParsedProxy> {
        let raw = self.proxy.trim();
        if raw.is_empty() {
            return None;
        }
        let cleaned = raw
            .strip_prefix("http://")
            .or_else(|| raw.strip_prefix("https://"))
            .unwrap_or(raw);

        let default_port = if self.transport_mode.contains("ssl") { 443 } else { 80 };

        let (host_part, auth) = if let Some((left, right)) = cleaned.split_once('@') {
            // HTTP Custom style: host:port@user:pass (left ends with numeric port)
            // Standard URL style: user:pass@host:port (right ends with numeric port)
            let left_is_host_port = parse_host_port(left, default_port).1;
            let right_is_host_port = parse_host_port(right, default_port).1;
            if left_is_host_port && !right_is_host_port {
                (left, Some(right.to_string()))
            } else {
                (right, Some(left.to_string()))
            }
        } else {
            (cleaned, None)
        };

        let ((host, port), _) = parse_host_port(host_part, default_port);
        if host.is_empty() {
            return None;
        }
        Some(ParsedProxy { host, port, auth })
    }
}

fn parse_host_port(input: &str, default_port: u16) -> ((String, u16), bool) {
    let trimmed = input.trim();
    if let Some(rest) = trimmed.strip_prefix('[') {
        if let Some((ip, after)) = rest.split_once(']') {
            if let Some(port_str) = after.strip_prefix(':') {
                if let Ok(port) = port_str.parse::<u16>() {
                    return ((ip.to_string(), port), true);
                }
            }
            return ((ip.to_string(), default_port), false);
        }
    }
    if let Some((h, p)) = trimmed.rsplit_once(':') {
        if let Ok(port) = p.parse::<u16>() {
            return ((h.trim().to_string(), port), true);
        }
    }
    ((trimmed.to_string(), default_port), false)
}
