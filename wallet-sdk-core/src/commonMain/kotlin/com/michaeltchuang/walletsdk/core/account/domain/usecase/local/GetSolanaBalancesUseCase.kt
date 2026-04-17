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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.pow

class GetSolanaBalancesUseCase(
    private val httpClient: HttpClient,
) {
    suspend operator fun invoke(
        addresses: List<String>,
        rpcEndpoint: String = SOLANA_DEVNET_RPC,
    ): Map<String, String?> =
        coroutineScope {
            addresses
                .map { address ->
                    async {
                        address to
                            fetchSolanaBalanceAsDisplayAmount(
                                address = address,
                                rpcEndpoint = rpcEndpoint,
                            )
                    }
                }.awaitAll()
                .toMap()
        }

    suspend fun getUsdcBalances(
        addresses: List<String>,
        rpcEndpoint: String = SOLANA_DEVNET_RPC,
        usdcMintAddress: String = USDC_MINT_DEVNET,
    ): Map<String, String?> =
        coroutineScope {
            addresses
                .map { address ->
                    async {
                        address to
                            fetchUsdcBalanceAsDisplayAmount(
                                address = address,
                                rpcEndpoint = rpcEndpoint,
                                usdcMintAddress = usdcMintAddress,
                            )
                    }
                }.awaitAll()
                .toMap()
        }

    private suspend fun fetchSolanaBalanceAsDisplayAmount(
        address: String,
        rpcEndpoint: String,
    ): String? {
        return try {
            val requestJson = buildGetBalanceRequestJson(address)
            val response =
                httpClient
                    .post(rpcEndpoint) {
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

    private suspend fun fetchUsdcBalanceAsDisplayAmount(
        address: String,
        rpcEndpoint: String,
        usdcMintAddress: String,
    ): String? =
        try {
            val requestJson = buildGetUsdcBalanceRequestJson(address, usdcMintAddress)
            val response =
                httpClient
                    .post(rpcEndpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(requestJson)
                    }.bodyAsText()
            parseUsdcBalance(response)
        } catch (_: Exception) {
            null
        }

    private fun buildGetUsdcBalanceRequestJson(
        address: String,
        usdcMintAddress: String,
    ): String {
        val payload =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByOwner")
                putJsonArray("params") {
                    add(JsonPrimitive(address))
                    add(
                        buildJsonObject {
                            put("mint", usdcMintAddress)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("encoding", "jsonParsed")
                        },
                    )
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

    private fun parseUsdcBalance(response: String): String? {
        return try {
            val jsonElement = json.parseToJsonElement(response) as? JsonObject ?: return null
            if (jsonElement["error"] != null) return null

            val tokenAccounts: JsonArray =
                jsonElement["result"]
                    ?.jsonObject
                    ?.get("value")
                    ?.jsonArray ?: return "0"

            var total = 0.0
            tokenAccounts.forEach { tokenAccount ->
                val tokenAmount =
                    tokenAccount
                        .jsonObject["account"]
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                        ?.get("parsed")
                        ?.jsonObject
                        ?.get("info")
                        ?.jsonObject
                        ?.get("tokenAmount")
                        ?.jsonObject ?: return@forEach

                val uiAmountString = tokenAmount["uiAmountString"]?.jsonPrimitive?.contentOrNull
                if (uiAmountString != null) {
                    total += uiAmountString.toDoubleOrNull() ?: 0.0
                } else {
                    val rawAmount = tokenAmount["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val decimals = tokenAmount["decimals"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    total += rawAmount / 10.0.pow(decimals)
                }
            }
            total.toString()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val SOLANA_DEVNET_RPC = "https://api.devnet.solana.com"
        private const val LAMPORTS_TO_MICRO_DIVISOR = 1_000L
        private const val USDC_MINT_DEVNET = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    }
}
