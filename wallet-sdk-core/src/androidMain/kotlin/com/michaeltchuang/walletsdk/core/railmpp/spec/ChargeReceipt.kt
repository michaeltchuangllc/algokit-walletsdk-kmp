package com.michaeltchuang.walletsdk.core.railmpp.spec

import org.json.JSONObject
import xyz.goplausible.webrtcpaymentsdk.railmpp.spec.Base64Url
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Algorand charge receipt — returned in `Payment-Receipt` HTTP header on
 * successful settlement. Wire format: `Payment <base64url(JCS(receipt))>`.
 */
internal data class ChargeReceipt(
    val method: String = "algorand",
    /** 52-character base32 Algorand TxID — the only proof of payment. */
    val reference: String,
    val status: String = "success",
    /** RFC 3339 timestamp. */
    val timestamp: String = nowRfc3339(),
)

internal object ChargeReceiptCodec {
    fun toHeader(receipt: ChargeReceipt): String {
        val json =
            JSONObject().apply {
                put("method", receipt.method)
                put("reference", receipt.reference)
                put("status", receipt.status)
                put("timestamp", receipt.timestamp)
            }
        val canonical = JcsJson.canonicalize(json)
        return "Payment " + Base64Url.encode(canonical)
    }

    fun fromHeader(header: String): ChargeReceipt {
        val token = AuthParams.stripPaymentPrefix(header)
        val json = Base64Url.decodeToString(token)
        val parsed = JSONObject(json)
        return ChargeReceipt(
            method = parsed.optString("method", "algorand"),
            reference = parsed.getString("reference"),
            status = parsed.optString("status", "success"),
            timestamp = parsed.optString("timestamp").ifBlank { nowRfc3339() },
        )
    }
}

private fun nowRfc3339(): String {
    val sdf =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    return sdf.format(Date())
}
