# Panduan Setup Kunci Rahasia Enkripsi `.rr` (`RR_MASTER_SECRET`)

Karena repositori RustRay bersifat open-source di GitHub, kunci produksi untuk enkripsi file `.rr` (`AES-256-GCM`) **tidak disimpan di dalam source code**. Sebagai gantinya, `rustray-ssh/build.rs` menyuntikkan kunci tersebut pada saat kompilasi (*compile-time key injection*) melalui **GitHub Actions Secret**.

## 1. Membuat Kunci Acak yang Kuat

Buat string acak minimal 64 karakter (jangan memakai kata yang mudah ditebak). Kamu bisa membuatnya di terminal Linux/Termux/Git Bash:

```bash
openssl rand -hex 32
```

Atau memakai Python:

```bash
python3 -c "import secrets; print(secrets.token_hex(32))"
```

Simpan salinan kunci ini di tempat aman (misalnya password manager) sebagai backup.

## 2. Memasang Secret di GitHub Repository

1. Buka repositori **RustRay** di GitHub.
2. Masuk ke tab **Settings**.
3. Di menu sebelah kiri, pilih **Secrets and variables** -> **Actions**.
4. Klik tombol **New repository secret**.
5. Isi formulir berikut:
   - **Name**: `RR_MASTER_SECRET`
   - **Secret**: tempelkan hasil string acak 64 karakter dari Langkah 1.
6. Klik **Add secret**.

## 3. Cara Kerja Saat Build

- **Build Rilis Resmi (GitHub Actions dengan `RR_MASTER_SECRET`)**:
  Workflow `.github/workflows/build.yml` otomatis meneruskan `RR_MASTER_SECRET` ke `rustray-ssh/build.rs`. Skrip `build.rs` melipat dan meng-XOR-mask secret tersebut ke file sementara `$OUT_DIR/rr_key_gen.rs` (tidak pernah masuk Git), lalu mengompilasinya ke dalam `librustray_ssh.so` yang sudah di-strip.
- **Build Publik / Clone Lokal (Tanpa `RR_MASTER_SECRET`)**:
  Siapa pun yang meng-clone repo publik tetap bisa mengompilasi RustRay tanpa error karena `build.rs` otomatis memakai *dev fallback key*. Namun, APK hasil build clone publik **tidak akan bisa membuka file `.rr`** yang dibuat oleh APK rilis resmimu.

## 4. Aturan Penting

- **Jangan ubah `RR_MASTER_SECRET` setelah rilis ke publik**: Mengubah nilai secret akan menghasilkan kunci AES-256-GCM yang berbeda, sehingga file `.rr` yang sudah dibagikan ke pengguna sebelumnya tidak akan bisa dibuka di versi baru.
- **Jangan pernah commit secret ke Git**: Pastikan kunci hanya disimpan di GitHub Actions Secrets, bukan di `.env`, `Cargo.toml`, atau `build.rs`.
- **Untuk build lokal yang kompatibel dengan rilis resmi**: Set environment variable sebelum menjalankan `cargo ndk`:
  ```bash
  export RR_MASTER_SECRET="kunci_rahasia_kamu_di_sini"
  cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
  ```
