package com.michaeltchuang.walletsdk.service.demo.data.repository

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repository for fetching Solana account balances from the network.
 * Uses Ktor for HTTP communication with Solana RPC endpoints.
 */
class SolanaBalanceRepository {

    companion object {
        private const val TAG = "SolanaBalanceRepository"
        private const val LAMPORTS_PER_SOL = 1_000_000_000.0

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

    private var httpClient: HttpClient? = null
    private var rpcEndpoint: String = DEVNET_RPC

    /**
     * Enum for Solana network clusters
     */
    enum class Cluster {
        MAINNET_BETA,
        DEVNET,
        TESTNET
    }

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

        httpClient = HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Log.d(TAG, message)
                    }
                }
                level = LogLevel.INFO
            }
            engine {
                connectTimeout = 30_000
                socketTimeout = 30_000
            }
        }

        Log.d(TAG, "Initialized connection to ${cluster.name} at $rpcEndpoint")
    }

    /**
     * Fetch balance for a single account.
     * @param publicKeyBase58 The account's public key in base58 format
     * @return Balance in SOL, or null if error
     */
    suspend fun fetchBalance(publicKeyBase58: String): Double? = withContext(Dispatchers.IO) {
        try {
            val client = httpClient ?: run {
                Log.e(TAG, "HTTP client not initialized. Call initialize() first.")
                return@withContext null
            }

            // Create JSON request
            val requestJson = """{"jsonrpc":"2.0","id":1,"method":"getBalance","params":["$publicKeyBase58"]}"""

            Log.d(TAG, "Sending request to: $rpcEndpoint")
            Log.d(TAG, "Request body: $requestJson")
            Log.d(TAG, "Public key: $publicKeyBase58")

            // Execute Ktor request
            val response: String = client.post {
                url(rpcEndpoint)
                contentType(ContentType.Application.Json)
                setBody(requestJson)
            }.body()

            Log.d(TAG, "Response received - length: ${response.length}")

            if (response.length > 500) {
                Log.d(TAG, "Raw response (truncated): ${response.take(500)}...")
            } else {
                Log.d(TAG, "Raw response: $response")
            }

            // Parse JSON response
            parseBalanceResponse(response, publicKeyBase58)

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching balance for $publicKeyBase58", e)
            null
        }
    }

    /**
     * Parse balance from JSON-RPC response
     */
    private fun parseBalanceResponse(responseString: String, publicKeyBase58: String): Double? {
        return try {
            // Parse JSON manually
            val jsonElement = json.parseToJsonElement(responseString)

            // Check for error
            if (jsonElement is JsonObject && jsonElement.containsKey("error")) {
                val errorObj = jsonElement["error"]
                Log.e(TAG, "RPC Error: $errorObj")
                return null
            }

            // Extract balance from result.value
            val resultObj = (jsonElement as? JsonObject)?.get("result") as? JsonObject
            val value = resultObj?.get("value")?.jsonPrimitive?.content?.toLongOrNull()

            if (value == null) {
                Log.e(TAG, "No balance value in response. Result: $resultObj")
                return null
            }

            val balanceSol = value / LAMPORTS_PER_SOL
            Log.d(TAG, "✓ Balance for $publicKeyBase58: $balanceSol SOL ($value lamports)")

            balanceSol
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch balances for multiple accounts.
     * @param publicKeyBase58List List of public keys
     * @return Map of public key to balance in SOL
     */
    suspend fun fetchBalances(publicKeyBase58List: List<String>): Map<String, Double?> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Double?>()

        publicKeyBase58List.forEach { publicKey ->
            results[publicKey] = fetchBalance(publicKey)
        }

        results
    }

    /**
     * Check if repository is initialized.
     */
    fun isInitialized(): Boolean = httpClient != null

    /**
     * Get the current RPC endpoint URL.
     */
    fun getRpcEndpoint(): String = rpcEndpoint

    /**
     * Close the HTTP client and cleanup resources.
     */
    fun close() {
        httpClient?.close()
        httpClient = null
    }
}
