package com.michaeltchuang.walletsdk.core.solana.data

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

data class SolanaTokenAccount(
    val address: String,
    val amount: Long,
    val decimals: Int,
    val tokenProgramId: String,
)

/** Thin JSON-RPC client for the handful of Solana calls the session signer needs. */
class SolanaRpcClient(
    private val httpClient: HttpClient,
) {
    suspend fun getLatestBlockhash(network: String): String {
        val result =
            call(network, "getLatestBlockhash", buildJsonArray { add(commitment()) })
        return result.jsonObject["value"]!!.jsonObject["blockhash"]!!.jsonPrimitive.content
    }

    suspend fun getBalance(
        network: String,
        address: String,
    ): Long {
        val result = call(network, "getBalance", buildJsonArray { add(JsonPrimitive(address)); add(commitment()) })
        return result.jsonObject["value"]!!.jsonPrimitive.long
    }

    /** Returns the owner's token accounts for [mint], largest balance first. */
    suspend fun getTokenAccounts(
        network: String,
        owner: String,
        mint: String,
    ): List<SolanaTokenAccount> {
        val result =
            call(
                network,
                "getTokenAccountsByOwner",
                buildJsonArray {
                    add(JsonPrimitive(owner))
                    add(buildJsonObject { put("mint", mint) })
                    add(
                        buildJsonObject {
                            put("encoding", "jsonParsed")
                            put("commitment", "confirmed")
                        },
                    )
                },
            )
        return result.jsonObject["value"]!!
            .jsonArray
            .mapNotNull { entry ->
                val obj = entry.jsonObject
                val account = obj["account"]?.jsonObject ?: return@mapNotNull null
                val info =
                    account["data"]
                        ?.jsonObject
                        ?.get("parsed")
                        ?.jsonObject
                        ?.get("info")
                        ?.jsonObject ?: return@mapNotNull null
                val tokenAmount = info["tokenAmount"]?.jsonObject ?: return@mapNotNull null
                SolanaTokenAccount(
                    address = obj["pubkey"]!!.jsonPrimitive.content,
                    amount = tokenAmount["amount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    decimals = tokenAmount["decimals"]?.jsonPrimitive?.int ?: 0,
                    tokenProgramId = account["owner"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.sortedByDescending { it.amount }
    }

    /** Raw account data (base64-decoded), or null if the account doesn't exist. */
    suspend fun getAccountData(
        network: String,
        address: String,
    ): ByteArray? {
        val result =
            call(
                network,
                "getAccountInfo",
                buildJsonArray {
                    add(JsonPrimitive(address))
                    add(
                        buildJsonObject {
                            put("encoding", "base64")
                            put("commitment", "confirmed")
                        },
                    )
                },
            )
        val value = result.jsonObject["value"]
        if (value == null || value is JsonNull) return null
        val data = value.jsonObject["data"]!!.jsonArray[0].jsonPrimitive.content
        return Base64.decode(data)
    }

    /**
     * `getProgramAccounts` filtered by account size and `memcmp` (offset -> Base58 bytes).
     * Returns address -> raw data.
     */
    suspend fun getProgramAccounts(
        network: String,
        programId: String,
        dataSize: Int,
        memcmp: Map<Int, String>,
    ): Map<String, ByteArray> {
        val result =
            call(
                network,
                "getProgramAccounts",
                buildJsonArray {
                    add(JsonPrimitive(programId))
                    add(
                        buildJsonObject {
                            put("encoding", "base64")
                            put("commitment", "confirmed")
                            put(
                                "filters",
                                buildJsonArray {
                                    add(buildJsonObject { put("dataSize", dataSize) })
                                    memcmp.forEach { (offset, bytes) ->
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "memcmp",
                                                    buildJsonObject {
                                                        put("offset", offset)
                                                        put("bytes", bytes)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        return result.jsonArray.associate { entry ->
            val obj = entry.jsonObject
            val data = obj["account"]!!.jsonObject["data"]!!.jsonArray[0].jsonPrimitive.content
            obj["pubkey"]!!.jsonPrimitive.content to Base64.decode(data)
        }
    }

    /** Submits a fully signed wire-format transaction; returns its signature. */
    suspend fun sendTransaction(
        network: String,
        signedTransaction: ByteArray,
    ): String {
        val result =
            call(
                network,
                "sendTransaction",
                buildJsonArray {
                    add(JsonPrimitive(Base64.encode(signedTransaction)))
                    add(
                        buildJsonObject {
                            put("encoding", "base64")
                            put("preflightCommitment", "confirmed")
                        },
                    )
                },
            )
        return result.jsonPrimitive.content
    }

    /**
     * Polls until [signature] reaches `confirmed`. Throws with the on-chain error if it failed,
     * or after [timeoutMillis].
     */
    suspend fun confirmTransaction(
        network: String,
        signature: String,
        timeoutMillis: Long = 60_000,
        pollMillis: Long = 1_000,
    ) {
        var waited = 0L
        while (waited <= timeoutMillis) {
            val result =
                call(
                    network,
                    "getSignatureStatuses",
                    buildJsonArray {
                        add(buildJsonArray { add(JsonPrimitive(signature)) })
                        add(buildJsonObject { put("searchTransactionHistory", true) })
                    },
                )
            val status = result.jsonObject["value"]?.jsonArray?.firstOrNull()
            if (status != null && status !is JsonNull) {
                val obj = status.jsonObject
                obj["err"]?.takeIf { it !is JsonNull }?.let {
                    throw IllegalStateException("Solana transaction $signature failed: $it")
                }
                val level = obj["confirmationStatus"]?.jsonPrimitive?.contentOrNull
                if (level == "confirmed" || level == "finalized") return
            }
            delay(pollMillis)
            waited += pollMillis
        }
        throw IllegalStateException("Timed out waiting for Solana transaction $signature")
    }

    private suspend fun call(
        network: String,
        method: String,
        params: JsonArray,
    ): JsonElement {
        val payload =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", method)
                put("params", params)
            }
        val response =
            httpClient
                .post(rpcEndpoint(network)) {
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }.bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        json["error"]?.let { throw IllegalStateException("Solana RPC $method error: $it") }
        return json["result"] ?: throw IllegalStateException("Solana RPC $method returned no result")
    }

    private fun commitment(): JsonObject = buildJsonObject { put("commitment", "confirmed") }

    companion object {
        /** Accepts MPP CAIP-style ids (`solana:devnet`) as well as bare cluster names. */
        fun rpcEndpoint(network: String): String =
            when {
                network.contains("mainnet", ignoreCase = true) -> "https://api.mainnet-beta.solana.com"
                network.contains("testnet", ignoreCase = true) -> "https://api.testnet.solana.com"
                else -> "https://api.devnet.solana.com"
            }
    }
}
