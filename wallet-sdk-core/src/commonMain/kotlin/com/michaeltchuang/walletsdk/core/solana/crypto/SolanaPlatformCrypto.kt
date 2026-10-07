package com.michaeltchuang.walletsdk.core.solana.crypto

/** Derives the 32-byte Ed25519 public key from a 32-byte private seed. */
internal expect fun solanaEd25519PublicKey(seed: ByteArray): ByteArray

/** Cryptographically secure random bytes. */
internal expect fun secureRandomBytes(size: Int): ByteArray
