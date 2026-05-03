package com.michaeltchuang.walletsdk.core.railmpp.core

import org.json.JSONObject

/**
 * DataChannel message types exchanged between provider and consumer.
 */
object DCMessageType {
    const val SEGMENT_REQUEST = "segment:request"
    const val SEGMENT_PAYMENT = "segment:payment"
    const val SEGMENT_ACCEPTED = "segment:accepted"
    const val SEGMENT_REJECTED = "segment:rejected"
    const val SEGMENT_KEY = "segment:key"
    const val SESSION_TERMINATE = "session:terminate"
}

/**
 * The DataChannel label the SDK uses for payment signaling.
 * Provider creates a channel with this label; consumer filters for it in onDataChannel.
 */
const val PAYMENT_CHANNEL_LABEL = "x402-payment-channel"

/**
 * Serialize a PaymentRequest to JSON (for wire transport).
 */
internal fun PaymentRequest.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("segmentIndex", segmentIndex)
        put("amount", amount)
        put("asset", asset)
        put("network", network)
        put("payTo", payTo)
        put("ttl", ttl)
        put("nonce", nonce)
        put(
            "meta",
            JSONObject().apply {
                put("gatingMode", meta.gatingMode.value)
                put("enforcement", meta.enforcement.value)
                meta.segmentDuration?.let { put("segmentDuration", it) }
                meta.segmentBytes?.let { put("segmentBytes", it) }
                meta.viewerAddress?.let { put("viewerAddress", it) }
                meta.voucherSignature?.let { put("voucherSignature", it) }
            },
        )
        railPayload?.let {
            if (it is JSONObject) put("railPayload", it) else put("railPayload", it.toString())
        }
    }

/**
 * Parse a PaymentRequest from JSON.
 */
internal fun paymentRequestFromJson(json: JSONObject): PaymentRequest {
    val metaJson = json.getJSONObject("meta")
    val meta =
        PaymentRequestMeta(
            gatingMode = GatingMode.fromString(metaJson.getString("gatingMode")),
            enforcement =
                if (metaJson.getString("enforcement") == "crypto") {
                    EnforcementMode.CRYPTO
                } else {
                    EnforcementMode.TRACK
                },
            segmentDuration = if (metaJson.has("segmentDuration")) metaJson.getInt("segmentDuration") else null,
            segmentBytes = if (metaJson.has("segmentBytes")) metaJson.getLong("segmentBytes") else null,
            viewerAddress = if (metaJson.has("viewerAddress")) metaJson.getString("viewerAddress") else null,
            voucherSignature = if (metaJson.has("voucherSignature")) metaJson.getString("voucherSignature") else null,
        )
    return PaymentRequest(
        id = json.getString("id"),
        sessionId = json.getString("sessionId"),
        segmentIndex = json.getInt("segmentIndex"),
        amount = json.getString("amount"),
        asset = json.getString("asset"),
        network = json.getString("network"),
        payTo = json.getString("payTo"),
        ttl = json.getInt("ttl"),
        nonce = json.getString("nonce"),
        meta = meta,
        railPayload = if (json.has("railPayload")) json.opt("railPayload") else null,
    )
}

/**
 * Serialize a RailPayment to JSON.
 */
internal fun RailPayment.toJson(): JSONObject =
    JSONObject().apply {
        put("railId", railId)
        put("version", version)
        put("nonce", nonce)
        put("paymentPayload", paymentPayload)
        put("paymentRequirements", paymentRequirements)
    }

/**
 * Parse a RailPayment from JSON (server-side receives this from consumer).
 */
internal fun railPaymentFromJson(json: JSONObject): RailPayment =
    RailPayment(
        railId = json.getString("railId"),
        version = json.getInt("version"),
        nonce = json.getString("nonce"),
        paymentPayload = json.get("paymentPayload"),
        paymentRequirements = json.get("paymentRequirements"),
    )

/**
 * Serialize a PaymentReceipt to JSON.
 */
internal fun PaymentReceipt.toJson(): JSONObject =
    JSONObject().apply {
        put("txId", txId)
        put("sessionId", sessionId)
        put("segmentIndex", segmentIndex)
        put("amount", amount)
        put("asset", asset)
        put("payTo", payTo)
        if (payFrom.isNotEmpty()) put("payFrom", payFrom)
        feePayer?.let { put("feePayer", it) }
        facilitator?.let { put("facilitator", it) }
        put("network", network)
        put("timestamp", timestamp)
    }
