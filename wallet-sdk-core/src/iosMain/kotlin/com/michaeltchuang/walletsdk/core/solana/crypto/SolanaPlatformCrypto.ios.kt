package com.michaeltchuang.walletsdk.core.solana.crypto

import AlgorandIosSdk.spmAlgoApiBridge
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalForeignApi::class)
private val bridge by lazy { spmAlgoApiBridge() }

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun solanaEd25519PublicKey(seed: ByteArray): ByteArray {
    require(seed.size == 32) { "Ed25519 seed must be 32 bytes, was ${seed.size}" }
    val result = bridge.solanaEd25519PublicKeyWithSeedBase64(Base64.encode(seed))
    check(result.isNotEmpty()) { "iOS: solanaEd25519PublicKey failed" }
    return Base64.decode(result)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "iOS: SecRandomCopyBytes failed with status $status" }
    return bytes
}
