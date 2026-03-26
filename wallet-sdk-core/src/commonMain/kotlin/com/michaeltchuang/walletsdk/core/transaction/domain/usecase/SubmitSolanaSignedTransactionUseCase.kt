package com.michaeltchuang.walletsdk.core.transaction.domain.usecase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SubmitSolanaSignedTransactionUseCase(
    private val httpClient: HttpClient,
) {
    @OptIn(ExperimentalEncodingApi::class)
    suspend operator fun invoke(
        signedTransaction: ByteArray,
        network: String = "devnet",
    ): String {
        val rpcEndpoint =
            when (network.lowercase()) {
                "mainnet", "mainnet-beta" -> SOLANA_MAINNET_RPC
                "devnet" -> SOLANA_DEVNET_RPC
                "testnet" -> SOLANA_TESTNET_RPC
                else -> SOLANA_DEVNET_RPC
            }

        val rpcPayload =
            JsonObject(
                mapOf(
                    "jsonrpc" to JsonPrimitive("2.0"),
                    "id" to JsonPrimitive(1),
                    "method" to JsonPrimitive("sendTransaction"),
                    "params" to
                        JsonArray(
                            listOf(
                                JsonPrimitive(Base64.encode(signedTransaction)),
                                JsonObject(
                                    mapOf(
                                        "encoding" to JsonPrimitive("base64"),
                                        "skipPreflight" to JsonPrimitive(false),
                                        "preflightCommitment" to JsonPrimitive("confirmed"),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val responseText =
            httpClient
                .post(rpcEndpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(rpcPayload.toString())
                }.body<String>()

        val json =
            kotlinx.serialization.json.Json
                .parseToJsonElement(responseText)
                .jsonObject
        val rpcError = json["error"]?.toString()
        if (rpcError != null) {
            throw IllegalStateException("Solana RPC error: $rpcError")
        }

        return json["result"]?.toString()?.trim('"')
            ?: throw IllegalStateException("Missing Solana transaction signature in RPC response")
    }

    companion object {
        private const val SOLANA_MAINNET_RPC = "https://api.mainnet-beta.solana.com"
        private const val SOLANA_DEVNET_RPC = "https://api.devnet.solana.com"
        private const val SOLANA_TESTNET_RPC = "https://api.testnet.solana.com"
    }
}
