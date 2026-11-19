# RustRay

[![Build RustRay APK](https://github.com/azziz1601/RustRay/actions/workflows/build.yml/badge.svg)](https://github.com/azziz1601/RustRay/actions/workflows/build.yml)

RustRay is a small Android VPN client for V2Ray-style proxy links. It uses the
[xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) library, a
Rust implementation of Xray that is designed for mobile clients.

Supported link formats:

- `vless://` (including TLS, REALITY, and Vision flow)
- `vmess://` (including alterId and cipher selection)
- `trojan://`

Supported transports: **TCP, WebSocket, HTTPUpgrade, gRPC, and XHTTP**.

> RustRay uses **xray-rust**, an independent, client-only Rust reimplementation.
> It is not the official XTLS Go [Xray-core](https://github.com/XTLS/Xray-core)
> port. See the [xray-rust](https://github.com/aimalygin/xray-rust) repository
> for details.

## What it can do

- Add, edit, and delete connection profiles; pick the active one.
- Import `vless://`, `vmess://`, or `trojan://` links from the clipboard. Imported
  profiles are read-only.
- Connect and disconnect with Android's standard VPN permission prompt and a
  foreground notification (works on Android 14+).
- Show live upload/download speed, total traffic, session duration, and the
  number of active connections.
- View a simple event log from the toolbar.
- Reconnect automatically when the network changes (for example Wi-Fi to
  mobile data) and keep traffic bound to the active non-VPN network.
- Offer a gaming preset with a 1380 MTU and throughput-oriented tuning.
- Build the TUN configuration locally with fake-IP DNS, direct routing for
  private addresses, and HTTP/TLS/QUIC sniffing.

## Download the APK

Every push to `main` builds release APKs with GitHub Actions.

1. Open the [Actions tab](https://github.com/azziz1601/RustRay/actions).
2. Select the latest **Build RustRay APK** run.
3. Scroll down to **Artifacts** and download `rustray-apks`.
4. Unzip it and install the APK for your device:
   - `app-arm64-v8a-release.apk` — most modern Android phones
   - `app-armeabi-v7a-release.apk` — older 32-bit ARM devices

You can also trigger a build manually with **Run workflow** on the Actions page.

The package name is `io.rustray.app`, version 1.1.0 (`versionCode` 3),
`minSdk` 24, and `targetSdk` 35.

## Build it yourself

You need:

- JDK 17 or newer
- Android SDK with platform 35 and build-tools 35.0.0
- Gradle (the repository includes the Gradle wrapper, so you do not need to
  install Gradle separately)

```bash
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

The APKs are written to `app/build/outputs/apk/release/`.

## Known limitations

- REALITY does not work over WebSocket or HTTPUpgrade with the current core;
  the form automatically switches those combinations to TLS.
- Profiles imported from links cannot be edited in the app. Delete and import
  them again instead.
- VLESS without TLS is unencrypted. The core rejects such outbound connections
  to public hosts, so RustRay uses a single-label DNS alias and a `dns.hosts`
  entry to keep those links working. Only use this with providers that
  intentionally issue plaintext links.
- Subscription URLs, latency checks, and per-app split tunneling are not
  implemented yet.
- The release build has been verified for compilation, lint, APK signing v2,
  and zipalign, but it has not been tested on a physical device or emulator.

## Main source files

| File | Purpose |
| --- | --- |
| `app/src/main/java/io/rustray/app/AppVpnService.kt` | VPN service: foreground notification, tunnel lifecycle, callbacks, statistics |
| `Profile.kt` / `ProfileStore.kt` | Profile model and local storage |
| `ConfigBuilder.kt` | Builds the TUN JSON configuration for manual profiles |
| `PlaintextVlessCompat.kt` | DNS alias compatibility for plaintext VLESS links |
| `MainActivity.kt` | Home screen, connect button, profile list, clipboard import, event log |
| `EditProfileActivity.kt` | Profile editor (protocol, credentials, transport, security) |

## Credits

- [xray-rust](https://github.com/aimalygin/xray-rust) and
  [xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) by
  [@aimalygin](https://github.com/aimalygin)
- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) as the reference project
