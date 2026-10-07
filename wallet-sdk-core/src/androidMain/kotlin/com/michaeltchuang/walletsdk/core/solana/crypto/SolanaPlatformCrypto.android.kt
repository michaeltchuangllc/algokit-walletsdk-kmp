package com.michaeltchuang.walletsdk.core.solana.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

internal actual fun solanaEd25519PublicKey(seed: ByteArray): ByteArray {
    require(seed.size == 32) { "Ed25519 seed must be 32 bytes, was ${seed.size}" }
    return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
}

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
