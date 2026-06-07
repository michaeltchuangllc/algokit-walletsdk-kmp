@file:Suppress("FunctionName")

package com.michaeltchuang.walletsdk.core.railmpp.internal

import AlgorandIosSdk.spmAlgoApiBridge
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalForeignApi::class)
private val bridge by lazy { spmAlgoApiBridge() }

/**
 * Computes SHA-256 via the Swift bridge (uses CommonCrypto CC_SHA256).
 * Swift: sha256WithDataBase64(_ dataBase64: String) → ObjC: sha256WithDataBase64:
 * Kotlin/Native binding: sha256WithDataBase64(dataBase64: String): String
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val inputB64 = Base64.encode(bytes)
    val digestB64 = bridge.sha256WithDataBase64(inputB64)
    if (digestB64.isEmpty()) error("iOS: sha256 returned empty")
    return Base64.decode(digestB64)
}

/**
 * Computes SHA-512/256 via the Swift bridge (uses CommonCrypto CC_SHA512_256).
 * Swift: sha512256WithDataBase64(_ dataBase64: String) → ObjC: sha512256WithDataBase64:
 * Kotlin/Native binding: sha512256WithDataBase64(dataBase64: String): String
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun sha512_256(bytes: ByteArray): ByteArray {
    val inputB64 = Base64.encode(bytes)
    val digestB64 = bridge.sha512256WithDataBase64(inputB64)
    if (digestB64.isEmpty()) error("iOS: sha512_256 returned empty")
    return Base64.decode(digestB64)
}

/**
 * Signs [message] with an Ed25519 key derived from [secretKey] via the Swift bridge.
 *
 * Supports:
 * - 64-byte Algo25 format (first 32 bytes = seed)
 * - 32-byte raw seed
 *
 * Swift: signEd25519WithSeed(seedBase64:messageBase64:)
 * ObjC selector: signEd25519WithSeed:messageBase64:
 * Kotlin/Native: signEd25519WithSeedWithSeedBase64(seedBase64:messageBase64:)
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun signEd25519(secretKey: ByteArray, message: ByteArray): ByteArray? {
    val seedBytes = when (secretKey.size) {
        64 -> secretKey.copyOfRange(0, 32)
        32 -> secretKey
        else -> return null
    }
    val seedBase64 = Base64.encode(seedBytes)
    val messageBase64 = Base64.encode(message)
    val sigBase64 = bridge.signEd25519WithSeedWithSeedBase64(
        seedBase64 = seedBase64,
        messageBase64 = messageBase64,
    )
    if (sigBase64.isEmpty()) return null
    return runCatching { Base64.decode(sigBase64) }.getOrNull()
}

/**
 * Verifies an Ed25519 [signature] over [message] using [publicKey] via the Swift bridge.
 *
 * Swift: verifyEd25519Signature(publicKeyBase64:messageBase64:signatureBase64:)
 * ObjC selector: verifyEd25519Signature:messageBase64:signatureBase64:
 * Kotlin/Native: verifyEd25519SignatureWithPublicKeyBase64(publicKeyBase64:messageBase64:signatureBase64:)
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != 32 || signature.size != 64) return false
    val pkBase64 = Base64.encode(publicKey)
    val msgBase64 = Base64.encode(message)
    val sigBase64 = Base64.encode(signature)
    return bridge.verifyEd25519SignatureWithPublicKeyBase64(
        publicKeyBase64 = pkBase64,
        messageBase64 = msgBase64,
        signatureBase64 = sigBase64,
    )
}
