package com.michaeltchuang.walletsdk.core.railmpp.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA512_256
import platform.CoreCrypto.CC_SHA512_256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
    bytes.usePinned { src -> digest.usePinned { dst -> CC_SHA256(src.addressOf(0), bytes.size.toUInt(), dst.addressOf(0)) } }
    return digest
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha512_256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA512_256_DIGEST_LENGTH.toInt())
    bytes.usePinned { src -> digest.usePinned { dst -> CC_SHA512_256(src.addressOf(0), bytes.size.toUInt(), dst.addressOf(0)) } }
    return digest
}

internal actual fun signEd25519(secretKey: ByteArray, message: ByteArray): ByteArray? =
    TODO("iOS: Ed25519 signing not yet implemented")

internal actual fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
    TODO("iOS: Ed25519 verification not yet implemented")
