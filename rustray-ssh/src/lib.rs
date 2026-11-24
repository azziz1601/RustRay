mod config;
mod payload_bridge;
mod protocol;
mod rr_crypto;
mod socks5;
mod ssh_pool;
mod stats;
mod transport;
mod udpgw_client;

use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;
use tokio::runtime::Runtime;
use tokio::sync::watch;

use crate::config::SshBridgeConfig;
use crate::payload_bridge::{start_payload_bridge, PayloadBridgeStats};
use crate::socks5::start_socks5_server;
use crate::ssh_pool::SshPool;
use crate::stats::BridgeStats;
use crate::transport::SocketProtector;
use crate::udpgw_client::UdpgwClient;

struct RunningSshBridge {
    shutdown_tx: watch::Sender<bool>,
    pool: Arc<SshPool>,
    udpgw: Arc<UdpgwClient>,
    stats: Arc<BridgeStats>,
    _runtime: Runtime,
}

struct RunningPayloadBridge {
    shutdown_tx: watch::Sender<bool>,
    stats: Arc<PayloadBridgeStats>,
    _runtime: Runtime,
}

enum RunningBridge {
    Ssh(RunningSshBridge),
    Payload(RunningPayloadBridge),
}

static STATE: OnceLock<Mutex<Option<RunningBridge>>> = OnceLock::new();

fn state() -> &'static Mutex<Option<RunningBridge>> {
    STATE.get_or_init(|| Mutex::new(None))
}

fn init_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("RustRaySSH"),
    );
}

fn throw_runtime(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

/// If the SSH config carries an encrypted `.rr` blob, decrypt it directly inside native Rust
/// memory and overlay locked fields so locked credentials/payloads never touch Kotlin plaintext.
fn resolve_rr_ssh_fields(mut cfg: SshBridgeConfig) -> Result<SshBridgeConfig, String> {
    if cfg.rr_blob_base64.trim().is_empty() {
        return Ok(cfg);
    }
    let raw = base64::engine::general_purpose::STANDARD
        .decode(cfg.rr_blob_base64.trim())
        .map_err(|e| format!("Invalid .rr blob encoding: {e}"))?;
    let plain = rr_crypto::decrypt_rr(&raw)?;
    let root: serde_json::Value =
        serde_json::from_slice(&plain).map_err(|e| format!("Invalid .rr JSON: {e}"))?;

    let expiry = root.get("expiryEpochMs").and_then(|v| v.as_i64()).unwrap_or(0);
    if expiry > 0 {
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        if now_ms > expiry {
            return Err("Config expired".to_string());
        }
    }

    let lock_server = root.get("lockServer").and_then(|v| v.as_bool()).unwrap_or(false);
    let lock_payload = root.get("lockPayload").and_then(|v| v.as_bool()).unwrap_or(false);
    if let Some(prof) = root.get("profile") {
        if lock_server {
            if let Some(v) = prof.get("server").and_then(|v| v.as_str()) {
                cfg.host = v.to_string();
            }
            if let Some(v) = prof.get("port").and_then(|v| v.as_u64()) {
                cfg.port = v as u16;
            }
            if let Some(v) = prof.get("sshUsername").and_then(|v| v.as_str()) {
                cfg.username = v.to_string();
            }
            if let Some(v) = prof.get("credential").and_then(|v| v.as_str()) {
                cfg.password = v.to_string();
            }
        }
        if lock_payload {
            if let Some(v) = prof.get("sshTransport").and_then(|v| v.as_str()) {
                cfg.transport_mode = v.to_string();
            }
            if let Some(v) = prof.get("sshPayload").and_then(|v| v.as_str()) {
                cfg.payload = v.to_string();
            }
            if let Some(v) = prof.get("sshProxy").and_then(|v| v.as_str()) {
                cfg.proxy = v.to_string();
            }
            if let Some(v) = prof.get("sni").and_then(|v| v.as_str()) {
                cfg.sni = v.to_string();
            }
            if let Some(v) = prof.get("host").and_then(|v| v.as_str()) {
                cfg.ws_host = v.to_string();
            }
            if let Some(v) = prof.get("path").and_then(|v| v.as_str()) {
                cfg.ws_path = v.to_string();
            }
        }
    }
    cfg.rr_blob_base64.clear();
    Ok(cfg)
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    protector_obj: JObject,
) -> jint {
    init_logger();

    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid config string: {e}"));
            return -1;
        }
    };

    let raw_cfg: SshBridgeConfig = match serde_json::from_str(&json_str) {
        Ok(c) => c,
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid SSH config JSON: {e}"));
            return -1;
        }
    };

    let cfg = match resolve_rr_ssh_fields(raw_cfg) {
        Ok(c) => c,
        Err(e) => {
            throw_runtime(&mut env, &e);
            return -1;
        }
    };

    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(e) => {
            throw_runtime(&mut env, &format!("Failed to get JavaVM: {e}"));
            return -1;
        }
    };

    let protector_ref: GlobalRef = match env.new_global_ref(protector_obj) {
        Ok(r) => r,
        Err(e) => {
            throw_runtime(&mut env, &format!("Failed to pin socket protector: {e}"));
            return -1;
        }
    };

    let protector: SocketProtector = Arc::new(move |fd: i32| -> bool {
        let Ok(mut guard) = vm.attach_current_thread() else {
            return false;
        };
        guard
            .call_method(
                protector_ref.as_obj(),
                "protectSocket",
                "(I)Z",
                &[JValue::Int(fd)],
            )
            .and_then(|v| v.z())
            .unwrap_or(false)
    });

    stop_existing();

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("rustray-ssh")
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            throw_runtime(&mut env, &format!("Failed to start Tokio runtime: {e}"));
            return -1;
        }
    };

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let pool = Arc::new(SshPool::new(Arc::new(cfg), protector));
    let stats = Arc::new(BridgeStats::new());

    let start_res = runtime.block_on(async {
        pool.warmup().await?;
        let udpgw = UdpgwClient::spawn(Arc::clone(&pool), Arc::clone(&stats), shutdown_rx.clone());
        let port = start_socks5_server(
            Arc::clone(&pool),
            Arc::clone(&udpgw),
            Arc::clone(&stats),
            shutdown_rx,
        )
        .await?;
        Ok::<(u16, Arc<UdpgwClient>), std::io::Error>((port, udpgw))
    });

    match start_res {
        Ok((port, udpgw)) => {
            if let Ok(mut guard) = state().lock() {
                *guard = Some(RunningBridge::Ssh(RunningSshBridge {
                    shutdown_tx,
                    pool,
                    udpgw,
                    stats,
                    _runtime: runtime,
                }));
            }
            port as jint
        }
        Err(err) => {
            let _ = shutdown_tx.send(true);
            drop(runtime);
            throw_runtime(&mut env, &err.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeEncryptRr(
    mut env: JNIEnv,
    _class: JClass,
    plain_json: JString,
) -> jbyteArray {
    let text: String = match env.get_string(&plain_json) {
        Ok(s) => s.into(),
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid JSON string: {e}"));
            return std::ptr::null_mut();
        }
    };
    match rr_crypto::encrypt_rr(text.as_bytes()) {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map(|arr| arr.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            throw_runtime(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeDecryptRr(
    mut env: JNIEnv,
    _class: JClass,
    blob: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(blob) {
        Ok(b) => b,
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid byte array: {e}"));
            return std::ptr::null_mut();
        }
    };
    match rr_crypto::decrypt_rr(&bytes)
        .and_then(|plain| String::from_utf8(plain).map_err(|_| "Invalid UTF-8 in .rr config".to_string()))
    {
        Ok(text) => env
            .new_string(text)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            throw_runtime(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_existing();
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeStartPayloadBridge(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    protector_obj: JObject,
) -> jint {
    init_logger();

    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid config string: {e}"));
            return -1;
        }
    };

    let cfg: SshBridgeConfig = match serde_json::from_str(&json_str) {
        Ok(c) => c,
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid payload bridge config JSON: {e}"));
            return -1;
        }
    };

    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(e) => {
            throw_runtime(&mut env, &format!("Failed to get JavaVM: {e}"));
            return -1;
        }
    };

    let protector_ref: GlobalRef = match env.new_global_ref(protector_obj) {
        Ok(r) => r,
        Err(e) => {
            throw_runtime(&mut env, &format!("Failed to pin socket protector: {e}"));
            return -1;
        }
    };

    let protector: SocketProtector = Arc::new(move |fd: i32| -> bool {
        let Ok(mut guard) = vm.attach_current_thread() else {
            return false;
        };
        guard
            .call_method(
                protector_ref.as_obj(),
                "protectSocket",
                "(I)Z",
                &[JValue::Int(fd)],
            )
            .and_then(|v| v.z())
            .unwrap_or(false)
    });

    stop_existing();

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("rustray-payload")
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            throw_runtime(&mut env, &format!("Failed to start Tokio runtime: {e}"));
            return -1;
        }
    };

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let stats = Arc::new(PayloadBridgeStats::new());

    let start_res = runtime.block_on(start_payload_bridge(
        Arc::new(cfg),
        protector,
        Arc::clone(&stats),
        shutdown_rx,
    ));

    match start_res {
        Ok(port) => {
            if let Ok(mut guard) = state().lock() {
                *guard = Some(RunningBridge::Payload(RunningPayloadBridge {
                    shutdown_tx,
                    stats,
                    _runtime: runtime,
                }));
            }
            port as jint
        }
        Err(err) => {
            let _ = shutdown_tx.send(true);
            drop(runtime);
            throw_runtime(&mut env, &err.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeRebind(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(guard) = state().lock() {
        if let Some(RunningBridge::Ssh(running)) = guard.as_ref() {
            running.udpgw.trigger_rebind();
            let pool = Arc::clone(&running.pool);
            running._runtime.spawn(async move {
                pool.reset_all().await;
            });
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeGetStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = state()
        .lock()
        .ok()
        .and_then(|g| {
            g.as_ref().map(|r| match r {
                RunningBridge::Ssh(s) => s.stats.summary(),
                RunningBridge::Payload(p) => p.stats.summary(),
            })
        })
        .unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn stop_existing() {
    if let Ok(mut guard) = state().lock() {
        if let Some(running) = guard.take() {
            match running {
                RunningBridge::Ssh(s) => {
                    let _ = s.shutdown_tx.send(true);
                    drop(s);
                }
                RunningBridge::Payload(p) => {
                    let _ = p.shutdown_tx.send(true);
                    drop(p);
                }
            }
        }
    }
}
