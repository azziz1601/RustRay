mod config;
mod protocol;
mod socks5;
mod ssh_pool;
mod transport;
mod udpgw_client;

use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::jint;
use jni::JNIEnv;
use tokio::runtime::Runtime;
use tokio::sync::watch;

use crate::config::SshBridgeConfig;
use crate::socks5::start_socks5_server;
use crate::ssh_pool::SshPool;
use crate::transport::SocketProtector;
use crate::udpgw_client::UdpgwClient;

struct RunningBridge {
    shutdown_tx: watch::Sender<bool>,
    pool: Arc<SshPool>,
    udpgw: Arc<UdpgwClient>,
    _runtime: Runtime,
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

    let cfg: SshBridgeConfig = match serde_json::from_str(&json_str) {
        Ok(c) => c,
        Err(e) => {
            throw_runtime(&mut env, &format!("Invalid SSH config JSON: {e}"));
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

    let start_res = runtime.block_on(async {
        pool.warmup().await?;
        let udpgw = UdpgwClient::spawn(Arc::clone(&pool), shutdown_rx.clone());
        let port = start_socks5_server(Arc::clone(&pool), Arc::clone(&udpgw), shutdown_rx).await?;
        Ok::<(u16, Arc<UdpgwClient>), std::io::Error>((port, udpgw))
    });

    match start_res {
        Ok((port, udpgw)) => {
            if let Ok(mut guard) = state().lock() {
                *guard = Some(RunningBridge {
                    shutdown_tx,
                    pool,
                    udpgw,
                    _runtime: runtime,
                });
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
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_existing();
}

#[no_mangle]
pub extern "system" fn Java_io_rustray_app_RustSshBridge_nativeRebind(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(guard) = state().lock() {
        if let Some(running) = guard.as_ref() {
            running.udpgw.trigger_rebind();
            let pool = Arc::clone(&running.pool);
            running._runtime.spawn(async move {
                pool.reset_all().await;
            });
        }
    }
}

fn stop_existing() {
    if let Ok(mut guard) = state().lock() {
        if let Some(running) = guard.take() {
            let _ = running.shutdown_tx.send(true);
            drop(running);
        }
    }
}
