package com.michaeltchuang.walletsdk.core.railmpp.internal

private const val SHA256_BLOCK_SIZE = 64

/** HMAC-SHA256 built on the platform [sha256] hash (no JVM/javax dependency). */
internal fun hmacSha256(
    key: ByteArray,
    message: ByteArray,
): ByteArray {
    val normalizedKey = if (key.size > SHA256_BLOCK_SIZE) sha256(key) else key
    val block = ByteArray(SHA256_BLOCK_SIZE)
    normalizedKey.copyInto(block)

    val inner = ByteArray(SHA256_BLOCK_SIZE) { (block[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(SHA256_BLOCK_SIZE) { (block[it].toInt() xor 0x5c).toByte() }

    return sha256(outer + sha256(inner + message))
}

/** Length-and-content constant-time comparison to avoid timing leaks on secrets. */
internal fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean {
    if (a.size != b.size) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}
