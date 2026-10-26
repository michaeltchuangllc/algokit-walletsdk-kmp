package com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSessionSignerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSettlementFeePayerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaClusters
import com.michaeltchuang.walletsdk.core.solana.domain.SubmitSolanaSettlementVoucherUseCase
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionSigner
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import com.michaeltchuang.walletsdk.core.solana.utils.SolanaPda
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

object SolanaStreamVault : KoinComponent {
    private val manager: SolanaEscrowSessionVaultManager get() = get()

    fun isSolana(network: String): Boolean = network.startsWith("solana:", ignoreCase = true)

    /** Maps a LiquidStream MPP network to its Solana cluster; only devnet and mainnet-beta have the vault. */
    fun cluster(network: String): String =
        when (network) {
            MppNetworks.SOLANA_DEVNET -> SolanaClusters.DEVNET
            MppNetworks.SOLANA_MAINNET -> SolanaClusters.MAINNET
            else -> throw IllegalArgumentException("LiquidStream supports Solana devnet and mainnet only: $network")
        }

    /** Vault program and USDC mint for [network]; the payee advertises these and the viewer checks them. */
    fun config(network: String): SolanaEscrowSessionVaultConfig =
        if (cluster(network) == SolanaClusters.MAINNET) {
            SolanaEscrowSessionVaultConfig.MAINNET
        } else {
            SolanaEscrowSessionVaultConfig.DEVNET
        }

    fun salt(
        payer: String,
        payee: String,
        signer: ByteArray,
    ): ByteArray {
        require(signer.size == 32 && signer.any { it != 0.toByte() }) { "Invalid Solana session signer" }
        return sha256("wallet-sdk:liquid-stream:solana:v1".encodeToByteArray() + key(payer) + key(payee) + signer)
    }

    fun deriveChannel(
        payer: String,
        payee: String,
        signer: ByteArray,
        network: String,
    ): ByteArray {
        val config = config(network)
        return Base58.decode(
            SolanaPda
                .findProgramAddress(
                    listOf("channel".encodeToByteArray(), key(payer), key(payee), key(config.usdcMint), salt(payer, payee, signer)),
                    config.programId,
                ).address,
        )
    }

    suspend fun read(
        payer: String,
        payee: String,
        signer: ByteArray,
        network: String,
        channel: ByteArray? = null,
    ): Result<HostViewerVaultReader.Snapshot> =
        result {
            // Only a successful getAccountInfo(value=null) means an unopened/closed vault.
            // RPC errors and invalid existing accounts remain failures, never zero balances.
            findState(payer, payee, signer, network, channel)?.let(::snapshot)
                ?: HostViewerVaultReader.Snapshot(0, 0, 0, 0)
        }

    suspend fun dynamicData(
        payer: String,
        payee: String,
        signer: ByteArray,
        network: String,
        channel: ByteArray? = null,
    ): MppPayments.SessionDynamicData? =
        result {
            val state = findState(payer, payee, signer, network, channel)
            if (state == null) {
                MppPayments.SessionDynamicData(0, 0, 0, 0)
            } else {
                MppPayments.SessionDynamicData(state.totalDeposit, state.lastSettled, state.latestVoucherAmount, state.startSlot)
            }
        }.getOrNull()

    suspend fun feePayer(payee: String): String {
        key(payee)
        return get<GetSolanaSettlementFeePayerUseCase>()(payee)
    }

    suspend fun fund(
        network: String,
        payer: SolanaTransactionSigner,
        payee: String,
        signer: ByteArray,
        amount: Long,
    ): Result<String> =
        result {
            val cluster = cluster(network)
            require(amount > 0) { "Deposit must be positive" }
            val channel = deriveChannel(payer.address, payee, signer, network)
            val address = Base58.encode(channel)
            val existing = manager.getChannel(cluster, address)
            if (existing == null) {
                manager
                    .open(cluster, payer, payee, amount, Base58.encode(signer), salt(payer.address, payee, signer))
                    .getOrThrow()
                    .signature
            } else {
                validateIdentity(existing, payer.address, payee, signer)
                require(existing.totalDeposit <= Long.MAX_VALUE - amount) { "Deposit overflow" }
                manager.topUp(cluster, payer, address, amount).getOrThrow()
            }
        }

    suspend fun createVoucher(
        network: String,
        payer: String,
        payee: String,
        signer: MppWalletSigner,
        channel: ByteArray,
        amount: Long,
        feePayer: String,
    ): SolanaSettlementVoucher {
        val publicKey = signer.authorizedSignerPublicKey.copyOf()
        val state = state(payer, payee, publicKey, network, channel)
        key(feePayer)
        // MppWalletSigner.address may identify the owner rather than its linked session key.
        val sessionSigner =
            object : SolanaTransactionSigner {
                override val address = Base58.encode(publicKey)

                override suspend fun signMessage(message: ByteArray) = signer.signMessage(message)
            }
        return manager.createSettlementVoucher(cluster(network), state.address, amount, sessionSigner, feePayer)
    }

    suspend fun validateVoucher(
        network: String,
        payer: String,
        payee: String,
        signer: ByteArray,
        channel: ByteArray,
        transaction: ByteArray,
        amount: Long,
    ): Result<HostViewerVaultReader.Snapshot> =
        result {
            val state = state(payer, payee, signer, network, channel)
            reconstruct(network, state, transaction, amount, localFeePayer(payee))
            snapshot(state)
        }

    suspend fun settleVoucher(
        network: String,
        payer: String,
        payee: String,
        signer: ByteArray,
        channel: ByteArray,
        transaction: ByteArray,
        amount: Long,
    ): Result<Unit> =
        result {
            val state = state(payer, payee, signer, network, channel)
            val voucher = reconstruct(network, state, transaction, amount, localFeePayer(payee))
            get<SubmitSolanaSettlementVoucherUseCase>()(cluster(network), payee, voucher).getOrThrow()
        }

    /**
     * Payee-initiated `close` after a viewer leaves: refunds the remaining deposit (and channel
     * rent) to the payer. The program only accepts the payee itself as caller, so [ownerSigner]
     * (the payee's Seed Vault account) must sign — one approval. The payee also pays the fee, so
     * the transaction carries a single signature and never touches the session key's SOL.
     * Returns null when the channel is already closed.
     */
    suspend fun closeChannel(
        network: String,
        payer: String,
        payee: String,
        signer: ByteArray,
        channel: ByteArray,
        ownerSigner: SolanaTransactionSigner,
    ): Result<String?> =
        result {
            require(ownerSigner.address == payee) { "Only the channel payee can close the channel" }
            val cluster = cluster(network)
            val state = findState(payer, payee, signer, network, channel) ?: return@result null
            manager.close(cluster, ownerSigner, state.address, feePayer = ownerSigner).getOrThrow()
        }

    private suspend fun localFeePayer(payee: String): String =
        get<GetSolanaSessionSignerUseCase>()(payee)?.address ?: error("No local Solana session key linked to $payee")

    internal fun reconstruct(
        network: String,
        state: SolanaEscrowChannel,
        transaction: ByteArray,
        amount: Long,
        expectedFeePayer: String,
        vaultManager: SolanaEscrowSessionVaultManager = manager,
    ): SolanaSettlementVoucher {
        val cluster = cluster(network)
        var failure: Throwable? = null
        for (ensureAta in listOf(true, false)) {
            val voucher =
                SolanaSettlementVoucher(
                    state.address,
                    amount,
                    state.authorizedSigner,
                    expectedFeePayer,
                    ensureAta,
                    transaction.copyOf(),
                )
            try {
                vaultManager.validateSettlementVoucher(cluster, state, voucher, expectedFeePayer)
                return voucher
            } catch (e: IllegalArgumentException) {
                failure = e
            }
        }
        throw failure ?: IllegalArgumentException("Invalid settlement transaction")
    }

    private suspend fun state(
        payer: String,
        payee: String,
        signer: ByteArray,
        network: String,
        channel: ByteArray?,
    ): SolanaEscrowChannel = findState(payer, payee, signer, network, channel) ?: error("Solana channel not found")

    private suspend fun findState(
        payer: String,
        payee: String,
        signer: ByteArray,
        network: String,
        channel: ByteArray?,
    ): SolanaEscrowChannel? {
        val expected = deriveChannel(payer, payee, signer, network)
        require(channel == null || channel.contentEquals(expected)) { "Unexpected LiquidStream channel" }
        return manager.getChannel(cluster(network), Base58.encode(expected))?.also {
            validateIdentity(it, payer, payee, signer)
        }
    }

    internal fun validateIdentity(
        state: SolanaEscrowChannel,
        payer: String,
        payee: String,
        signer: ByteArray,
    ) {
        require(state.payer == payer && state.payee == payee) { "Channel payer/payee mismatch" }
        require(!state.isSignerRevoked && state.authorizedSigner == Base58.encode(signer)) { "Channel signer mismatch" }
        require(state.salt.contentEquals(salt(payer, payee, signer))) { "Channel salt mismatch" }
    }

    internal fun snapshot(state: SolanaEscrowChannel) =
        HostViewerVaultReader.Snapshot(
            remainingBalanceMicroUsdc = state.totalDeposit - state.lastSettled,
            lastSettledMicroUsdc = state.lastSettled,
            progressBalanceMicroUsdc = state.totalDeposit - maxOf(state.lastSettled, state.latestVoucherAmount),
            totalDepositMicroUsdc = state.totalDeposit,
            startRound = state.startSlot,
        )

    private fun key(address: String): ByteArray =
        Base58.decode(address).also {
            require(it.size == 32 && Base58.encode(it) == address) { "Invalid Solana address" }
        }

    private suspend fun <T> result(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
