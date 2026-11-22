# RustRay Release Notes

[English](#english) | [Bahasa Indonesia](#bahasa-indonesia)

<a id="english"></a>

## English

This release introduces a **native Rust SSH Hybrid engine**, **encrypted `.rr` config sharing**, **Home Screen Widget**, **Quick Settings Tiles**, **live traffic notification**, **bilingual UI (EN/ID)**, and key UI/stability fixes.

### What's New

* **Native Rust SSH Hybrid Engine (`russh 0.65` + `ring`)**:

  * Full SSH tunnel + BadVPN UDPGW client running natively inside `librustray_ssh.so` over `xray-rust` TUN (zero Java GC pauses).

  * 7 HTTP Custom-style transport modes: `SSH Direct`, `SSH + Payload`, `SSH + Payload + Proxy`, `SSH + SSL / SNI`, `SSH + SSL + Payload`, `SSH + WebSocket`, and `SSH + SSL + WebSocket`.

  * Supports `[crlf]`, `[host_port]`, `[sni]`, `[netData]`, `[raw]`, `[auth]`, `[split]`, `[delay_split]`, and flexible Remote Proxy (`host:port` or `host:port@user:pass`).

  * **Parallel Connections (1–4)** to isolate gaming UDPGW traffic from background TCP traffic (auto-fallback on single-login servers).

  * **Enhanced SSH Mode** toggle for larger 256 KB TCP buffers, 8 MiB SSH window, and faster keepalive intervals (8s SSH / 5s UDPGW).

  * Live SSH/UDPGW diagnostics (`udpgw`, `probe=ok`, `txB`/`rxB`, `udp_assoc`, `last_dst`) displayed at the top of the **Logs** screen.

* **Encrypted `.rr` Config Export & Import**:

  * Native Rust `AES-256-GCM` encryption with compile-time secret injection via GitHub Actions (`RR_MASTER_SECRET`).

  * Configurable filename, **Lock Server & Account**, **Lock Payload/Proxy/SNI**, **Expiry Date**, and clickable **HTML Notes**.

  * Locked credentials/payloads are never stored as plaintext in app preferences and are decrypted directly in native Rust memory for SSH profiles.

  * Direct sharing via Android Share Sheet and 1-tap import from File Manager/Telegram or the in-app **Import** button.

* **Home Screen Widget & Quick Settings Tiles**:

  * Compact `3x1` dark-flat Home Screen Widget for 1-tap Connect/Disconnect.

  * Two Quick Settings Tiles: **`RustRay`** (quick VPN toggle) and **`Switch Profile`** (instant pop-up profile switcher + **Reconnect** without leaving your current app/game).

* **Live Traffic Notification + Reconnect**:

  * Real-time upload/download speed and total usage in the VPN notification (updates every 2s only while the screen is on), controllable via **Notification Live Traffic** toggle in Settings.

  * Added **Reconnect** action button directly in the notification.

* **Redesigned Profile Cards & Language Switcher**:

  * Replaced checkmark icons with a clean left accent bar and quick **Edit**, **Share**, and **Delete** icons on every card.

  * Full **English** (default) and **Bahasa Indonesia** localization with an in-app **Language** dropdown in Settings.

### Bug Fixes & Improvements

* Fixed active profile highlight desync when importing a new link while VPN is already connected/connecting.

* Enabled editing for all imported VLESS/VMess/Trojan profiles (automatically hydrates fields from imported JSON and applies saved changes).

* Removed the blocking "No TLS" confirmation pop-up before connecting to plaintext V2Ray profiles.

* Fixed UDPGW per-destination `conid` collisions, FakeIP DNS UDP loops, and switched to lock-free `russh` `Channel::split()` I/O for stable online gaming.

### APK Downloads

* `app-arm64-v8a-release.apk` — Recommended for most modern Android phones (64-bit ARM).

* `app-armeabi-v7a-release.apk` — For older 32-bit ARM devices.

<a id="bahasa-indonesia"></a>

## Bahasa Indonesia

Rilis ini menghadirkan **engine SSH Hybrid native Rust**, **fitur ekspor/impor config terenkripsi `.rr`**, **Widget Layar Beranda**, **Quick Settings Tiles**, **notifikasi trafik live**, **pengaturan bahasa (EN/ID)**, serta berbagai perbaikan stabilitas dan UI.

### Fitur Baru

* **Engine SSH Hybrid Native Rust (`russh 0.65` + `ring`)**:

  * Tunnel SSH + klien BadVPN UDPGW berjalan native di dalam `librustray_ssh.so` di atas TUN `xray-rust` tanpa jeda GC Java.

  * 7 mode transport ala HTTP Custom: `SSH Direct`, `SSH + Payload`, `SSH + Payload + Proxy`, `SSH + SSL / SNI`, `SSH + SSL + Payload`, `SSH + WebSocket`, dan `SSH + SSL + WebSocket`.

  * Mendukung tag `[crlf]`, `[host_port]`, `[sni]`, `[netData]`, `[raw]`, `[auth]`, `[split]`, `[delay_split]`, serta Remote Proxy (`host:port` atau `host:port@user:pass`).

  * **Koneksi Paralel (1–4)** untuk memisahkan trafik UDPGW game dari trafik TCP background (otomatis fallback di server 1 login).

  * Toggle **Mode SSH Enhanced** untuk buffer TCP 256 KB, SSH window 8 MiB, serta keepalive lebih cepat (8 detik SSH / 5 detik UDPGW).

  * Diagnostik live SSH/UDPGW (`udpgw`, `probe=ok`, `txB`/`rxB`, `udp_assoc`, `last_dst`) di bagian atas layar **Logs**.

* **Ekspor & Impor Config Terenkripsi `.rr`**:

  * Enkripsi `AES-256-GCM` native Rust dengan *compile-time secret injection* via GitHub Actions (`RR_MASTER_SECRET`).

  * Mendukung nama file kustom, **Kunci Server & Akun**, **Kunci Payload/Proxy/SNI**, **Tanggal Kedaluwarsa**, dan **Catatan HTML** (tautan bisa langsung diklik).

  * Field terkunci tidak pernah disimpan sebagai plaintext di penyimpanan aplikasi dan didekripsi langsung di memori native Rust untuk profil SSH.

  * Bisa langsung dibagikan lewat Android Share Sheet dan otomatis terimpor saat file `.rr` diketuk dari File Manager/Telegram atau tombol **Impor**.

* **Widget Layar Beranda & Quick Settings Tiles**:

  * Widget dark-flat ringkas `3x1` untuk Hubungkan/Putuskan VPN dalam 1 ketuk.

  * Dua Quick Settings Tile di panel atas Android: **`RustRay`** (toggle VPN cepat) dan **`Ganti Profil`** (pop-up daftar profil + **Sambungkan Ulang** instan tanpa keluar dari game/aplikasi).

* **Notifikasi Trafik Live + Sambungkan Ulang**:

  * Menampilkan kecepatan unggah/unduh dan total pemakaian data secara langsung di notifikasi VPN (diperbarui tiap 2 detik hanya saat layar menyala), dapat diatur melalui toggle **Trafik Live di Notifikasi** di Pengaturan.

  * Tambahan tombol aksi **Sambungkan Ulang** langsung di notifikasi.

* **Desain Kartu Profil Baru & Pilihan Bahasa**:

  * Mengganti ikon centang dengan garis aksen vertikal di sisi kiri serta ikon cepat **Edit**, **Bagikan**, dan **Hapus** di setiap kartu.

  * Dukungan penuh **English** (bawaan) dan **Bahasa Indonesia** lewat menu **Bahasa** di Pengaturan.

### Perbaikan Bug & Peningkatan

* Memperbaiki sinkronisasi pilihan profil saat mengimpor tautan baru ketika VPN sedang terhubung.

* Mengaktifkan tombol edit untuk seluruh profil hasil impor VLESS/VMess/Trojan (otomatis mengekstrak seluruh field konfigurasi impor).

* Menghapus dialog peringatan "No TLS" sebelum menghubungkan profil V2Ray tanpa TLS.

* Memperbaiki tabrakan `conid` per tujuan di UDPGW, loop DNS FakeIP pada UDP, serta beralih ke `Channel::split()` lock-free `russh` agar game online berjalan stabil.

### Unduhan APK

* `app-arm64-v8a-release.apk` — Disarankan untuk mayoritas HP Android modern (ARM 64-bit).

* `app-armeabi-v7a-release.apk` — Untuk perangkat ARM 32-bit lama.