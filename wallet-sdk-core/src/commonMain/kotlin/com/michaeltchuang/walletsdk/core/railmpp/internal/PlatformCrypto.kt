package com.michaeltchuang.walletsdk.core.railmpp.internal

internal expect fun sha256(bytes: ByteArray): ByteArray

internal expect fun sha512_256(bytes: ByteArray): ByteArray

/** Signs [message] with Ed25519 using the 32-byte [secretKey]; returns the 64-byte signature. */
internal expect fun signEd25519(
    secretKey: ByteArray,
    message: ByteArray,
): ByteArray?

/** Verifies an Ed25519 [signature] over [message] using the 32-byte [publicKey]. */
internal expect fun verifyEd25519(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean
