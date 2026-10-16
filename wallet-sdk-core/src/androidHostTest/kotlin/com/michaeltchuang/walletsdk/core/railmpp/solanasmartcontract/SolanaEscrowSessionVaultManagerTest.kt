package com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract

import com.michaeltchuang.walletsdk.core.railmpp.internal.verifyEd25519
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaAccountMeta
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaInstruction
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaKeypairSigner
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import com.michaeltchuang.walletsdk.core.solana.utils.SolanaPda
import kotlinx.coroutines.test.runTest
import org.sol4k.PublicKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolanaEscrowSessionVaultManagerTest {
    private val random = Random(42)

    private fun randomKey(): ByteArray = random.nextBytes(32)

    @Test
    fun findProgramAddressMatchesSol4k() {
        val program = Base58.encode(randomKey())
        repeat(150) {
            val seeds = List(random.nextInt(1, 4)) { randomKey() }
            val ours = SolanaPda.findProgramAddress(seeds, program)
            val reference = PublicKey.findProgramAddress(seeds.map { PublicKey(it) }, PublicKey(program))
            assertEquals(reference.publicKey.toBase58(), ours.address)
            assertEquals(reference.nonce, ours.bump)
        }
    }

    @Test
    fun associatedTokenAddressMatchesSol4k() {
        repeat(50) {
            val owner = Base58.encode(randomKey())
            val mint = Base58.encode(randomKey())
            val reference = PublicKey.findProgramDerivedAddress(PublicKey(owner), PublicKey(mint))
            assertEquals(reference.publicKey.toBase58(), SolanaPda.associatedTokenAddress(owner, mint))
        }
    }

    @Test
    fun discriminatorsAreAnchorStyle() {
        // Anchor: sha256("global:<snake_case_name>")[0..8]
        assertEquals(8, SolanaEscrowSessionVaultManager.IX_SETTLE.size)
        val all =
            listOf(
                SolanaEscrowSessionVaultManager.IX_OPEN,
                SolanaEscrowSessionVaultManager.IX_TOP_UP,
                SolanaEscrowSessionVaultManager.IX_SET_AUTHORIZED_SIGNER,
                SolanaEscrowSessionVaultManager.IX_REVOKE_AUTHORIZED_SIGNER,
                SolanaEscrowSessionVaultManager.IX_SETTLE,
                SolanaEscrowSessionVaultManager.IX_REQUEST_CLOSE,
                SolanaEscrowSessionVaultManager.IX_CLOSE,
                SolanaEscrowSessionVaultManager.IX_WITHDRAW,
            ).map { it.toList() }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun decodesChannelAccount() {
        val payer = randomKey()
        val payee = randomKey()
        val mint = Base58.decode(SolanaEscrowSessionVaultConfig.DEVNET_USDC_MINT)
        val signer = randomKey()
        val salt = randomKey()

        fun u64(v: Long) = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }
        val data =
            SolanaEscrowSessionVaultManager.CHANNEL_ACCOUNT_DISCRIMINATOR + payer + payee + mint + signer +
                u64(3_000_000) + u64(1_200_001) + u64(1_200_001) + u64(12345) + u64(1_700_000_000) + u64(0) +
                byteArrayOf(253.toByte()) + salt
        assertEquals(SolanaEscrowSessionVaultManager.CHANNEL_ACCOUNT_SIZE, data.size)

        val channel = SolanaEscrowSessionVaultManager.decodeChannel("chan", data)
        assertEquals(Base58.encode(payer), channel.payer)
        assertEquals(Base58.encode(payee), channel.payee)
        assertEquals(SolanaEscrowSessionVaultConfig.DEVNET_USDC_MINT, channel.mint)
        assertEquals(Base58.encode(signer), channel.authorizedSigner)
        assertEquals(3_000_000, channel.totalDeposit)
        assertEquals(1_799_999, channel.remainingDeposit)
        assertEquals(12345, channel.startSlot)
        assertNull(channel.withdrawAvailableAt)
        assertEquals(253, channel.bump)
        assertContentEquals(salt, channel.salt)
    }

    @Test
    fun multiSignerTransactionRoundTrip() =
        runTest {
            val feePayer = SolanaKeypairSigner(randomKey())
            val session = SolanaKeypairSigner(randomKey())
            val ix =
                SolanaInstruction(
                    programId = SolanaEscrowSessionVaultConfig.DEVNET_PROGRAM_ID,
                    accounts =
                        listOf(
                            SolanaAccountMeta(Base58.encode(randomKey()), isSigner = false, isWritable = true),
                            SolanaAccountMeta(session.address, isSigner = true, isWritable = false),
                        ),
                    data = SolanaEscrowSessionVaultManager.IX_SETTLE,
                )
            val blockhash = Base58.encode(randomKey())
            val message = SolanaTransactionBuilder.compileMessage(feePayer.address, blockhash, listOf(ix))

            // Header: 2 signers, 1 read-only signer (session key), 1 read-only unsigned (program).
            assertEquals(listOf(2, 1, 1), message.take(3).map { it.toInt() })
            assertEquals(listOf(feePayer.address, session.address), SolanaTransactionBuilder.requiredSigners(message))
            assertEquals(blockhash, SolanaEscrowSessionVaultManager.recentBlockhash(message))

            // Session key signs first (voucher), fee payer cosigns later.
            val partial = SolanaTransactionBuilder.serialize(message, listOf(null, session.signMessage(message)))
            val full = SolanaTransactionBuilder.addSignature(partial, feePayer.address, feePayer.signMessage(message))
            val (signatures, decoded) = SolanaTransactionBuilder.deserialize(full)
            assertContentEquals(message, decoded)
            assertTrue(verifyEd25519(Base58.decode(feePayer.address), message, signatures[0]))
            assertTrue(verifyEd25519(Base58.decode(session.address), message, signatures[1]))
        }

    @Test
    fun mapsAnchorErrorCodes() {
        val fromJson =
            SolanaEscrowSessionVaultManager.mapProgramError(
                IllegalStateException("""{"InstructionError":[1,{"Custom":6004}]}"""),
            )
        assertIs<SolanaEscrowVaultException>(fromJson)
        assertEquals(SolanaEscrowVaultError.VoucherExceedsDeposit, fromJson.error)

        val fromLog =
            SolanaEscrowSessionVaultManager.mapProgramError(IllegalStateException("custom program error: 0x177a"))
        assertIs<SolanaEscrowVaultException>(fromLog)
        assertEquals(SolanaEscrowVaultError.WithdrawalTooEarly, fromLog.error)
    }
}
