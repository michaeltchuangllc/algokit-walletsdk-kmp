package com.michaeltchuang.walletsdk.core.solana.domain

import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaEscrowSessionVaultManager
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaSettlementVoucher
import com.michaeltchuang.walletsdk.core.solana.data.SolanaRpcClient
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.transaction.asSolanaTransactionSigner
import com.michaeltchuang.walletsdk.core.solana.utils.SolanaPda
import kotlinx.coroutines.flow.first

/*
 * Liquid Stream settlement on Solana, payee side.
 *
 * - The payer's session key only signs vouchers (`settle` as the channel's authorized signer).
 *   It never pays fees, so it needs no SOL.
 * - The payee pays the settle fees. A Seed Vault payee can't sign silently, so its own linked
 *   session key acts as fee payer: it cosigns and broadcasts each voucher without a Seed Vault
 *   prompt. That key needs a little SOL (fees, plus one-time USDC account rent on first settle).
 *   Settled USDC always goes to the payee's Seed Vault account, never to the session key.
 */

/**
 * Address the payer must use as `feePayer` when building vouchers for [payeeAddress]: the payee's
 * linked session key. The payee advertises it in its payment request.
 */
class GetSolanaSettlementFeePayerUseCase(
    private val ensureSessionKey: EnsureSolanaSessionKeyUseCase,
) {
    suspend operator fun invoke(payeeAddress: String): String = ensureSessionKey(payeeAddress).signerAddress
}

/**
 * Validates a voucher for a channel paid to [payeeAddress], cosigns it with the payee's session
 * key as fee payer and broadcasts it. No Seed Vault approval is needed. Fails fast with a clear
 * message if the session key doesn't hold enough SOL to cover the fee (and first-time rent).
 */
class SubmitSolanaSettlementVoucherUseCase(
    private val manager: SolanaEscrowSessionVaultManager,
    private val getSessionSigner: GetSolanaSessionSignerUseCase,
    private val rpcClient: SolanaRpcClient,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
) {
    suspend operator fun invoke(
        payeeAddress: String,
        voucher: SolanaSettlementVoucher,
    ): Result<String> {
        val cluster =
            runCatching { SolanaClusters.clusterFor(getCurrentNetworkUseCase().first()) }
                .getOrElse { return Result.failure(it) }
        return runCatching {
            val channel =
                manager.getChannel(cluster, voucher.channel)
                    ?: error("Channel ${voucher.channel} not found (closed or never opened)")
            require(channel.payee == payeeAddress) { "Channel ${voucher.channel} doesn't pay $payeeAddress" }

            val feePayer =
                getSessionSigner(payeeAddress)?.asSolanaTransactionSigner()
                    ?: error("No session key linked to $payeeAddress")
            require(voucher.feePayer == feePayer.address) {
                "Voucher was built for fee payer ${voucher.feePayer}, expected session key ${feePayer.address}"
            }

            val required = requiredLamports(cluster, channel.payee, channel.mint, voucher.ensurePayeeTokenAccount)
            val available = rpcClient.getBalance(cluster, feePayer.address)
            check(available >= required) {
                "Send at least ${formatSol(required - available)} SOL to your session key ${feePayer.address.take(6)}… " +
                    "so it can pay settlement fees."
            }
            feePayer
        }.fold(
            onSuccess = { feePayer -> manager.submitSettlementVoucher(cluster, voucher, feePayer) },
            onFailure = { Result.failure(it) },
        )
    }

    /** Two signatures (fee payer + authorized signer), plus ATA rent if the payee's USDC account is missing. */
    private suspend fun requiredLamports(
        cluster: String,
        payee: String,
        mint: String,
        ensurePayeeTokenAccount: Boolean,
    ): Long {
        val fees = SolanaClusters.LAMPORTS_PER_SIGNATURE * 2
        if (!ensurePayeeTokenAccount) return fees
        val ataMissing = rpcClient.getAccountData(cluster, SolanaPda.associatedTokenAddress(payee, mint)) == null
        return if (ataMissing) fees + SolanaTransactionBuilder.TOKEN_ACCOUNT_RENT_LAMPORTS else fees
    }

    private fun formatSol(lamports: Long): String {
        val raw = lamports.coerceAtLeast(0).toString().padStart(10, '0')
        return "${raw.dropLast(9)}.${raw.takeLast(9)}".trimEnd('0').trimEnd('.')
    }
}
