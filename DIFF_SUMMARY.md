# RustRay Update Diff Summary (SSH IPv4-First + Payload Jabar/Jatim + Race Condition Fix)

Total **2 file diubah** (`+204 / -65` baris).

## Ringkasan

- **IPv4-First & Fallback di `rustray-ssh/src/transport.rs`**: `connect_protected_tcp()` kini mengumpulkan semua alamat hasil DNS, memprioritaskan IPv4 sebelum IPv6 (menghindari hang 12 detik pada rute IPv6 5G yang di-drop operator), fallback otomatis antar alamat (timeout 6 detik per alamat jika >1 hasil DNS), dan menyertakan target `host:port -> ip:port` pada pesan error TCP.
- **Perbaikan Parser Payload Jabar & Jatim di `rustray-ssh/src/transport.rs`**:
  - Langsung melanjutkan ke handshake SSH saat menerima `101 Switching Protocols` tanpa menahan menunggu respons kedua.
  - Melewati baris status dummy pasca-`[split]` (seperti `[split]HTTP/enhanced 200 Ok[crlf]`) agar tidak bocor ke stream SSH sebelum `SSH-2.0-`.
  - Jeda singkat `15ms` antar segmen `[split]` supaya paket TCP tidak digabung kernel.
  - Batas *drain* body HTTP dinaikkan ke `256 KB` (`MAX_SCAN_BYTES = 128 KB`), *best-effort* pada timeout, serta tidak men-drain body untuk respons `HEAD` / `204` / `304` atau `200` tunnel handoff.
- **Log Proxy & Proteksi Race Condition di `app/src/main/java/io/rustray/app/AppVpnService.kt`**:
  - Menampilkan `via <proxy>` pada log `Starting SSH` (tetap disembunyikan jika profil `.rr` mengunci payload/proxy).
  - Menambahkan `connectGeneration` (`AtomicLong`) agar thread SSH lama yang sudah di-Stop tidak dapat memanggil `fail()` dan memutus percobaan koneksi baru.

## Daftar File

- `rustray-ssh/src/transport.rs` (`+171 / -61`)
- `app/src/main/java/io/rustray/app/AppVpnService.kt` (`+33 / -4`)
