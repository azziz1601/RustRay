# RustRay Update Diff Summary (VMess/Trojan/VLESS Standalone Parser + Non-TLS Support)

Total **4 file diubah/ditambah** (`+204 / -68` baris).

## Ringkasan

- **Parser Mandiri `ShareLinkParser.kt`**: Mengganti `XrayProfileImporter` untuk impor `vmess://`, `vless://`, dan `trojan://` sehingga UUID/password, SNI, host, path, gRPC `serviceName`, alterId, dan security selalu terisi penuh (tidak lagi kosong saat diedit) serta mendukung penuh config Non-TLS (`tls: none` / `security=none`).
- **Perluasan `PlaintextVlessCompat.kt`**: `rewriteConfig()` kini juga menerapkan alias domain single-label + `dns.hosts` untuk outbound **VMess** dan **Trojan** tanpa TLS agar diterima oleh core `xray-rust`.
- **Pembaruan `MainActivity.kt` & `EditProfileActivity.kt`**: `previewOf()` dan `buildImportedProfile()` memakai `ShareLinkParser.parse()`.

## Daftar File

- `app/src/main/java/io/rustray/app/EditProfileActivity.kt` (`+1 / -1`)
- `app/src/main/java/io/rustray/app/MainActivity.kt` (`+4 / -63`)
- `app/src/main/java/io/rustray/app/PlaintextVlessCompat.kt` (`+11 / -4`)
- `app/src/main/java/io/rustray/app/ShareLinkParser.kt` (`+188 / -0`)