package com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import com.michaeltchuang.walletsdk.core.solana.utils.SolanaPda
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SolanaStreamVaultTest {
    private val random = Random(7)
    private val payer = Base58.encode(random.nextBytes(32))
    private val payee = Base58.encode(random.nextBytes(32))
    private val signer = random.nextBytes(32)

    @Test
    fun mapsDevnetAndMainnetToTheirVaultConfig() {
        assertEquals("devnet", SolanaStreamVault.cluster(MppNetworks.SOLANA_DEVNET))
        assertEquals("mainnet-beta", SolanaStreamVault.cluster(MppNetworks.SOLANA_MAINNET))
        assertEquals(SolanaEscrowSessionVaultConfig.DEVNET, SolanaStreamVault.config(MppNetworks.SOLANA_DEVNET))
        assertEquals(SolanaEscrowSessionVaultConfig.MAINNET, SolanaStreamVault.config(MppNetworks.SOLANA_MAINNET))
    }

    @Test
    fun rejectsNetworksWithoutTheVault() {
        assertFailsWith<IllegalArgumentException> { SolanaStreamVault.cluster(MppNetworks.SOLANA_TESTNET) }
        assertFailsWith<IllegalArgumentException> { SolanaStreamVault.config(MppNetworks.ALGORAND_MAINNET) }
    }

    @Test
    fun mainnetChannelIsTheMainnetMintPda() {
        val devnet = SolanaStreamVault.deriveChannel(payer, payee, signer, MppNetworks.SOLANA_DEVNET)
        val mainnet = SolanaStreamVault.deriveChannel(payer, payee, signer, MppNetworks.SOLANA_MAINNET)
        // Same program on both clusters, but the PDA commits to the cluster's USDC mint.
        assertFalse(devnet.contentEquals(mainnet))
        val expected =
            SolanaPda
                .findProgramAddress(
                    listOf(
                        "channel".encodeToByteArray(),
                        Base58.decode(payer),
                        Base58.decode(payee),
                        Base58.decode(SolanaEscrowSessionVaultConfig.MAINNET_USDC_MINT),
                        SolanaStreamVault.salt(payer, payee, signer),
                    ),
                    SolanaEscrowSessionVaultConfig.MAINNET_PROGRAM_ID,
                ).address
        assertContentEquals(Base58.decode(expected), mainnet)
    }
}
