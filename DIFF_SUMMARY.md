# RustRay Update Diff Summary (V2Ray HTTPUpgrade + Custom Payload)

Total **13 file diubah/ditambah** (`+484 / -58` baris) — **Version bump `1.1.0` (`versionCode = 2`) → `1.2.7` (`versionCode = 3`)**.

## Ringkasan

- **Native TCP Payload Bridge (`rustray-ssh/src/payload_bridge.rs`, `lib.rs`, `transport.rs`)**:
  - Menambahkan bridge lokal `127.0.0.1:<port>` (`nativeStartPayloadBridge`) untuk membungkus koneksi outbound Xray (VMess/VLESS/Trojan HTTPUpgrade) dengan payload HTTP kustom via proxy/bug.
  - Mendukung tag `[path]`, `[host]` (menggunakan `Host` header profil), auto-inject `profile.path` (mis. `/upvmess`) ke baris request `Upgrade: websocket` jika payload masih memakai `/`, serta menyaring baris status dummy pasca-`[split]` seperti `[split]HTTP/69[crlf][crlf]`.
- **Integrasi Xray & `.rr` (`Profile.kt`, `RrConfigCodec.kt`, `RustSshBridge.kt`, `ConfigBuilder.kt`, `AppVpnService.kt`)**:
  - Menambahkan field `v2rayCustomPayload` (ikut terkunci otomatis saat `Lock Payload` pada ekspor `.rr`).
  - Saat aktif pada jaringan `HTTPUpgrade`, `AppVpnService` menyalakan bridge lokal dan `ConfigBuilder.configForV2rayPayload()` mengarahkan outbound Xray ke `127.0.0.1:<port>` (mode raw `tcp`).
- **UI & Lokalisasi (`EditProfileActivity.kt`, `activity_edit_profile.xml`, `strings.xml`, `values-in/strings.xml`)**:
  - Menambahkan toggle `MaterialSwitch` **Use custom payload / Gunakan payload kustom** pada profil VMess/VLESS/Trojan berjaringan `HTTPUpgrade`, menampilkan kolom Proxy dan Payload tepat di bawahnya saat aktif.

## Daftar File

- `app/build.gradle.kts` (`+2 / -2`) — bump `versionCode = 3`, `versionName = "1.2.7"`
- `app/src/main/java/io/rustray/app/AppVpnService.kt` (`+56 / -2`)
- `app/src/main/java/io/rustray/app/ConfigBuilder.kt` (`+22 / -0`)
- `app/src/main/java/io/rustray/app/EditProfileActivity.kt` (`+18 / -2`)
- `app/src/main/java/io/rustray/app/Profile.kt` (`+3 / -0`)
- `app/src/main/java/io/rustray/app/RrConfigCodec.kt` (`+2 / -0`)
- `app/src/main/java/io/rustray/app/RustSshBridge.kt` (`+25 / -0`)
- `app/src/main/res/layout/activity_edit_profile.xml` (`+49 / -32`)
- `app/src/main/res/values/strings.xml` (`+2 / -0`)
- `app/src/main/res/values-in/strings.xml` (`+2 / -0`)
- `rustray-ssh/src/lib.rs` (`+134 / -7`)
- `rustray-ssh/src/payload_bridge.rs` (`+102 / -0`) (baru)
- `rustray-ssh/src/transport.rs` (`+67 / -13`)
