package com.michaeltchuang.walletsdk.core.solana.utils

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256

/** Program-derived address helpers (matches `PublicKey.findProgramAddressSync` in web3.js). */
object SolanaPda {
    const val ASSOCIATED_TOKEN_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

    private const val MAX_SEED_LENGTH = 32
    private val PDA_MARKER = "ProgramDerivedAddress".encodeToByteArray()

    data class ProgramAddress(
        val address: String,
        val bump: Int,
    )

    fun findProgramAddress(
        seeds: List<ByteArray>,
        programId: String,
    ): ProgramAddress {
        require(seeds.size < 16) { "Too many seeds" }
        seeds.forEach { require(it.size <= MAX_SEED_LENGTH) { "Seed exceeds $MAX_SEED_LENGTH bytes" } }
        val program = Base58.decode(programId)
        for (bump in 255 downTo 0) {
            val candidate = createProgramAddress(seeds + byteArrayOf(bump.toByte()), program)
            if (candidate != null) return ProgramAddress(Base58.encode(candidate), bump)
        }
        error("Unable to find a viable program address bump")
    }

    /** Associated token account for [owner] / [mint] (classic SPL Token program by default). */
    fun associatedTokenAddress(
        owner: String,
        mint: String,
        tokenProgramId: String = TOKEN_PROGRAM_ID,
    ): String =
        findProgramAddress(
            listOf(Base58.decode(owner), Base58.decode(tokenProgramId), Base58.decode(mint)),
            ASSOCIATED_TOKEN_PROGRAM_ID,
        ).address

    private fun createProgramAddress(
        seeds: List<ByteArray>,
        programId: ByteArray,
    ): ByteArray? {
        var buffer = ByteArray(0)
        seeds.forEach { buffer += it }
        val hash = sha256(buffer + programId + PDA_MARKER)
        return hash.takeUnless { Ed25519Curve.isOnCurve(it) }
    }
}

/**
 * Mirrors curve25519-dalek `CompressedEdwardsY::decompress().is_some()`, which is what the
 * Solana runtime uses to reject PDAs that lie on the curve.
 */
internal object Ed25519Curve {
    private val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger(19)
    private val D: BigInteger =
        (BigInteger(-121665) * modInverse(BigInteger(121666))).mod(P)
    private val LEGENDRE_EXP: BigInteger = (P - BigInteger.ONE) / BigInteger.TWO

    fun isOnCurve(compressed: ByteArray): Boolean {
        require(compressed.size == 32) { "Expected 32-byte point" }
        // Little-endian y with the sign bit cleared; dalek reduces non-canonical y mod p.
        val bigEndian = compressed.reversedArray()
        bigEndian[0] = (bigEndian[0].toInt() and 0x7F).toByte()
        val y = BigInteger.fromByteArray(bigEndian, Sign.POSITIVE).mod(P)
        val y2 = (y * y).mod(P)
        val u = (y2 - BigInteger.ONE).mod(P)
        val v = (D * y2 + BigInteger.ONE).mod(P)
        val x2 = (u * modInverse(v)).mod(P)
        // A point exists iff x^2 is a quadratic residue (or zero).
        return x2.isZero() || modPow(x2, LEGENDRE_EXP) == BigInteger.ONE
    }

    private fun modInverse(value: BigInteger): BigInteger = modPow(value.mod(P), P - BigInteger.TWO)

    private fun modPow(
        base: BigInteger,
        exponent: BigInteger,
    ): BigInteger {
        var result = BigInteger.ONE
        var b = base.mod(P)
        var e = exponent
        while (e > BigInteger.ZERO) {
            if (e.mod(BigInteger.TWO) == BigInteger.ONE) result = (result * b).mod(P)
            b = (b * b).mod(P)
            e = e shr 1
        }
        return result
    }
}
