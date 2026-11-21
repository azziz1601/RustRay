use std::env;
use std::fs;
use std::path::Path;

fn main() {
    println!("cargo:rerun-if-env-changed=RR_MASTER_SECRET");

    let secret = env::var("RR_MASTER_SECRET")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "rustray-open-source-dev-fallback-key".to_string());

    // Fold arbitrary-length secret deterministically into 32 bytes using multi-round mixing.
    let mut folded = [0x6Au8; 32];
    for (idx, b) in secret.as_bytes().iter().enumerate() {
        let slot = idx % 32;
        folded[slot] = folded[slot]
            .wrapping_add(*b)
            .rotate_left(((idx % 7) + 1) as u32)
            ^ ((idx as u8).wrapping_mul(0x9E));
    }
    for round in 0..4u8 {
        for i in 0..32usize {
            let prev = folded[(i + 31) % 32];
            let next = folded[(i + 1) % 32];
            folded[i] = folded[i]
                .wrapping_add(prev.rotate_left(3))
                ^ next.wrapping_mul(0x3D)
                ^ round.wrapping_mul(0x5B);
        }
    }

    // Generate deterministic per-secret mask pad so raw folded bytes never appear directly in binary.
    let mut pad = [0u8; 32];
    let mut state: u64 = 0x9E37_79B9_7F4A_7C15 ^ (secret.len() as u64);
    for b in secret.as_bytes() {
        state = state.wrapping_mul(0xBF58_476D_1CE4_E5B9).wrapping_add(*b as u64);
        state ^= state >> 27;
    }
    for slot in &mut pad {
        state = state.wrapping_mul(0x94D0_49BB_1331_11EB).wrapping_add(1);
        state ^= state >> 31;
        *slot = (state & 0xFF) as u8;
    }

    let mut masked = [0u8; 32];
    for i in 0..32 {
        masked[i] = folded[i] ^ pad[i];
    }

    let out_dir = env::var("OUT_DIR").expect("OUT_DIR not set");
    let dest = Path::new(&out_dir).join("rr_key_gen.rs");
    let code = format!(
        "const RR_MASKED_MATERIAL: [u8; 32] = {:?};\nconst RR_MASK_PAD: [u8; 32] = {:?};\n",
        masked, pad
    );
    fs::write(dest, code).expect("Failed to write rr_key_gen.rs");
}
