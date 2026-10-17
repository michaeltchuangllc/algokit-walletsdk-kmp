package com.michaeltchuang.seedvault.demo.data.repository

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reads balances from and sends transactions to an Algorand node (algod) over REST, the same
 * endpoints wallet-sdk-core uses.
 */
class AlgorandRepository(
    private val nodeBaseUrl: String = TESTNET_NODE,
) {
    private val httpClient = HttpClient(Android) {
        engine {
            connectTimeout = 30_000
            socketTimeout = 30_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Balance of [address] in microAlgos. */
    suspend fun getBalanceMicroAlgos(address: String): Long = withContext(Dispatchers.IO) {
        val response: HttpResponse = httpClient.get("$nodeBaseUrl/v2/accounts/$address")
        check(response.status.isSuccess()) { "Balance request failed: ${response.status.value} ${response.bodyAsText()}" }
        json.parseToJsonElement(response.body<String>()).jsonObject.getValue("amount").jsonPrimitive.content.toLong()
    }

    /**
     * Network values for a payment as the JSON `signAlgorandPayment` takes:
     * {"genesisId", "genesisHash", "firstValid", "lastValid"}.
     */
    suspend fun getPaymentParamsJson(): String = withContext(Dispatchers.IO) {
        val response: HttpResponse = httpClient.get("$nodeBaseUrl/v2/transactions/params")
        check(response.status.isSuccess()) { "Params request failed: ${response.status.value} ${response.bodyAsText()}" }
        val params = json.parseToJsonElement(response.body<String>()).jsonObject
        val lastRound = params.getValue("last-round").jsonPrimitive.content.toLong()
        buildJsonObject {
            put("genesisId", params.getValue("genesis-id").jsonPrimitive.content)
            put("genesisHash", params.getValue("genesis-hash").jsonPrimitive.content)
            put("firstValid", lastRound)
            put("lastValid", lastRound + VALID_ROUNDS)
        }.toString()
    }

    /** Sends a signed transaction and returns its transaction id. */
    suspend fun sendSignedTransaction(signedTransaction: ByteArray): String = withContext(Dispatchers.IO) {
        val response: HttpResponse = httpClient.post("$nodeBaseUrl/v2/transactions") {
            contentType(ContentType("application", "x-binary"))
            setBody(signedTransaction)
        }
        val body = response.bodyAsText()
        Log.d(TAG, "Send response ${response.status.value}: $body")
        check(response.status.isSuccess()) { "Send failed: ${response.status.value} $body" }
        json.parseToJsonElement(body).jsonObject.getValue("txId").jsonPrimitive.content
    }

    fun close() = httpClient.close()

    companion object {
        private const val TAG = "AlgorandRepository"
        const val TESTNET_NODE = "https://testnet-api.algonode.cloud"
        const val EXPLORER_TX_URL = "https://testnet.explorer.perawallet.app/tx/"
        private const val VALID_ROUNDS = 1000L
        const val MICRO_ALGOS_PER_ALGO = 1_000_000.0
    }
}
