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
        val charValue = when (char) {
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
    for (i in 7 downTo 0) { bytes[i] = (v and 0xFF).toByte(); v = v ushr 8 }
    return bytes
}
