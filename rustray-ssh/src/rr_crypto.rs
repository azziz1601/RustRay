use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM, NONCE_LEN};
use ring::digest::{Context, SHA256};
use ring::rand::{SecureRandom, SystemRandom};

const MAGIC: &[u8; 4] = b"RR01";
const AAD_BYTES: &[u8] = b"RR01-RUSTRAY-CONFIG-V1";

include!(concat!(env!("OUT_DIR"), "/rr_key_gen.rs"));

fn derive_key() -> Result<LessSafeKey, String> {
    let mut ctx = Context::new(&SHA256);
    ctx.update(AAD_BYTES);
    let mut material = [0u8; 32];
    for i in 0..32 {
        material[i] = RR_MASKED_MATERIAL[i] ^ RR_MASK_PAD[i];
    }
    ctx.update(&material);
    // Wipe temporary stack copy immediately after hashing.
    for b in &mut material {
        *b = 0;
    }
    let digest = ctx.finish();
    let unbound = UnboundKey::new(&AES_256_GCM, digest.as_ref())
        .map_err(|_| "Failed to initialize AES-256-GCM key".to_string())?;
    Ok(LessSafeKey::new(unbound))
}

pub fn encrypt_rr(plaintext: &[u8]) -> Result<Vec<u8>, String> {
    let key = derive_key()?;
    let rng = SystemRandom::new();
    let mut nonce_bytes = [0u8; NONCE_LEN];
    rng.fill(&mut nonce_bytes)
        .map_err(|_| "Failed to generate random nonce".to_string())?;

    let nonce = Nonce::assume_unique_for_key(nonce_bytes);
    let mut in_out = plaintext.to_vec();
    key.seal_in_place_append_tag(nonce, Aad::from(AAD_BYTES), &mut in_out)
        .map_err(|_| "AES-256-GCM encryption failed".to_string())?;

    let mut out = Vec::with_capacity(MAGIC.len() + NONCE_LEN + in_out.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&in_out);
    Ok(out)
}

pub fn decrypt_rr(blob: &[u8]) -> Result<Vec<u8>, String> {
    if blob.len() < MAGIC.len() + NONCE_LEN + 16 {
        return Err("Invalid .rr file: payload too short".to_string());
    }
    if &blob[..4] != MAGIC {
        return Err("Invalid .rr file: unsupported header".to_string());
    }
    let key = derive_key()?;
    let mut nonce_bytes = [0u8; NONCE_LEN];
    nonce_bytes.copy_from_slice(&blob[4..4 + NONCE_LEN]);
    let nonce = Nonce::assume_unique_for_key(nonce_bytes);

    let mut in_out = blob[4 + NONCE_LEN..].to_vec();
    let plain = key
        .open_in_place(nonce, Aad::from(AAD_BYTES), &mut in_out)
        .map_err(|_| "Invalid or tampered .rr config file".to_string())?;
    Ok(plain.to_vec())
}
