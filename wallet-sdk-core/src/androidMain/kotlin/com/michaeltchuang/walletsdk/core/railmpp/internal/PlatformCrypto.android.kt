package com.michaeltchuang.walletsdk.core.railmpp.internal

import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun sha512_256(bytes: ByteArray): ByteArray {
    BouncyCastleProviderSetup.ensure()
    val digest = SHA512tDigest(256)
    val output = ByteArray(32)
    digest.update(bytes, 0, bytes.size)
    digest.doFinal(output, 0)
    return output
}

internal actual fun signEd25519(
    secretKey: ByteArray,
    message: ByteArray,
): ByteArray? =
    runCatching {
        BouncyCastleProviderSetup.ensure()
        val key =
            when (secretKey.size) {
                32 -> secretKey
                64 -> secretKey.copyOfRange(0, 32)
                else -> error("Unsupported secret key size=${secretKey.size}")
            }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(key, 0))
        signer.update(message, 0, message.size)
        signer.generateSignature()
    }.getOrNull()

internal actual fun verifyEd25519(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean =
    runCatching {
        BouncyCastleProviderSetup.ensure()
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        verifier.verifySignature(signature)
    }.getOrDefault(false)
