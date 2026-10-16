package com.michaeltchuang.walletsdk.core.solana.domain

import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.solana.data.SolanaRpcClient
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionSigner
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import kotlinx.coroutines.flow.first

/**
 * Sends native SOL from a Seed Vault (or any [SolanaTransactionSigner]) account to another
 * Solana address on the cluster matching the app-wide network toggle. The sender pays the fee.
 *
 * Validation mirrors the runtime's rent rules so the user gets a clear message instead of a
 * preflight failure:
 * - An empty recipient must receive at least the rent-exempt minimum.
 * - The sender must either keep the rent-exempt minimum or be drained to exactly 0.
 */
class SendSolanaSolUseCase(
    private val rpcClient: SolanaRpcClient,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
) {
    suspend fun currentCluster(): String = SolanaClusters.clusterFor(getCurrentNetworkUseCase().first())

    suspend fun getBalance(address: String): Long = rpcClient.getBalance(currentCluster(), address)

    suspend operator fun invoke(
        from: SolanaTransactionSigner,
        to: String,
        lamports: Long,
    ): String {
        val recipient = to.trim()
        require(Base58.isValidSolanaAddress(recipient)) { "Enter a valid Solana address" }
        require(recipient != from.address) { "Recipient must be a different account" }
        require(lamports > 0) { "Enter an amount greater than 0" }

        val cluster = currentCluster()
        val senderBalance = rpcClient.getBalance(cluster, from.address)
        val remaining = senderBalance - lamports - FEE_LAMPORTS
        check(remaining >= 0) {
            "Insufficient SOL. Available to send: ${formatSol((senderBalance - FEE_LAMPORTS).coerceAtLeast(0))} SOL"
        }
        check(remaining == 0L || remaining >= SolanaClusters.RENT_EXEMPT_MINIMUM_LAMPORTS) {
            "Leave at least ${formatSol(SolanaClusters.RENT_EXEMPT_MINIMUM_LAMPORTS)} SOL in this account, " +
                "or send the max to empty it."
        }

        val recipientBalance = rpcClient.getBalance(cluster, recipient)
        check(recipientBalance + lamports >= SolanaClusters.RENT_EXEMPT_MINIMUM_LAMPORTS) {
            "The recipient account is new; send at least " +
                "${formatSol(SolanaClusters.RENT_EXEMPT_MINIMUM_LAMPORTS - recipientBalance)} SOL to activate it."
        }

        val message =
            SolanaTransactionBuilder.compileMessage(
                feePayer = from.address,
                recentBlockhash = rpcClient.getLatestBlockhash(cluster),
                instructions = listOf(SolanaTransactionBuilder.systemTransfer(from.address, recipient, lamports)),
            )
        val signature = from.signMessage(message)
        require(signature.size == 64) { "Solana requires a 64-byte Ed25519 signature." }
        val txSignature = rpcClient.sendTransaction(cluster, SolanaTransactionBuilder.serialize(message, listOf(signature)))
        rpcClient.confirmTransaction(cluster, txSignature)
        return txSignature
    }

    companion object {
        /** Single-signature legacy transfer. */
        const val FEE_LAMPORTS = SolanaClusters.LAMPORTS_PER_SIGNATURE

        fun formatSol(lamports: Long): String {
            val raw = lamports.coerceAtLeast(0).toString().padStart(10, '0')
            return "${raw.dropLast(9)}.${raw.takeLast(9)}".trimEnd('0').trimEnd('.')
        }

        /** Parses a decimal SOL string into lamports without floating point. Null if invalid. */
        fun parseSol(input: String): Long? {
            val text = input.trim()
            if (text.isEmpty() || !Regex("""\d*\.?\d*""").matches(text) || text == ".") return null
            val whole = text.substringBefore('.').ifEmpty { "0" }
            val fraction = text.substringAfter('.', "")
            if (fraction.length > 9 || whole.length > 10) return null
            return runCatching { whole.toLong() * SolanaClusters.LAMPORTS_PER_SOL + fraction.padEnd(9, '0').toLong() }
                .getOrNull()
        }
    }
}
