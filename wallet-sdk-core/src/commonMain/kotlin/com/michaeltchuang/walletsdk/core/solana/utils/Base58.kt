package com.michaeltchuang.walletsdk.core.solana.utils

/** Bitcoin-alphabet Base58 codec used for Solana addresses, public keys and signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val zeros = input.indexOfFirst { it.toInt() != 0 }.let { if (it == -1) input.size else it }
        val digits = input.copyOf()
        val encoded = CharArray(input.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < digits.size) {
            encoded[--outputStart] = ALPHABET[divmod(digits, inputStart, 256, 58)]
            if (digits[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < encoded.size && encoded[outputStart] == ALPHABET[0]) outputStart++
        repeat(zeros) { encoded[--outputStart] = ALPHABET[0] }
        return encoded.concatToString(outputStart, encoded.size)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 =
            ByteArray(input.length) { i ->
                val c = input[i]
                val digit = if (c.code < 128) INDEXES[c.code] else -1
                require(digit >= 0) { "Invalid Base58 character '$c' at position $i" }
                digit.toByte()
            }
        val zeros = input58.indexOfFirst { it.toInt() != 0 }.let { if (it == -1) input58.size else it }
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256).toByte()
            if (input58[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) outputStart++
        return decoded.copyOfRange(outputStart - zeros, decoded.size)
    }

    /** Returns true when [address] decodes to a 32-byte Solana public key. */
    fun isValidSolanaAddress(address: String): Boolean = runCatching { decode(address).size == 32 }.getOrDefault(false)

    private fun divmod(
        number: ByteArray,
        firstDigit: Int,
        base: Int,
        divisor: Int,
    ): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }
}
