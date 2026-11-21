# RustRay

[![Build RustRay APK](https://github.com/azziz1601/RustRay/actions/workflows/build.yml/badge.svg)](https://github.com/azziz1601/RustRay/actions/workflows/build.yml)
[![Downloads](https://img.shields.io/github/downloads/azziz1601/RustRay/total?label=downloads)](https://github.com/azziz1601/RustRay/releases)

<p align="center">
  <img src="docs/ic_launcher.png" alt="RustRay app icon" width="128" height="128">
</p>

[English](#english) | [Bahasa Indonesia](#bahasa-indonesia)

---

&lt;a id=&quot;english&quot;&gt;&lt;/a&gt;
## English

**RustRay** is a lightweight, native Rust-powered Android VPN client combining [`xray-rust-mobile`](https://github.com/aimalygin/xray-rust-mobile) (`0.10.0`) with a custom native Rust SSH + BadVPN UDPGW engine (`russh 0.65` + `ring`).

### Supported Protocols
- **VLESS** (TLS, REALITY, Vision flow, TCP, WebSocket, HTTPUpgrade, gRPC, XHTTP, and plaintext compatibility)
- **VMess** (Alter ID, cipher selection, all stream transports)
- **Trojan** (TLS/REALITY and stream transports)
- **SSH Hybrid (Native Rust)** — runs `russh 0.65` directly inside `librustray_ssh.so` bridged to `xray-rust` TUN via local SOCKS5 + native lock-free BadVPN UDPGW client (`badvpn-udpgw` & `badvpn-udpgw-rs` wire-compatible)

### Key Features
- **Native Rust SSH Hybrid Engine**:
  - 7 injector modes: `SSH Direct`, `SSH + Payload`, `SSH + Payload + Proxy`, `SSH + SSL / SNI`, `SSH + SSL + Payload`, `SSH + WebSocket`, and `SSH + SSL + WebSocket`.
  - HTTP Custom-style payload tags (`[crlf]`, `[host_port]`, `[sni]`, `[netData]`, `[raw]`, `[auth]`, `[split]`, `[delay_split]`) and flexible Remote Proxy (`host:port` or `host:port@user:pass`).
  - **Parallel Connections (1–4)** with dedicated UDPGW session isolation when set to `>= 2` (automatic fallback to primary session on single-login servers).
  - **Enhanced SSH Mode** toggle: 256 KB TCP socket buffers, 8 MiB SSH window, 8s SSH / 5s UDPGW keepalive, and dedicated UDPGW channel.
  - Lock-free `Channel::split()` UDPGW client with frame batching, per-destination `conid` mapping, async DNS-over-UDPGW resolution, and live startup DNS probe in Logs.
- **Encrypted `.rr` Config Export & Import**:
  - Native Rust `AES-256-GCM` encryption (`ring`) with compile-time secret injection via GitHub Actions (`RR_MASTER_SECRET` in `rustray-ssh/build.rs`).
  - Export with custom filename, **Lock Server & Account**, **Lock Payload/Proxy/SNI**, **Expiry Date**, and **HTML Notes** (clickable links).
  - Locked fields are never persisted as plaintext in `ProfileStore`; SSH locked credentials/payloads are decrypted directly in Rust memory on connect.
- **Gaming & Network Tuning**:
  - Ready-made **Game Mode** preset (MTU 1380, `Throughput` TUN profile, FakeIP/Sniffing off, custom DNS).
  - Configurable MTU (`1280–9000`), 6 TUN runtime profiles, IPv6 route, Auto Network Rebind, Bind Underlying Network, Sniffing, FakeIP, Custom DNS, and Per-App Split Tunneling (`All`, `Bypass`, `Only Selected`).
  - **Shizuku Integration** (`dev.rikka.shizuku:api:13.1.5`) for automatic and manual Captive Portal control.
- **Everyday Convenience**:
  - Clean profile cards with left active accent bar and quick **Edit**, **Share/Export**, and **Delete** icons (all imported profiles are editable).
  - **Home Screen Widget** (1-tap Connect/Disconnect).
  - **Quick Settings Tiles**: `RustRay` toggle tile and `Switch Profile` pop-up tile with instant profile switching & reconnect.
  - **Live Traffic Notification** (2-second screen-on updates, toggleable in Settings) with **Reconnect** and **Disconnect** actions.
  - Full **English** (default) and **Bahasa Indonesia** UI language switcher in Settings.

### Core Dependencies & Architecture

| Component | Source |
| --- | --- |
| Xray Rust Core | https://github.com/aimalygin/xray-rust |
| Android Mobile SDK | `io.github.aimalygin:xray-rust-mobile:0.10.0` |
| Native Rust SSH Core | `russh 0.65` (`ring` + `rsa`) + `tokio-rustls` |
| Shizuku API | `dev.rikka.shizuku:api:13.1.5` & `provider:13.1.5` |

### Building from Source

Prerequisites: JDK 17+, Android SDK (compileSdk 35, minSdk 24), Android NDK, Rust toolchain (`aarch64-linux-android`, `armv7-linux-androideabi`), and `cargo-ndk`.

```bash
# 1. Build native SSH & .rr crypto library (arm64-v8a & armeabi-v7a)
cd rustray-ssh
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
cd ..

# 2. Build Android release APKs (ABI-split, R8 minified)
./gradlew assembleRelease
```

For GitHub Actions automated builds and setting up your private `.rr` encryption key (`RR_MASTER_SECRET`) and release signing (`KEYSTORE_BASE64`, `KEYSTORE_PROPERTIES`), see [RR\_SECRET\_SETUP.md](https://www.google.com/search?q=RR_SECRET_SETUP.md).

---

&lt;a id=&quot;bahasa-indonesia&quot;&gt;&lt;/a&gt;
## Bahasa Indonesia

**RustRay** adalah klien VPN Android ringan berbasis Rust native yang menggabungkan inti [`xray-rust-mobile`](https://github.com/aimalygin/xray-rust-mobile) (`0.10.0`) dengan engine SSH + BadVPN UDPGW native Rust buatan sendiri (`russh 0.65` + `ring`).

### Protokol yang Didukung
- **VLESS** (TLS, REALITY, Vision flow, TCP, WebSocket, HTTPUpgrade, gRPC, XHTTP, dan kompatibilitas tanpa TLS)
- **VMess** (Alter ID, pilihan enkripsi, seluruh transport stream)
- **Trojan** (TLS/REALITY dan transport stream)
- **SSH Hybrid (Native Rust)** — menjalankan `russh 0.65` langsung di dalam `librustray_ssh.so` yang terhubung ke TUN `xray-rust` via SOCKS5 lokal + klien BadVPN UDPGW lock-free (kompatibel penuh dengan `badvpn-udpgw` C dan `badvpn-udpgw-rs`)

### Fitur Utama
- **Engine SSH Hybrid Native Rust**:
  - 7 mode injector ala HTTP Custom: `SSH Direct`, `SSH + Payload`, `SSH + Payload + Proxy`, `SSH + SSL / SNI`, `SSH + SSL + Payload`, `SSH + WebSocket`, dan `SSH + SSL + WebSocket`.
  - Mendukung tag payload lengkap (`[crlf]`, `[host_port]`, `[sni]`, `[netData]`, `[raw]`, `[auth]`, `[split]`, `[delay_split]`) dan Remote Proxy fleksibel (`host:port` atau `host:port@user:pass`).
  - **Koneksi Paralel (1–4)** yang memisahkan jalur UDPGW game ke sesi SSH tersendiri saat diatur `>= 2` (dengan fallback otomatis ke sesi utama pada server 1 login).
  - Toggle **Mode SSH Enhanced**: socket buffer TCP 256 KB, SSH window 8 MiB, keepalive SSH 8 detik / UDPGW 5 detik, dan jalur UDPGW terpisah.
  - Klien UDPGW lock-free (`Channel::split()`) dengan batching frame, pemetaan `conid` per alamat tujuan, resolver DNS-over-UDPGW asinkron, serta telemetri probe DNS live di layar Logs.
- **Ekspor & Impor Config Terenkripsi `.rr`**:
  - Enkripsi `AES-256-GCM` native Rust (`ring`) dengan *compile-time secret injection* via GitHub Actions (`RR_MASTER_SECRET` di `rustray-ssh/build.rs`).
  - Mendukung nama file kustom, **Kunci Server & Akun**, **Kunci Payload/Proxy/SNI**, **Tanggal Kedaluwarsa**, dan **Catatan HTML** (tautan dapat diklik langsung).
  - Field yang dikunci tidak pernah disimpan sebagai plaintext di `ProfileStore`, dan pada mode SSH didekripsi langsung di memori native Rust saat connect.
- **Tuning Gaming & Jaringan**:
  - Preset **Mode Game** siap pakai (MTU 1380, profil TUN `Throughput`, FakeIP & Sniffing mati, DNS kustom).
  - Pengaturan MTU (`1280–9000`), 6 profil kinerja TUN, Rute IPv6, Rebind Jaringan Otomatis, Ikat Jaringan Aktif, Sniffing, FakeIP, Server DNS, dan Split Tunneling per aplikasi (`Semua`, `Kecualikan`, `Hanya Terpilih`).
  - **Integrasi Shizuku** untuk kontrol Captive Portal otomatis saat VPN menyala/mati maupun secara manual.
- **Kemudahan Harian**:
  - Kartu profil bersih dengan garis aksen kiri untuk profil aktif serta tombol cepat **Edit**, **Bagikan/Ekspor**, dan **Hapus** (semua profil hasil impor kini bisa diedit).
  - **Widget Layar Beranda** (1 ketuk Hubungkan/Putuskan).
  - **Quick Settings Tiles (Panel Atas Android)**: Tile `RustRay` untuk on/off cepat dan tile `Ganti Profil` dengan dialog pop-up pilih profil + **Sambungkan Ulang** instan.
  - **Notifikasi Trafik Live** (update tiap 2 detik hanya saat layar menyala, bisa diatur di Pengaturan) lengkap dengan tombol **Sambungkan Ulang** dan **Putuskan**.
  - Pilihan bahasa aplikasi lengkap **English** (bawaan) dan **Bahasa Indonesia** di Pengaturan.

### Cara Build dari Source

Prasyarat: JDK 17+, Android SDK (compileSdk 35, minSdk 24), Android NDK, toolchain Rust (`aarch64-linux-android`, `armv7-linux-androideabi`), dan `cargo-ndk`.

```bash
# 1. Build library native SSH & enkripsi .rr (arm64-v8a & armeabi-v7a)
cd rustray-ssh
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
cd ..

# 2. Build APK release (ABI-split, R8 minified)
./gradlew assembleRelease
```

Untuk panduan setup kunci enkripsi `.rr` di GitHub Secrets (`RR_MASTER_SECRET`) agar tetap aman pada repositori publik, lihat [RR\_SECRET\_SETUP.md](https://www.google.com/search?q=RR_SECRET_SETUP.md).
