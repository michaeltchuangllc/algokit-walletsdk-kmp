package com.michaeltchuang.walletsdk.ui.liquidAuth.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * X402 Streaming Payment Messages
 *
 * Payment flow:
 * 1. Creator sends [PaymentRequest] - requests 1 ALGO deposit
 * 2. Client responds with [PaymentResponse] - signed transaction
 * 3. Creator holds funds in escrow/vault
 * 4. Each block: deduct 0.1 ALGO from balance
 * 5. UI shows remaining balance
 * 6. When depleted: stop stream or request more
 * 7. Creator claims with single transaction
 */
object X402PaymentMessages {
    const val PAYMENT_REQUEST = "liquid:payment:request"
    const val PAYMENT_RESPONSE = "liquid:payment:response"
    const val BALANCE_UPDATE = "liquid:payment:balance"
    const val FUNDS_DEPLETED = "liquid:payment:depleted"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Payment Request from Creator to Client
     * Asks client to sign 1 ALGO deposit transaction
     */
    @Serializable
    data class PaymentRequest(
        val reference: String = PAYMENT_REQUEST,
        val id: String, // Unique payment session ID
        val amountMicroAlgos: Long, // 1 ALGO = 1_000_000 microAlgos
        val creatorAddress: String, // Where funds will be held
        val network: String = "testnet", // mainnet/testnet
        val note: String = "X402 Streaming Deposit",
    ) {
        fun toJson(): String = json.encodeToString(serializer(), this)

        companion object {
            fun fromJson(jsonStr: String): PaymentRequest = json.decodeFromString(serializer(), jsonStr)
        }
    }

    /**
     * Payment Response from Client to Creator
     * Contains signed transaction ready to submit
     */
    @Serializable
    data class PaymentResponse(
        val reference: String = PAYMENT_RESPONSE,
        val id: String, // Same ID as request
        val signedTransactionB64: String, // Base64 signed txn
        val clientAddress: String, // Sender address
        val status: Status,
        val errorMessage: String? = null,
    ) {
        enum class Status {
            SIGNED, // Successfully signed
            REJECTED, // User rejected
            ERROR, // Error during signing
        }

        fun toJson(): String = json.encodeToString(serializer(), this)

        companion object {
            fun fromJson(jsonStr: String): PaymentResponse = json.decodeFromString(serializer(), jsonStr)
        }
    }

    /**
     * Balance Update from Creator to Client
     * Sent each block or every few seconds with remaining balance
     */
    @Serializable
    data class BalanceUpdate(
        val reference: String = BALANCE_UPDATE,
        val id: String, // Payment session ID
        val initialDepositMicroAlgos: Long, // Original 1 ALGO
        val consumedMicroAlgos: Long, // Amount used so far
        val remainingMicroAlgos: Long, // Current balance
        val blocksWatched: Int, // Number of blocks consumed
        val costPerBlockMicroAlgos: Long = 100_000, // 0.1 ALGO per block
    ) {
        fun toJson(): String = json.encodeToString(serializer(), this)

        fun remainingUsdc(): Double = remainingMicroAlgos / 1_000_000.0

        fun consumedUsdc(): Double = consumedMicroAlgos / 1_000_000.0

        companion object {
            fun fromJson(jsonStr: String): BalanceUpdate = json.decodeFromString(serializer(), jsonStr)
        }
    }

    /**
     * Funds Depleted - sent when balance reaches 0
     */
    @Serializable
    data class FundsDepleted(
        val reference: String = FUNDS_DEPLETED,
        val id: String,
        val totalBlocksWatched: Int,
        val totalConsumedMicroAlgos: Long,
    ) {
        fun toJson(): String = json.encodeToString(serializer(), this)

        companion object {
            fun fromJson(jsonStr: String): FundsDepleted = json.decodeFromString(serializer(), jsonStr)
        }
    }
}
