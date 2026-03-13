package com.michaeltchuang.walletsdk.core.account.domain.usecase.local
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
class GetSolanaBalancesUseCase(
    private val httpClient: HttpClient,
) {
    suspend operator fun invoke(
        addresses: List<String>,
        rpcEndpoint: String = SOLANA_DEVNET_RPC,
    ): Map<String, String?> {
        return coroutineScope {
            addresses.map { address ->
                async {
                    address to
                        fetchSolanaBalanceAsDisplayAmount(
                            address = address,
                            rpcEndpoint = rpcEndpoint,
                        )
                }
            }.awaitAll().toMap()
        }
    }
    private suspend fun fetchSolanaBalanceAsDisplayAmount(
        address: String,
        rpcEndpoint: String,
    ): String? {
        return try {
            val requestJson = buildGetBalanceRequestJson(address)
            val response =
                httpClient.post(rpcEndpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }.bodyAsText()
            val lamports = parseLamports(response) ?: return null
            // Convert lamports to "micro-style" amount so existing formatAmount() UI path shows SOL.
            (lamports / LAMPORTS_TO_MICRO_DIVISOR).toString()
        } catch (_: Exception) {
            null
        }
    }
    private fun buildGetBalanceRequestJson(address: String): String {
        val payload =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getBalance")
                putJsonArray("params") {
                    add(JsonPrimitive(address))
                }
            }
        return json.encodeToString(JsonObject.serializer(), payload)
    }
    private fun parseLamports(response: String): Long? {
        return try {
            val jsonElement = json.parseToJsonElement(response) as? JsonObject ?: return null
            if (jsonElement["error"] != null) return null
            jsonElement["result"]
                ?.let { it as? JsonObject }
                ?.get("value")
                ?.jsonPrimitive
                ?.content
                ?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val SOLANA_DEVNET_RPC = "https://api.devnet.solana.com"
        private const val LAMPORTS_TO_MICRO_DIVISOR = 1_000L
    }
}