package com.michaeltchuang.walletsdk.core.solana.domain

import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.solana.crypto.SolanaKeyDerivation
import com.michaeltchuang.walletsdk.core.solana.data.SolanaLinkedSignerRepository
import com.michaeltchuang.walletsdk.core.solana.data.SolanaRpcClient
import com.michaeltchuang.walletsdk.core.solana.data.SolanaSessionMppWalletSigner
import com.michaeltchuang.walletsdk.core.solana.data.SolanaTokenAccount
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaInstruction
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.utils.SolanaPda
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SubmitSolanaSignedTransactionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GetSolanaLinkedSignerUseCase(
    private val repository: SolanaLinkedSignerRepository,
) {
    suspend operator fun invoke(ownerAddress: String): SolanaLinkedSigner? = repository.get(ownerAddress)

    fun observe(ownerAddress: String): Flow<SolanaLinkedSigner?> = repository.observe(ownerAddress)
}

/**
 * Every Seed Vault account always has a device-generated session key. Returns the existing key,
 * or generates and stores one if the account doesn't have one yet. Idempotent and race-safe.
 */
class EnsureSolanaSessionKeyUseCase(
    private val repository: SolanaLinkedSignerRepository,
) {
    suspend operator fun invoke(ownerAddress: String): SolanaLinkedSigner {
        repository.get(ownerAddress)?.let { return it }
        val keypair = SolanaKeyDerivation.generateEphemeralKeypair()
        return try {
            repository.createIfAbsent(ownerAddress, keypair)
        } finally {
            keypair.privateSeed.fill(0)
        }
    }
}

/**
 * Replaces the account's session key with a freshly generated one. Any funds sent to the old key
 * by mistake are returned to the Seed Vault account first, since the old key is discarded for good.
 * Aborts (keeping the old key) if its balance can't be checked or the sweep fails.
 */
class RegenerateSolanaSessionKeyUseCase(
    private val repository: SolanaLinkedSignerRepository,
    private val returnSessionKeyFunds: ReturnSolanaSessionKeyFundsUseCase,
) {
    suspend operator fun invoke(ownerAddress: String): SolanaLinkedSigner {
        returnSessionKeyFunds(ownerAddress)
        val keypair = SolanaKeyDerivation.generateEphemeralKeypair()
        return try {
            repository.replace(ownerAddress, keypair)
        } finally {
            keypair.privateSeed.fill(0)
        }
    }
}

class GetSolanaSignerBalanceUseCase(
    private val rpcClient: SolanaRpcClient,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
) {
    suspend operator fun invoke(signerAddress: String): SolanaSignerBalance {
        val cluster = SolanaClusters.clusterFor(getCurrentNetworkUseCase().first())
        val lamports = rpcClient.getBalance(cluster, signerAddress)
        val usdc =
            rpcClient
                .getTokenAccounts(cluster, signerAddress, SolanaClusters.usdcMintFor(cluster))
                .sumOf { it.amount }
        return SolanaSignerBalance(lamports = lamports, usdcBaseUnits = usdc)
    }
}

/**
 * Returns the remaining session-key funds (USDC + SOL) to the Seed Vault account in a single
 * transaction and waits for confirmation. The key isn't meant to hold funds; this is a safety
 * net for mistaken transfers.
 *
 * - Creates the Seed Vault's USDC account if it doesn't exist (rent paid by the session key).
 * - Closes the session key's token accounts so their rent also goes back to the Seed Vault.
 * - Throws, without sending anything, if the session key lacks the SOL to cover fees/rent.
 */
class SweepSolanaLinkedSignerUseCase(
    private val repository: SolanaLinkedSignerRepository,
    private val rpcClient: SolanaRpcClient,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val submitSolanaSignedTransactionUseCase: SubmitSolanaSignedTransactionUseCase,
) {
    suspend operator fun invoke(ownerAddress: String): String {
        val signer = repository.get(ownerAddress) ?: error("No session key linked")
        val signerAddress = signer.signerAddress
        val cluster = SolanaClusters.clusterFor(getCurrentNetworkUseCase().first())
        val usdcMint = SolanaClusters.usdcMintFor(cluster)
        val instructions = mutableListOf<SolanaInstruction>()

        val signerTokenAccounts = rpcClient.getTokenAccounts(cluster, signerAddress, usdcMint)
        val fundedTokenAccounts = signerTokenAccounts.filter { it.amount > 0 }
        var spendableLamports = rpcClient.getBalance(cluster, signerAddress) - SolanaClusters.LAMPORTS_PER_SIGNATURE

        if (fundedTokenAccounts.isNotEmpty()) {
            val tokenProgramId = fundedTokenAccounts.first().programIdOrDefault()
            val destination =
                rpcClient.getTokenAccounts(cluster, ownerAddress, usdcMint).firstOrNull()?.address
                    ?: SolanaPda.associatedTokenAddress(ownerAddress, usdcMint, tokenProgramId).also { ata ->
                        instructions +=
                            SolanaTransactionBuilder.createAssociatedTokenAccountIdempotent(
                                funder = signerAddress,
                                associatedAccount = ata,
                                owner = ownerAddress,
                                mint = usdcMint,
                                tokenProgramId = tokenProgramId,
                            )
                        spendableLamports -= SolanaTransactionBuilder.TOKEN_ACCOUNT_RENT_LAMPORTS
                    }
            check(spendableLamports >= 0) {
                val needed = -spendableLamports
                "The session key holds USDC but not enough SOL to return it. Send at least " +
                    "${formatSol(needed)} SOL to ${signerAddress.take(6)}… and try again."
            }
            fundedTokenAccounts.forEach { account ->
                instructions +=
                    SolanaTransactionBuilder.tokenTransferChecked(
                        sourceTokenAccount = account.address,
                        mint = usdcMint,
                        destinationTokenAccount = destination,
                        authority = signerAddress,
                        amount = account.amount,
                        decimals = account.decimals,
                        tokenProgramId = account.programIdOrDefault(),
                    )
            }
        }

        // Emptied token accounts are closed; their rent goes straight to the Seed Vault account.
        signerTokenAccounts.forEach { account ->
            instructions +=
                SolanaTransactionBuilder.closeTokenAccount(
                    tokenAccount = account.address,
                    destination = ownerAddress,
                    authority = signerAddress,
                    tokenProgramId = account.programIdOrDefault(),
                )
        }

        if (spendableLamports > 0) {
            instructions += SolanaTransactionBuilder.systemTransfer(signerAddress, ownerAddress, spendableLamports)
        }
        check(instructions.isNotEmpty()) { "Nothing to return (balance covers fees only)" }

        val message =
            SolanaTransactionBuilder.compileMessage(
                feePayer = signerAddress,
                recentBlockhash = rpcClient.getLatestBlockhash(cluster),
                instructions = instructions,
            )
        val seed = repository.getPrivateSeed(ownerAddress) ?: error("Session key material missing")
        val signed =
            try {
                SolanaTransactionBuilder.signAndSerialize(message, seed)
            } finally {
                seed.fill(0)
            }
        val signature = submitSolanaSignedTransactionUseCase(signed, cluster)
        rpcClient.confirmTransaction(cluster, signature)
        return signature
    }

    private fun SolanaTokenAccount.programIdOrDefault(): String = tokenProgramId.ifBlank { SolanaTransactionBuilder.TOKEN_PROGRAM_ID }

    private fun formatSol(lamports: Long): String {
        val whole = lamports / LAMPORTS_PER_SOL
        val fraction = (lamports % LAMPORTS_PER_SOL).toString().padStart(9, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
    }

    private companion object {
        const val LAMPORTS_PER_SOL = 1_000_000_000L
    }
}

/**
 * Safety net before a session key is discarded (account deletion or regenerate): if anything was
 * sent to the key by mistake, returns it to the Seed Vault account and waits for confirmation.
 * Throws — so the caller keeps the key — if the balance can't be checked or the return fails.
 */
class ReturnSolanaSessionKeyFundsUseCase(
    private val repository: SolanaLinkedSignerRepository,
    private val getSignerBalance: GetSolanaSignerBalanceUseCase,
    private val sweepLinkedSigner: SweepSolanaLinkedSignerUseCase,
) {
    /** Returns the sweep transaction signature, or null if there was nothing to return. */
    suspend operator fun invoke(ownerAddress: String): String? {
        if (!hasFunds(ownerAddress)) return null
        return sweepLinkedSigner(ownerAddress)
    }

    /** True if the account's session key holds funds. Throws if the balance can't be checked. */
    suspend fun hasFunds(ownerAddress: String): Boolean {
        val signer = repository.get(ownerAddress) ?: return false
        val balance =
            try {
                getSignerBalance(signer.signerAddress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Couldn't check the session key balance. Check your connection and try again.",
                    e,
                )
            }
        return balance.hasFunds
    }
}

/** Builds the auto-signing MPP signer for a Seed Vault account, or null if none is linked. */
class GetSolanaSessionSignerUseCase(
    private val repository: SolanaLinkedSignerRepository,
    private val rpcClient: SolanaRpcClient,
) {
    suspend operator fun invoke(ownerAddress: String): SolanaSessionMppWalletSigner? {
        val linked = repository.get(ownerAddress) ?: return null
        return SolanaSessionMppWalletSigner(
            ownerAddress = ownerAddress,
            signerAddress = linked.signerAddress,
            repository = repository,
            rpcClient = rpcClient,
        )
    }
}
