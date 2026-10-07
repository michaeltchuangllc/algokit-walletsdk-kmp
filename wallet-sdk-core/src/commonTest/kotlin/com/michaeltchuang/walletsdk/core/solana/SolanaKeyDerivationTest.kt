package com.michaeltchuang.walletsdk.core.solana

import com.michaeltchuang.walletsdk.core.solana.crypto.SolanaKeyDerivation
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SolanaKeyDerivationTest {

    @Test
    fun base58RoundTripsSystemProgramId() {
        val decoded = Base58.decode(SolanaTransactionBuilder.SYSTEM_PROGRAM_ID)
        assertContentEquals(ByteArray(32), decoded)
        assertEquals(SolanaTransactionBuilder.SYSTEM_PROGRAM_ID, Base58.encode(decoded))
    }

    @Test
    fun base58RoundTripsTokenProgramId() {
        val decoded = Base58.decode(SolanaTransactionBuilder.TOKEN_PROGRAM_ID)
        assertEquals(32, decoded.size)
        assertEquals(SolanaTransactionBuilder.TOKEN_PROGRAM_ID, Base58.encode(decoded))
    }

    @Test
    fun keypairFromSeedMatchesRfc8032Vector() {
        // RFC 8032 §7.1, TEST 1
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val keypair = SolanaKeyDerivation.keypairFromSeed(seed)
        assertContentEquals(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), keypair.publicKey)
        assertEquals(Base58.encode(keypair.publicKey), keypair.address)
    }

    @Test
    fun generatedKeypairsAreUniqueAndValid() {
        val a = SolanaKeyDerivation.generateEphemeralKeypair()
        val b = SolanaKeyDerivation.generateEphemeralKeypair()
        assertEquals(32, a.privateSeed.size)
        assertEquals(32, Base58.decode(a.address).size)
        assertNotEquals(a.address, b.address)
    }

    @Test
    fun invalidSeedLengthIsRejected() {
        assertFailsWith<IllegalArgumentException> { SolanaKeyDerivation.keypairFromSeed(ByteArray(16)) }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun systemTransferMessageHasExpectedLayout() {
        val from = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        val to = SolanaTransactionBuilder.TOKEN_PROGRAM_ID // any valid 32-byte key
        val blockhash = SolanaTransactionBuilder.SYSTEM_PROGRAM_ID
        val message =
            SolanaTransactionBuilder.compileMessage(
                feePayer = from,
                recentBlockhash = blockhash,
                instructions = listOf(SolanaTransactionBuilder.systemTransfer(from, to, 1_000L)),
            )
        // header(3) + shortvec(1) + 3 keys(96) + blockhash(32) + ix count(1) + ix(1 + 1 + 2 + 1 + 12)
        assertEquals(3 + 1 + 96 + 32 + 1 + 17, message.size)
        assertEquals(1, message[0].toInt()) // 1 signer
        assertEquals(0, message[1].toInt())
        assertEquals(1, message[2].toInt()) // System program is the only read-only unsigned key
        assertTrue(message.copyOfRange(4, 36).contentEquals(Base58.decode(from)))
    }
}
