package com.michaeltchuang.walletsdk.core.solana.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class SolanaTokenAccount(
    val address: String,
    val amount: Long,
    val decimals: Int,
    val tokenProgramId: String,
)

/** Thin JSON-RPC client for the handful of Solana calls the session signer needs. */
class SolanaRpcClient private constructor(
    private val httpClient: HttpClient,
    private val timeSource: TimeSource,
) {
    constructor(httpClient: HttpClient) : this(httpClient, TimeSource.Monotonic)

    private class EndpointState {
        val mutex = Mutex()
        var cooldownUntil: TimeMark? = null
        var nextRequestAt: TimeMark? = null
    }

    private val endpointsMutex = Mutex()
    private val endpoints = mutableMapOf<String, EndpointState>()

    suspend fun getLatestBlockhash(network: String): String {
        val result =
            call(network, "getLatestBlockhash", buildJsonArray { add(commitment()) })
        return result.jsonObject["value"]!!
            .jsonObject["blockhash"]!!
            .jsonPrimitive.content
    }

    /** Whether [blockhash] can still be used to land a transaction (`processed` = most recent view). */
    suspend fun isBlockhashValid(
        network: String,
        blockhash: String,
    ): Boolean {
        val result =
            call(
                network,
                "isBlockhashValid",
                buildJsonArray {
                    add(JsonPrimitive(blockhash))
                    add(buildJsonObject { put("commitment", "processed") })
                },
            )
        return result.jsonObject["value"]!!.jsonPrimitive.boolean
    }

    suspend fun getBalance(
        network: String,
        address: String,
    ): Long {
        val result =
            call(
                network,
                "getBalance",
                buildJsonArray {
                    add(JsonPrimitive(address))
                    add(commitment())
                },
            )
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
    ): ByteArray? = getAccountInfo(network, address)?.data

    data class AccountInfo(
        val owner: String,
        val data: ByteArray,
    )

    suspend fun getAccountInfo(
        network: String,
        address: String,
    ): AccountInfo? {
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
        val value = result.jsonObject["value"] ?: error("Solana getAccountInfo returned no value")
        if (value is JsonNull) return null
        val data =
            value.jsonObject["data"]!!
                .jsonArray[0]
                .jsonPrimitive.content
        return AccountInfo(value.jsonObject["owner"]!!.jsonPrimitive.content, Base64.decode(data))
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
            val data =
                obj["account"]!!
                    .jsonObject["data"]!!
                    .jsonArray[0]
                    .jsonPrimitive.content
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
        val confirmed =
            withTimeoutOrNull(timeoutMillis) {
                var confirmed = false
                while (!confirmed) {
                    val result =
                        call(
                            network,
                            "getSignatureStatuses",
                            buildJsonArray {
                                add(buildJsonArray { add(JsonPrimitive(signature)) })
                                add(buildJsonObject { put("searchTransactionHistory", true) })
                            },
                        )
                    val status = result.jsonObject["value"]!!.jsonArray.first()
                    if (status !is JsonNull) {
                        val obj = status.jsonObject
                        obj["err"]?.takeIf { it !is JsonNull }?.let {
                            throw IllegalStateException("Solana transaction $signature failed: $it")
                        }
                        val level = obj["confirmationStatus"]?.jsonPrimitive?.contentOrNull
                        confirmed = level == "confirmed" || level == "finalized"
                    }
                    if (!confirmed) delay(pollMillis)
                }
                true
            }
        if (confirmed != true) {
            throw IllegalStateException("Timed out waiting for Solana transaction $signature")
        }
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
        val endpoint = rpcEndpoint(network)
        val state = endpointsMutex.withLock { endpoints.getOrPut(endpoint) { EndpointState() } }
        return state.mutex.withLock {
            repeat(MAX_RETRIES + 1) { attempt ->
                val remaining =
                    maxOf(
                        state.cooldownUntil?.let { -it.elapsedNow() } ?: Duration.ZERO,
                        state.nextRequestAt?.let { -it.elapsedNow() } ?: Duration.ZERO,
                    )
                // One cancellable wait, using the same monotonic source as the deadlines.
                if (remaining > Duration.ZERO) delay(remaining)
                state.cooldownUntil = null
                // Conservative pacing (<40 starts/10s), not a quota guarantee: providers vary.
                state.nextRequestAt = timeSource.markNow() + 300.milliseconds
                val response =
                    httpClient.post(endpoint) {
                        // Inspect HTTP 429 ourselves even when the supplied client throws on errors.
                        expectSuccess = false
                        contentType(ContentType.Application.Json)
                        setBody(payload.toString())
                    }
                val body = response.bodyAsText()
                val httpRateLimited = response.status == HttpStatusCode.TooManyRequests
                if (!httpRateLimited && !response.status.isSuccess()) {
                    error("Solana RPC $method HTTP error: ${response.status}")
                }
                // HTTP 429 often comes from a proxy and need not contain JSON.
                val json = if (httpRateLimited) null else Json.parseToJsonElement(body).jsonObject
                val rpcError = json?.get("error")?.takeUnless { it is JsonNull }
                val rpcCode = ((rpcError as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
                if (httpRateLimited || rpcCode == 429) {
                    // Honor delta-seconds; dates/invalid headers use bounded exponential backoff.
                    val retryAfter =
                        response.headers[HttpHeaders.RetryAfter]
                            ?.trim()
                            ?.toLongOrNull()
                            ?.takeIf { it >= 0 }
                            ?.coerceAtMost(30)
                            ?.seconds
                            ?: (1L shl attempt.coerceAtMost(2)).seconds
                    // Keep this deadline even on exhaustion, cancellation, or a non-replayable write.
                    state.cooldownUntil = timeSource.markNow() + retryAfter
                    if (method in READ_METHODS && attempt < MAX_RETRIES) return@repeat
                    if (httpRateLimited) error("Solana RPC $method HTTP error: ${response.status}")
                    error("Solana RPC $method error: $rpcError")
                }
                if (rpcError != null) error("Solana RPC $method error: $rpcError")
                return@withLock json?.get("result")
                    ?: error("Solana RPC $method returned no result")
            }
            error("Solana RPC $method retries exhausted")
        }
    }

    private fun commitment(): JsonObject = buildJsonObject { put("commitment", "confirmed") }

    companion object {
        // Keep the public one-argument constructor unambiguous for singleOf(::SolanaRpcClient).
        internal fun withTimeSource(
            httpClient: HttpClient,
            timeSource: TimeSource,
        ): SolanaRpcClient = SolanaRpcClient(httpClient, timeSource)

        private const val MAX_RETRIES = 3
        private val READ_METHODS =
            setOf(
                "getLatestBlockhash",
                "isBlockhashValid",
                "getBalance",
                "getTokenAccountsByOwner",
                "getAccountInfo",
                "getProgramAccounts",
                "getSignatureStatuses",
            )

        /** Accepts MPP CAIP-style ids (`solana:devnet`) as well as bare cluster names. */
        fun rpcEndpoint(network: String): String =
            when {
                network.contains("mainnet", ignoreCase = true) -> "https://api.mainnet-beta.solana.com"
                network.contains("testnet", ignoreCase = true) -> "https://api.testnet.solana.com"
                else -> "https://api.devnet.solana.com"
            }
    }
}
