package com.michaeltchuang.walletsdk.core.railmpp.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Algorand charge receipt returned in the `Payment-Receipt` header on settlement.
 * Wire: `Payment <base64url(JCS(receipt))>`.
 */
internal data class ChargeReceipt(
    val method: String = "algorand",
    /** 52-char base32 Algorand TxID — the proof of payment. */
    val reference: String,
    val status: String = "success",
    /** RFC 3339 timestamp. */
    val timestamp: String = nowRfc3339(),
)

internal object ChargeReceiptCodec {
    fun toHeader(receipt: ChargeReceipt): String {
        val json =
            buildJsonObject {
                put("method", receipt.method)
                put("reference", receipt.reference)
                put("status", receipt.status)
                put("timestamp", receipt.timestamp)
            }
        return "Payment " + Base64Url.encode(JcsJson.canonicalize(json))
    }

    fun fromHeader(header: String): ChargeReceipt {
        val token = AuthParams.stripPaymentPrefix(header)
        val parsed = Json.parseToJsonElement(Base64Url.decodeToString(token)).jsonObject
        return ChargeReceipt(
            method = parsed.optString("method") ?: "algorand",
            reference = parsed.reqString("reference"),
            status = parsed.optString("status") ?: "success",
            timestamp = parsed.optString("timestamp") ?: nowRfc3339(),
        )
    }
}

@OptIn(ExperimentalTime::class)
private fun nowRfc3339(): String = Instant.fromEpochSeconds(Clock.System.now().epochSeconds).toString()
