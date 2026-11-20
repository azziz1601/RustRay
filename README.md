# RustRay

[![Build RustRay APK](https://github.com/azziz1601/RustRay/actions/workflows/build.yml/badge.svg)](https://github.com/azziz1601/RustRay/actions/workflows/build.yml)
[![Downloads](https://img.shields.io/github/downloads/azziz1601/RustRay/total?label=downloads)](https://github.com/azziz1601/RustRay/releases)

<p align="center">
  <img src="docs/ic_launcher.png" alt="RustRay app icon" width="128" height="128">
</p>

RustRay is a small Android VPN client for V2Ray-style proxy links. It uses the
[xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) library, a
Rust implementation of Xray that is designed for mobile clients.

Supported link formats:

- `vless://` (including TLS, REALITY, and Vision flow)
- `vmess://` (including alterId and cipher selection)
- `trojan://`

Supported transports: **TCP, WebSocket, HTTPUpgrade, gRPC, and XHTTP**.
Manual SSH profiles add direct, payload, proxy, SSL, SSL+payload, WebSocket, and SSL+WebSocket modes with pooled connections, per-destination UDPGW channels, remote UDPGW DNS, live engine telemetry, and optional HTTP upstream proxy.

> RustRay uses **xray-rust**, an independent, client-only Rust reimplementation.
> It is not the official XTLS Go [Xray-core](https://github.com/XTLS/Xray-core)
> port. See the [xray-rust](https://github.com/aimalygin/xray-rust) repository
> for details.

## What it can do

- Add, edit, and delete connection profiles; pick the active one.
- Import `vless://`, `vmess://`, or `trojan://` links from the clipboard. Imported
  profiles are editable; saving clears the original imported config.
- Connect and disconnect with Android's standard VPN permission prompt and a
  foreground notification (works on Android 14+).
- Show live upload/download speed, total traffic, session duration, and the
  number of active connections.
- View a simple event log from the toolbar.
- Reconnect automatically when the network changes (for example Wi-Fi to
  mobile data) and optionally keep traffic bound to the active non-VPN network.
- Tune the tunnel from the Settings screen: runtime profile, MTU, IPv6 route,
  DNS/FakeIP, sniffing, and per-app split tunneling (include or exclude apps).
- Optionally use [Shizuku](https://shizuku.rikka.app/) to disable Android's
  captive-portal probe while the VPN is active and restore the previous values
  when it disconnects. This is off by default.
- Offer a gaming preset with a 1380 MTU and throughput-oriented tuning.
- Build the TUN configuration locally with fake-IP DNS, direct routing for
  private addresses, and HTTP/TLS/QUIC sniffing.
- Run SSH accounts through a native Rust bridge with a local SOCKS5 endpoint,
  seven transport modes, per-destination UDPGW channels, HTTP Custom upstream
  proxies (`host:port` or `host:port@user:pass`), automatic fallback to the
  primary session, and live engine counters shown in the Logs dialog.

## Download the APK

Every push to `main` builds release APKs with GitHub Actions.

1. Open the [Actions tab](https://github.com/azziz1601/RustRay/actions).
2. Select the latest **Build RustRay APK** run.
3. Scroll down to **Artifacts** and download `rustray-apks`.
4. Unzip it and install the APK for your device:
   - `app-arm64-v8a-release.apk` — most modern Android phones
   - `app-armeabi-v7a-release.apk` — older 32-bit ARM devices

You can also trigger a build manually with **Run workflow** on the Actions page.

The package name is `io.rustray.app`, version 1.2.4 (`versionCode` 7),
`minSdk` 24, and `targetSdk` 35.

## Build it yourself

You need:

- JDK 17 or newer
- Android SDK with platform 35 and build-tools 35.0.0
- Rust toolchain with `aarch64-linux-android` and `armv7-linux-androideabi` targets
- `cargo-ndk` for the native SSH library
- Gradle (the repository includes the Gradle wrapper, so you do not need to
  install Gradle separately)

```bash
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
cd rustray-ssh
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
cd ..
./gradlew assembleRelease
```

The APKs are written to `app/build/outputs/apk/release/`.

### Release signing

The release build uses `rustray-release.jks` plus `keystore.properties`. Both
files are intentionally **not tracked** in this public repository.

- Locally: place both files in the project root before running `assembleRelease`.
- In GitHub Actions: store the keystore as repository secret `KEYSTORE_BASE64`
  (base64 of the `.jks` file) and its properties as `KEYSTORE_PROPERTIES`
  (contents of `keystore.properties`). The workflow decodes them at build time.
  `storePassword`, `keyAlias`, and `keyPassword` must match the keystore.

Without those files the build still succeeds, but the APK is unsigned and cannot
upgrade an existing signed installation. Keep the keystore safe: anyone holding
it can produce APKs that devices treat as official updates.

## Known limitations

- REALITY does not work over WebSocket or HTTPUpgrade with the current core;
  the form automatically switches those combinations to TLS.
- Editing an imported profile clears its original `importedConfig`; fields not
  represented in the editor can be lost.
- The SSH engine ships only for `arm64-v8a` and `armeabi-v7a`; other ABIs and
  emulators cannot load SSH profiles. SSH connections accept any server
  certificate or host key, so use only trusted endpoints.
- Proxy credentials are sent as base64 `Proxy-Authorization`; use an SSL mode
  when the proxy is not on a trusted network.
- UDPGW resolves domain targets through `1.1.1.1:53` with fallback to `8.8.8.8:53`
  inside the tunnel to avoid fake-IP DNS loops; the SSH server must expose the
  configured UDPGW port.
- VLESS without TLS is unencrypted. The core rejects such outbound connections
  to public hosts, so RustRay uses a single-label DNS alias and a `dns.hosts`
  entry to keep those links working. Only use this with providers that
  intentionally issue plaintext links.
- Subscription URLs and latency checks are not implemented yet.
- Shizuku-based captive-portal control requires Shizuku to be installed and
  granted to RustRay; it is disabled by default.
- The release build has been verified for compilation, lint, APK signing v2,
  and zipalign, but it has not been tested on a physical device or emulator.

## Main source files

| File | Purpose |
| --- | --- |
| `app/src/main/java/io/rustray/app/AppVpnService.kt` | VPN service: foreground notification, tunnel lifecycle, callbacks, statistics |
| `Profile.kt` / `ProfileStore.kt` | Profile model and local storage |
| `ConfigBuilder.kt` | Builds the TUN JSON configuration for manual profiles |
| `PlaintextVlessCompat.kt` | DNS alias compatibility for plaintext VLESS links |
| `AppSettings.kt` | Settings model, presets, and persistence |
| `SettingsActivity.kt` | Settings UI: tuning, DNS, split tunneling, Shizuku controls |
| `ShizukuCaptivePortal.kt` | Optional captive-portal disable/restore via Shizuku |
| `MainActivity.kt` | Home screen, connect button, profile list, clipboard import, event log |
| `EditProfileActivity.kt` | Profile editor (protocol, credentials, transport, security) |
| `RustSshBridge.kt` | JNI entry point for the native SSH engine |
| `rustray-ssh/` | Rust SSH, SOCKS5, and UDPGW core built by GitHub Actions |

## Credits

- [xray-rust](https://github.com/aimalygin/xray-rust) and
  [xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) by
  [@aimalygin](https://github.com/aimalygin)
- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) as the reference project
- [Shizuku](https://github.com/RikkaApps/Shizuku) for optional privileged helpers
- [russh](https://github.com/warp-tech/russh) for the SSH protocol implementation.
