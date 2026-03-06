package com.michaeltchuang.walletsdk.service.demo.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sol4k.Connection
import org.sol4k.Keypair
import org.sol4k.PublicKey
import org.sol4k.Transaction
import org.sol4k.instruction.TransferInstruction

/**
 * Repository for creating and sending Solana transfer transactions.
 * Uses sol4k for transaction building and RPC communication.
 */
class SolanaTransferRepository {

    companion object {
        private const val TAG = "SolanaTransferRepository"
        private const val LAMPORTS_PER_SOL = 1_000_000_000L

        // RPC endpoints - using devnet for testing
        const val MAINNET_RPC = "https://api.mainnet-beta.solana.com"
        const val DEVNET_RPC = "https://api.devnet.solana.com"
        const val TESTNET_RPC = "https://api.testnet.solana.com"

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            isLenient = true
        }
    }

    private var rpcEndpoint: String = DEVNET_RPC
    private var connection: Connection? = null

    /**
     * Enum for Solana network clusters
     */
    enum class Cluster {
        MAINNET_BETA,
        DEVNET,
        TESTNET
    }

    /**
     * Result of a transfer operation
     */
    data class TransferResult(
        val success: Boolean,
        val signature: String? = null,
        val error: String? = null
    )

    /**
     * Initialize the repository with specified network cluster.
     * @param cluster The network cluster (default: DEVNET)
     */
    fun initialize(cluster: Cluster = Cluster.DEVNET) {
        rpcEndpoint = when (cluster) {
            Cluster.MAINNET_BETA -> MAINNET_RPC
            Cluster.DEVNET -> DEVNET_RPC
            Cluster.TESTNET -> TESTNET_RPC
        }

        // Initialize sol4k connection
        connection = Connection(rpcEndpoint)

        Log.d(TAG, "Initialized connection to ${cluster.name} at $rpcEndpoint")
    }

    /**
     * Create a SOL transfer transaction.
     * This creates the transaction but does NOT sign it - signing should be done by Seed Vault.
     *
     * @param fromPublicKey The sender's public key (base58 encoded)
     * @param toPublicKey The recipient's public key (base58 encoded)
     * @param amountSol The amount of SOL to transfer
     * @return The serialized transaction as a byte array (base64 encoded), or null if creation failed
     */
    /**
     * Create a transfer transaction and return both the transaction object and serialized message.
     * This allows for proper signing and serialization later.
     */
    suspend fun createTransferTransactionData(
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double
    ): Pair<Transaction, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: run {
                Log.e(TAG, "Connection not initialized. Call initialize() first.")
                return@withContext null
            }

            // Convert amount to lamports
            val lamports = (amountSol * LAMPORTS_PER_SOL).toLong()

            Log.d(TAG, "Creating transfer transaction: $amountSol SOL ($lamports lamports)")
            Log.d(TAG, "From: $fromPublicKey")
            Log.d(TAG, "To: $toPublicKey")

            // Create public keys from base58 strings
            val fromPubKey = PublicKey(fromPublicKey)
            val toPubKey = PublicKey(toPublicKey)

            // Get recent blockhash
            val recentBlockhash = conn.getLatestBlockhash()
            Log.d(TAG, "Recent blockhash: $recentBlockhash")

            // Create transfer instruction
            val transferInstruction = TransferInstruction(
                fromPubKey,
                toPubKey,
                lamports
            )

            // Build transaction
            val transaction = Transaction(
                recentBlockhash,
                transferInstruction,
                fromPubKey  // Fee payer is the sender
            )

            Log.d(TAG, "Transaction created successfully")

            // Serialize the transaction message (unsigned)
            // Note: transaction.serialize() returns the full transaction with empty signatures array
            // Format: [0x00] + [message], so we need to skip the first byte to get just the message
            val serializedWithEmptySig = transaction.serialize()
            val serializedMessage = if (serializedWithEmptySig.isNotEmpty() && serializedWithEmptySig[0] == 0.toByte()) {
                // Remove the empty signatures count byte to get just the message
                serializedWithEmptySig.copyOfRange(1, serializedWithEmptySig.size)
            } else {
                // Already just the message
                serializedWithEmptySig
            }
            Log.d(TAG, "Transaction message serialized: ${serializedMessage.size} bytes (raw: ${serializedWithEmptySig.size} bytes)")
            Log.d(TAG, "Message first 10 bytes hex: ${serializedMessage.take(10).joinToString(" ") { "%02x".format(it) }})")

            Pair(transaction, serializedMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating transfer transaction", e)
            null
        }
    }

    /**
     * Serialize a signed transaction for sending.
     * This creates the proper Solana transaction format with signatures.
     */
    fun serializeSignedTransaction(
        message: ByteArray,
        signature: ByteArray
    ): ByteArray {
        // Solana transaction format for legacy (non-versioned) transactions:
        // [signatures count: compact-u16] + [signatures: 64 bytes each] + [message]
        //
        // Compact-u16 encoding:
        // - Values 0-127: 1 byte (0xxxxxxx)
        // - Values 128-16383: 2 bytes (1xxxxxxx 0xxxxxxx)
        // - etc.
        //
        // For 1 signature, we just need 1 byte: 0x01

        val signatureCount = 1
        val signatureLength = 64

        // Calculate total size
        val totalSize = 1 + signatureLength + message.size
        val result = ByteArray(totalSize)

        // Write signature count as compact-u16 (1 signature = 0x01)
        result[0] = signatureCount.toByte()

        // Copy signature (64 bytes)
        System.arraycopy(signature, 0, result, 1, minOf(signature.size, signatureLength))

        // Copy message after signature
        System.arraycopy(message, 0, result, 1 + signatureLength, message.size)

        Log.d(TAG, "Serialized signed transaction: ${result.size} bytes")
        Log.d(TAG, "  - Signature count: $signatureCount (1 byte)")
        Log.d(TAG, "  - Signature: ${signatureLength} bytes")
        Log.d(TAG, "  - Message: ${message.size} bytes")
        Log.d(TAG, "  - First 10 bytes hex: ${result.take(10).joinToString(" ") { "%02x".format(it) }})")

        return result
    }

    @Deprecated("Use createTransferTransactionData instead")
    suspend fun createTransferTransaction(
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double
    ): ByteArray? = withContext(Dispatchers.IO) {
        createTransferTransactionData(fromPublicKey, toPublicKey, amountSol)?.second
    }

    @Deprecated("Use createTransferTransactionData and serializeSignedTransaction instead")
    suspend fun createSerializedTransferTransaction(
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double
    ): String? = withContext(Dispatchers.IO) {
        val transactionBytes = createTransferTransaction(fromPublicKey, toPublicKey, amountSol)
        transactionBytes?.let {
            java.util.Base64.getEncoder().encodeToString(it)
        }
    }

    /**
     * Send a pre-signed transaction to the network.
     *
     * @param signedTransaction The signed transaction as a byte array
     * @return TransferResult with success/failure and transaction signature
     */
    suspend fun sendSignedTransaction(
        signedTransaction: ByteArray
    ): TransferResult = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: run {
                return@withContext TransferResult(
                    success = false,
                    error = "Connection not initialized. Call initialize() first."
                )
            }

            Log.d(TAG, "Sending signed transaction: ${signedTransaction.size} bytes")

            // Send the transaction
            val signature = conn.sendTransaction(signedTransaction)

            Log.d(TAG, "Transaction sent successfully! Signature: $signature")

            TransferResult(
                success = true,
                signature = signature
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending signed transaction", e)
            TransferResult(
                success = false,
                error = "Failed to send transaction: ${e.message}"
            )
        }
    }

    /**
     * Send a pre-signed transaction to the network (base64 version).
     *
     * @param signedTransactionBase64 The signed transaction as a base64-encoded string
     * @return TransferResult with success/failure and transaction signature
     */
    suspend fun sendSignedTransactionBase64(
        signedTransactionBase64: String
    ): TransferResult {
        // Clean the base64 string (remove newlines and trim)
        val cleanBase64 = signedTransactionBase64.trim().replace("\n", "").replace("\r", "")
        val signedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
        return sendSignedTransaction(signedBytes)
    }

    /**
     * Get the minimum balance required to be rent-exempt for a new account.
     * @return The minimum balance in lamports, or null if error
     */
    suspend fun getMinimumBalanceForRentExemption(): Long? = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: run {
                Log.e(TAG, "Connection not initialized")
                return@withContext null
            }

            // Get minimum balance for rent exemption (0 bytes for simple account)
            val minimumBalance = conn.getMinimumBalanceForRentExemption(0)
            Log.d(TAG, "Minimum balance for rent exemption: $minimumBalance lamports")

            minimumBalance
        } catch (e: Exception) {
            Log.e(TAG, "Error getting minimum balance for rent exemption", e)
            null
        }
    }

    /**
     * Check if repository is initialized.
     */
    fun isInitialized(): Boolean = connection != null

    /**
     * Get the current RPC endpoint URL.
     */
    fun getRpcEndpoint(): String = rpcEndpoint

    /**
     * Close the connection and cleanup resources.
     */
    fun close() {
        connection = null
    }

    /**
     * Convert lamports to SOL
     */
    fun lamportsToSol(lamports: Long): Double {
        return lamports / LAMPORTS_PER_SOL.toDouble()
    }

    /**
     * Convert SOL to lamports
     */
    fun solToLamports(sol: Double): Long {
        return (sol * LAMPORTS_PER_SOL).toLong()
    }
}
