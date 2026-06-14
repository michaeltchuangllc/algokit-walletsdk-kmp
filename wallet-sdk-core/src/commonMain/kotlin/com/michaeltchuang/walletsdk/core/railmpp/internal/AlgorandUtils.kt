package com.michaeltchuang.walletsdk.core.railmpp.internal

private const val ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH = 32
private const val ALGORAND_ADDRESS_CHECKSUM_LENGTH = 4

/** Decodes an Algorand base32-encoded address to its 32-byte public key. */
internal fun decodeAlgorandAddressPublicKey(address: String): ByteArray {
    val decoded = decodeBase32(address)
    require(decoded.size >= ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH + ALGORAND_ADDRESS_CHECKSUM_LENGTH) {
        "Invalid Algorand address length"
    }
    return decoded.copyOfRange(0, ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH)
}

internal fun decodeBase32(value: String): ByteArray {
    var buffer = 0
    var bitsLeft = 0
    val bytes = mutableListOf<Byte>()
    value.trim().trimEnd('=').uppercase().forEach { char ->
        val charValue =
            when (char) {
                in 'A'..'Z' -> char - 'A'
                in '2'..'7' -> char - '2' + 26
                else -> error("Invalid base32 character: $char")
            }
        buffer = (buffer shl 5) or charValue
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            bytes.add(((buffer shr bitsLeft) and 0xFF).toByte())
        }
    }
    return bytes.toByteArray()
}

/** Big-endian uint64 encoding of [value]. */
internal fun encodeUint64(value: Long): ByteArray {
    var v = value
    val bytes = ByteArray(8)
    for (i in 7 downTo 0) {
        bytes[i] = (v and 0xFF).toByte()
        v = v ushr 8
    }
    return bytes
}

/** Encodes a byte array to Algorand's base32 (no padding). */
internal fun encodeBase32(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val sb = StringBuilder()
    var buffer = 0
    var bitsInBuffer = 0
    for (b in bytes) {
        buffer = (buffer shl 8) or (b.toInt() and 0xFF)
        bitsInBuffer += 8
        while (bitsInBuffer >= 5) {
            bitsInBuffer -= 5
            sb.append(alphabet[(buffer ushr bitsInBuffer) and 0x1F])
        }
    }
    if (bitsInBuffer > 0) {
        sb.append(alphabet[(buffer shl (5 - bitsInBuffer)) and 0x1F])
    }
    return sb.toString()
}

/** Encodes a 32-byte public key to an Algorand base32 address (with checksum). */
internal fun encodeAlgorandAddress(publicKey: ByteArray): String {
    require(publicKey.size == ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH) {
        "Public key must be $ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH bytes"
    }
    // Algorand address checksum is the last 4 bytes of SHA512/256(publicKey), not SHA256.
    val checksum = sha512_256(publicKey).takeLast(ALGORAND_ADDRESS_CHECKSUM_LENGTH).toByteArray()
    return encodeBase32(publicKey + checksum)
}

/**
 * Derives the Algorand application address from an app ID.
 * Formula: sha512_256("appID" || encode_uint64(appId))
 */
internal fun appIdToAlgorandAddress(appId: Long): String {
    val hash = sha512_256("appID".encodeToByteArray() + encodeUint64(appId))
    return encodeAlgorandAddress(hash)
}
