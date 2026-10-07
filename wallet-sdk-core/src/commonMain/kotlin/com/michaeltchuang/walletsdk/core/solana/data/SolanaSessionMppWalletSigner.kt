package com.michaeltchuang.walletsdk.core.solana.data

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType
import com.michaeltchuang.walletsdk.core.railmpp.internal.signEd25519
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.utils.Base58

/**
 * Auto-signing [MppWalletSigner] backed by a hot session key linked to a Seed Vault account.
 * Payments are drawn from the session key's own balance, so the maximum exposure is whatever
 * the user funded it with.
 */
class SolanaSessionMppWalletSigner(
    private val ownerAddress: String,
    signerAddress: String,
    private val repository: SolanaLinkedSignerRepository,
    private val rpcClient: SolanaRpcClient,
) : MppWalletSigner {
    override val address: String = signerAddress
    override val authorizedSignerPublicKey: ByteArray = Base58.decode(signerAddress)
    override val signerType: MppWalletSignerType = MppWalletSignerType.ED25519

    override suspend fun signMessage(message: ByteArray): ByteArray =
        withSeed { seed -> signEd25519(seed, message) ?: error("Ed25519 signing failed") }

    override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
        throw UnsupportedOperationException("Solana session keys cannot sign Algorand transactions")

    override suspend fun createSolanaSignedTransaction(
        recipientAddress: String,
        amount: String,
        network: String,
        mint: String?,
    ): ByteArray {
        val baseUnits = amount.toLongOrNull() ?: error("Invalid Solana amount '$amount' (expected base units)")
        require(Base58.isValidSolanaAddress(recipientAddress)) { "Invalid Solana recipient $recipientAddress" }

        val instruction =
            if (mint.isNullOrBlank()) {
                SolanaTransactionBuilder.systemTransfer(from = address, to = recipientAddress, lamports = baseUnits)
            } else {
                val source =
                    rpcClient.getTokenAccounts(network, address, mint).firstOrNull()
                        ?: error("Session key $address has no token account for mint $mint — fund it first")
                check(source.amount >= baseUnits) {
                    "Session key balance too low (${source.amount} < $baseUnits). Top it up from the Seed Vault."
                }
                val destination =
                    rpcClient.getTokenAccounts(network, recipientAddress, mint).firstOrNull()
                        ?: error("Recipient $recipientAddress has no token account for mint $mint")
                SolanaTransactionBuilder.tokenTransferChecked(
                    sourceTokenAccount = source.address,
                    mint = mint,
                    destinationTokenAccount = destination.address,
                    authority = address,
                    amount = baseUnits,
                    decimals = source.decimals,
                    tokenProgramId = source.tokenProgramId.ifBlank { SolanaTransactionBuilder.TOKEN_PROGRAM_ID },
                )
            }

        val blockhash = rpcClient.getLatestBlockhash(network)
        val message = SolanaTransactionBuilder.compileMessage(address, blockhash, listOf(instruction))
        return withSeed { seed -> SolanaTransactionBuilder.signAndSerialize(message, seed) }
    }

    private suspend fun <T> withSeed(block: (ByteArray) -> T): T {
        val seed = repository.getPrivateSeed(ownerAddress) ?: error("No session key linked to $ownerAddress")
        return try {
            block(seed)
        } finally {
            seed.fill(0)
        }
    }
}
