# `.rr` Encryption Secret Setup Guide (`RR_MASTER_SECRET`)

[English](RR_SECRET_SETUP.en.md) | [Bahasa Indonesia](RR_SECRET_SETUP.md)

Because the RustRay repository is open source on GitHub, the production key for `.rr` encryption (`AES-256-GCM`) **is not stored in the source code**. Instead, `rustray-ssh/build.rs` injects the key at compile time through a **GitHub Actions secret**.

## 1. Generate a strong random key

Create a random string of at least 64 characters. Do not use anything guessable. On Linux, Termux, or Git Bash:

```bash
openssl rand -hex 32
```

Or with Python:

```bash
python3 -c "import secrets; print(secrets.token_hex(32))"
```

Store a copy of this key in a safe place, such as a password manager, as a backup.

## 2. Add the secret to the GitHub repository

1. Open the **RustRay** repository on GitHub.
2. Go to the **Settings** tab.
3. In the left menu, select **Secrets and variables** → **Actions**.
4. Click **New repository secret**.
5. Fill in the form:
   - **Name**: `RR_MASTER_SECRET`
   - **Secret**: paste the 64-character random string from Step 1.
6. Click **Add secret**.

## 3. How it works during a build

- **Official release build (GitHub Actions with `RR_MASTER_SECRET`)**:
  The workflow `.github/workflows/build.yml` passes `RR_MASTER_SECRET` to `rustray-ssh/build.rs`. `build.rs` folds and XOR-masks the secret into a temporary file `$OUT_DIR/rr_key_gen.rs` (never committed to Git), then compiles it into the stripped `librustray_ssh.so`.
- **Public clone / local build (without `RR_MASTER_SECRET`)**:
  Anyone cloning the public repository can still compile RustRay without errors because `build.rs` falls back to a development key. However, APKs built from a public clone **cannot open `.rr` files** created by your official release APK.

## 4. Important rules

- **Do not change `RR_MASTER_SECRET` after release**: changing the value produces a different AES-256-GCM key, so `.rr` files already shared with users will no longer open in newer versions.
- **Never commit the secret to Git**: keep it only in GitHub Actions Secrets, not in `.env`, `Cargo.toml`, or `build.rs`.
- **For a local build compatible with the official release**: set the environment variable before running `cargo ndk`:
  ```bash
  export RR_MASTER_SECRET="your_secret_key_here"
  cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 -o ../app/src/main/jniLibs build --release
  ```
