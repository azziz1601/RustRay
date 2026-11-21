# Repository Guidelines

## Project Overview

RustRay is an Android VPN client for V2Ray-style links: `vless://`, `vmess://`, `trojan://`. The repository now includes a small Rust crate, `rustray-ssh/`, for SSH profiles alongside the prebuilt `io.github.aimalygin:xray-rust-mobile:0.10.0` JNI library. The xray-rust core is an independent, client-only Rust reimplementation of Xray, not the official Go `xray-core`.

App identity: `io.rustray.app`, version `1.2.6` (`versionCode` 12), `minSdk` 24, `targetSdk` 35. Imported profiles are editable, but saving clears `importedConfig`; there are no subscription URLs or latency checks. Profiles can also be exported/imported as encrypted `.rr` files. VLESS without TLS uses a DNS-alias workaround; Shizuku captive-portal control is opt-in. The UI has English and Indonesian strings, a widget, and Quick Settings tiles. Release APKs are produced by GitHub Actions; binaries are not committed.

## Architecture & Data Flow

Three code layers:

- `rustray-ssh/` — Rust SSH/SOCKS5/UDPGW crate producing `librustray_ssh.so`.
- `srcs/org/xrayrust/mobile/` — reusable SDK layer: JNI bridge, VPN service base, DNS bootstrap, importers, TUN helpers.
- `app/src/main/java/io/rustray/app/` — host Android app: activities, stores, config builder, Shizuku helper.

Key flows:

1. **Import**: clipboard → `MainActivity.importLink()` → `PlaintextVlessCompat.prepare()` → `XrayProfileImporter.profile()` → JNI `nativeImportProfileJson()` → JSON stored in `Profile.importedConfig` → `ProfileStore` SharedPreferences.
2. **Connect**: `MainActivity.onConnectClicked()` → VPN consent + notification permission → `startForegroundService(ACTION_CONNECT)` → `AppVpnService.handleConnect()` → `ConfigBuilder.configFor()` → `XrayVpnService.startXrayTunnel()`.
3. **Tunnel start**: `XrayVpnService.runXrayTunnelStart()` does DNS bootstrap under a 5s deadline, startup-probe preflight, `buildTunnel().establish()`, `XrayCore.create()` with a borrowed TUN fd, `core.start()`, then publishes the session and starts pump/watch threads. `XrayTunnelStateMachine` serializes start/stop with token identity; every accepted start ends in started, failed, or cancelled.
4. **Telemetry**: `MainActivity` polls `AppVpnService.instance.snapshot()` every second → `XrayVpnService.xrayVpnRuntimeSnapshot()` → JNI stats/connection snapshot.
5. **SSH**: `AppVpnService.handleConnect()` spawns `rustray-ssh-start` → `RustSshBridge.start()` → JNI `librustray_ssh` → local SOCKS5 on `127.0.0.1` → `ConfigBuilder.configForSsh()` → `startXrayTunnel()`. Modes `proxy` and `ssl_payload` route through `Profile.sshProxy`. The native engine uses `russh` 0.65 with `Channel::split()` and coalesced `ChannelMsg::Data`. Each UDP destination gets its own UDPGW conid; a one-packet DNS probe on conid 65530 sets `udpgw_probe_ok`, and `BridgeStats` exposes `probe`, `txB`/`rxB`, and `last_dst` through `RustSshBridge.statsSummary()` in the Logs dialog. When `enhanced` is set, the SSH window grows to 8 MiB, keepalive drops to 8s, TCP buffers grow to 256 KiB, and UDPGW keepalive drops to 5s. For `.rr` profiles, `resolve_rr_ssh_fields()` decrypts `rr_blob_base64` inside Rust and overlays locked fields before any Kotlin-side config is used.

`XrayCore` is `Closeable` around a `Long nativeHandle`. A fair `ReentrantReadWriteLock` separates lifecycle/native-free work (write lock) from the data path (read lock). Native results are decoded by typed snapshot helpers.

SDK features present but **not wired into the shipped app flow**: `XrayVlessUrlImporter`, `XrayVlessEncryption`, TUN admission callbacks, and outbound-health APIs. The app uses `XrayProfileImporter` for link import; avoid activating unused SDK entry points unless asked.

## Key Directories

- `srcs/org/xrayrust/mobile/` — SDK/JNI layer and reusable VPN service machinery.
- `app/src/main/java/io/rustray/app/` — activities, view adapter, models, stores, config building.
- `rustray-ssh/` — Rust crate; CI builds `librustray_ssh.so` into `app/src/main/jniLibs/` (gitignored).
- `app/src/main/res/` — Android resources; English-only.
- `app/src/main/AndroidManifest.xml` — declares launcher, editor, settings, VPN service, and `ShizukuProvider`.
- `.github/workflows/` — CI build workflow.
- `gradle/`, `gradlew`, `gradlew.bat` — Gradle 8.13 wrapper.
- `docs/` — launcher image only; no written docs.

## Development Commands

Release APKs are built in GitHub Actions (`.github/workflows/build.yml`) on push to `main`, pull requests, and `workflow_dispatch`. The workflow first builds the native SSH engine, then runs:

```bash
cd rustray-ssh
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
cd ..
./gradlew assembleRelease -x lintVitalAnalyzeRelease --no-daemon
```

Local build check, optional and not the source of the shipped APK:

```bash
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
cd rustray-ssh
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
cd ..
./gradlew assembleRelease
```

Other useful commands:

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew lint
```

CI uploads APKs as artifact `rustray-apks`; local builds land in `app/build/outputs/apk/release/`. There is no app `run` task; deploy locally with `installDebug` or Android Studio.

Release signing needs untracked root files `rustray-release.jks` and `keystore.properties`. Without them the APK builds but is unsigned and cannot upgrade a signed install. CI recreates them from secrets `KEYSTORE_BASE64` and `KEYSTORE_PROPERTIES`.

## Code Conventions & Common Patterns

- **Naming**: `Xray*` types belong to the SDK layer; `App*` types to the host app. Enums expose `wire`/`wireValue` plus `fromWire`/`fromWireValue`. Constants use `SCREAMING_SNAKE_CASE`.
- **Structure**: `object` singletons for stateless helpers, immutable `data class` snapshots, `internal` for SDK internals, `@JvmStatic`/`@JvmOverloads` on JNI-facing functions.
- **Formatting**: 4-space indent, trailing commas, Kotlin official style from `gradle.properties`. Public SDK APIs carry KDoc describing threading/lifecycle contracts; inline `//` comments explain concurrency decisions.
- **Error handling**: sealed result/admission types plus dedicated exceptions (`XrayCoreException`, `XrayStartCancelledException`, `AndroidDnsBootstrap*Exception`, `XrayVlessUrlImportException`). Native best-effort calls use `runCatching {}.getOrNull()/getOrDefault()/onFailure`; invariants use `require`/`check`. Credential byte arrays are zeroed in `finally`, `XrayImportedProfile.toString()` redacts secrets, and `AppVpnService.safeMessage()` truncates the first line to 220 chars.
- **Async**: no coroutines, `suspend`, Flow, LiveData, or ViewModel. Use `java.util.concurrent`: `ThreadPoolExecutor` on `SynchronousQueue` with `AbortPolicy`, named daemon threads (`xray-vpn-start-N`, `xray-dns-bootstrap-N`, `xray-tun-in/out`, `xray-vpn-network`), `FutureTask`, `ExecutorService`, `Atomic*`, `@Volatile`. UI updates use `Handler(Looper.getMainLooper())` and `ActivityResultContracts`.
- **State**: `VpnState` is process-global with `@Volatile`/`@Synchronized`; `EventLog` is a synchronized 300-entry log. `XrayTunnelStateMachine` uses a sealed state under `synchronized(Object)`; `XrayNetworkChangeState` uses `ReentrantLock.withLock`. `ProfileStore` and `SettingsStore` persist to SharedPreferences.
- **Dependency injection**: manual only — constructor `Context` for stores, lambdas for native import/DNS lookup/network selection, and `open`/`protected open` SDK hooks overridden by `AppVpnService`. No Hilt, Koin, or Dagger.
- **Serialization**: Kotlin uses `org.json` only; Rust uses `serde_json` with `camelCase` keys. No Kotlinx Serialization or Gson.
- **Rust**: snake_case modules and functions, `camelCase` JSON via `serde(rename_all = "camelCase")`, JNI exports `Java_io_rustray_app_RustSshBridge_native*` including `nativeGetStats`. `russh` is 0.65 with `ring` and `rsa` features and a direct `ring = "0.17"` dependency for `.rr`; `check_server_key` takes `PublicKeyOrCertificate`, preferred ciphers include `AES_128_GCM`, and client config sets `nodelay: true`. `build.rs` folds `RR_MASTER_SECRET` from the CI environment into `$OUT_DIR/rr_key_gen.rs`; absent secret uses a dev fallback. SSH config keys include `proxy`, `enhanced`, and `rr_blob_base64`; transport modes are `direct`, `payload`, `proxy`, `ssl`, `ssl_payload`, `ws`, `ssl_ws`; default pool size is 1. TLS certificate and SSH host-key verification are disabled; use only trusted endpoints.

## Important Files

| Path | Purpose |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Entry points: `MainActivity`, `EditProfileActivity`, `SettingsActivity`, `AppVpnService`, `ShizukuProvider`. |
| `srcs/org/xrayrust/mobile/XrayCore.kt` | JNI bridge, capability gating, `nativeHandle` lifecycle, typed native-result helpers, `XrayCoreException`. |
| `srcs/org/xrayrust/mobile/XrayVpnService.kt` | Reusable `VpnService` base, tunnel state machine, start/stop coordination, packet-pump threads. |
| `srcs/org/xrayrust/mobile/XrayAndroidDnsBootstrap.kt` | Deadline-bounded resolver, canonical `dns.hosts` mapping, fail-closed topology checks. |
| `srcs/org/xrayrust/mobile/XrayProfileImporter.kt` | Link-to-JSON import via native code, validation, credential zeroing. |
| `app/src/main/java/io/rustray/app/AppVpnService.kt` | Host VPN service: `ACTION_CONNECT`/`ACTION_STOP`, `buildTunnel()` override, callbacks, network rebind, `onRevoke()`. |
| `app/src/main/java/io/rustray/app/MainActivity.kt` | Home UI, clipboard import, permission launchers, one-second status polling. |
| `app/src/main/java/io/rustray/app/ConfigBuilder.kt`, `app/src/main/java/io/rustray/app/PlaintextVlessCompat.kt` | Config assembly and no-TLS VLESS DNS-alias workaround. |
| `app/src/main/java/io/rustray/app/RustSshBridge.kt` | JNI bridge: `System.loadLibrary("rustray_ssh")`, `start/stop/rebind`, and `statsSummary()` via `nativeGetStats`. |
| `rustray-ssh/src/lib.rs`, `config.rs`, `transport.rs`, `ssh_pool.rs`, `socks5.rs`, `udpgw_client.rs`, `stats.rs`, `protocol.rs` | Native SSH engine on russh 0.65: transport modes, per-destination UDPGW conids, UDPGW probe, lock-free channel I/O, remote DNS fallback, SOCKS5 endpoint, and atomic telemetry with byte counters. |
| `app/src/main/java/io/rustray/app/RrConfigCodec.kt`, `rustray-ssh/src/rr_crypto.rs`, `rustray-ssh/build.rs`, `RR_SECRET_SETUP.md`, `RR_SECRET_SETUP.en.md` | `RR01` AES-256-GCM `.rr` export/import, compile-time `RR_MASTER_SECRET` injection, and bilingual setup guides. Public clones fall back to a dev key and cannot open official `.rr` files. |
| `app/src/main/java/io/rustray/app/RustRayWidgetProvider.kt`, `RustRayVpnTileService.kt`, `RustRayProfileTileService.kt`, `TileSync.kt`, `res/xml/rustray_widget_info.xml`, `res/layout/widget_rustray.xml` | Home-screen widget and Quick Settings tiles, including widget action broadcasts. |
| `app/src/main/res/values-in/strings.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/xml/file_paths.xml`, `app/src/main/res/layout/item_profile.xml`, `app/src/main/res/layout/dialog_export_rr.xml` | Indonesian localization, FileProvider paths for `.rr` sharing, card-style profile rows, and `.rr` export dialog. |
| `app/src/main/res/layout/activity_edit_profile.xml` | SSH editor fields, remote proxy input, and transport visibility controls. |
| `app/build.gradle.kts` | SDK/min/target levels, ABI splits, R8/lint settings, dependency versions. |
| `app/proguard-rules.pro` | Keeps `org.xrayrust.mobile.**`, native methods, `RustSshBridge`, Shizuku APIs; do not remove when changing R8 config. |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` | Build configuration and toolchain pins. |
| `.github/workflows/build.yml` | CI build, decode signing files, upload APK artifact. |
| `README.md` | Human-facing build, signing, and limitation notes. |
| `.agents/AGENTS.md`, `CLAUDE.md` | Existing agent workflow and state-memory instructions; project slug `rustray`. |

## Runtime/Tooling Preferences

- JDK 17+; Android SDK platform 35 and build-tools 35.0.0. Gradle wrapper 8.13, AGP 8.9.2, Kotlin Android plugin 2.1.10.
- Single Gradle module `:app`. Repositories are declared centrally; `RepositoriesMode.FAIL_ON_PROJECT_REPOS` rejects `repositories {}` inside the module.
- `gradle.properties` enables parallel builds, caching, and configuration cache with `-Xmx3072m`. Configuration-cache incompatibilities can surface as task failures.
- Dependency versions are inline in `app/build.gradle.kts`; no version catalog or dependency-verification metadata.
- ABI splits: `arm64-v8a`, `armeabi-v7a`; `isUniversalApk = false`. JNI libs use `jniLibs.useLegacyPackaging = true`.
- Rust toolchain with Android targets `aarch64-linux-android` and `armv7-linux-androideabi`, plus `cargo-ndk`; no Node or Bun required. No `Cargo.lock` is committed; `rustray-ssh/target/` and generated `app/src/main/jniLibs/` are gitignored.
- CI uses `ubuntu-latest`, Temurin 17, `dtolnay/rust-toolchain@stable`, `Swatinem/rust-cache@v2`, `taiki-e/install-action@v2` with `cargo-binstall`, `cargo binstall cargo-ndk`, `gradle/actions/setup-gradle@v4`, and upload-artifact `rustray-apks`; cache is read-only outside `main`.

## Testing & QA

- There is no automated test suite: no `app/src/test/`, `app/src/androidTest/`, JUnit/Kotlin test dependencies, fixtures, snapshots, or coverage tooling. `./gradlew test` and `./gradlew connectedAndroidTest` have nothing to run.
- CI builds both `librustray_ssh.so` and the APKs; `app/build.gradle.kts` sets `lint.checkReleaseBuilds = true`, CI skips `lintVitalAnalyzeRelease`, and local `assembleRelease` runs it. `README.md` states the release was verified for compilation, lint, signing v2, and zipalign, but never on a physical device or emulator.
- Adding tests requires adding `testImplementation`/`androidTestImplementation` and source sets in `app/build.gradle.kts`; there is no existing test convention to preserve.
