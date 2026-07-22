package com.michaeltchuang.walletsdk.core.railmpp.core

import com.michaeltchuang.walletsdk.core.railmpp.domain.model.EnforcementMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequestMeta
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.RailPayment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** DataChannel message types exchanged between provider and consumer. */
object DCMessageType {
    const val SEGMENT_REQUEST = "segment:request"
    const val SEGMENT_PAYMENT = "segment:payment"
    const val SEGMENT_ACCEPTED = "segment:accepted"
    const val SEGMENT_REJECTED = "segment:rejected"
    const val SESSION_TERMINATE = "session:terminate"

    /** Sent by the viewer after a session-vault top-up so the server re-issues the pending request. */
    const val VIEWER_VAULT_FUNDED = "viewer:vault:funded"

    const val SEGMENT_HANDSHAKE = "segment:handshake"
    const val SEGMENT_VOUCHER = "segment:voucher"
}

/** DataChannel label the SDK uses for payment signaling. */
const val PAYMENT_CHANNEL_LABEL = "x402-payment-channel"

/** Serialize a PaymentRequest to a JSON object for wire transport. */
internal fun PaymentRequest.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("sessionId", sessionId)
        put("segmentIndex", segmentIndex)
        put("amount", amount)
        put("asset", asset)
        put("network", network)
        put("payTo", payTo)
        put("ttl", ttl)
        put("nonce", nonce)
        channelId?.let { put("channelId", it) }
        salt?.let { put("salt", it) }
        put(
            "meta",
            buildJsonObject {
                put("gatingMode", meta.gatingMode.value)
                put("enforcement", meta.enforcement.value)
                meta.segmentDuration?.let { put("segmentDuration", it) }
                meta.segmentBytes?.let { put("segmentBytes", it) }
                meta.viewerAddress?.let { put("viewerAddress", it) }
                meta.voucherSignature?.let { put("voucherSignature", it) }
            },
        )
        when (val payload = railPayload) {
            null -> Unit
            is JsonElement -> put("railPayload", payload)
            else -> put("railPayload", payload.toString())
        }
    }

/** Parse a PaymentRequest from JSON. */
internal fun paymentRequestFromJson(json: JsonObject): PaymentRequest {
    val metaJson = json["meta"]!!.jsonObject
    val meta =
        PaymentRequestMeta(
            gatingMode = GatingMode.fromString(metaJson["gatingMode"]!!.jsonPrimitive.content),
            enforcement =
                if (metaJson["enforcement"]?.jsonPrimitive?.content == "crypto") {
                    EnforcementMode.CRYPTO
                } else {
                    EnforcementMode.TRACK
                },
            segmentDuration = metaJson["segmentDuration"]?.jsonPrimitive?.int,
            segmentBytes = metaJson["segmentBytes"]?.jsonPrimitive?.long,
            viewerAddress = metaJson["viewerAddress"]?.jsonPrimitive?.contentOrNull,
            voucherSignature = metaJson["voucherSignature"]?.jsonPrimitive?.contentOrNull,
        )
    return PaymentRequest(
        id = json["id"]!!.jsonPrimitive.content,
        sessionId = json["sessionId"]!!.jsonPrimitive.content,
        segmentIndex = json["segmentIndex"]!!.jsonPrimitive.int,
        amount = json["amount"]!!.jsonPrimitive.content,
        asset = json["asset"]!!.jsonPrimitive.content,
        network = json["network"]!!.jsonPrimitive.content,
        payTo = json["payTo"]!!.jsonPrimitive.content,
        ttl = json["ttl"]!!.jsonPrimitive.int,
        nonce = json["nonce"]!!.jsonPrimitive.content,
        meta = meta,
        railPayload = json["railPayload"],
        channelId = json["channelId"]?.jsonPrimitive?.contentOrNull,
        salt = json["salt"]?.jsonPrimitive?.contentOrNull,
    )
}

/** Serialize a RailPayment to JSON. */
internal fun RailPayment.toJson(): JsonObject =
    buildJsonObject {
        put("railId", railId)
        put("version", version)
        put("nonce", nonce)
        put("paymentPayload", paymentPayload.asJsonElement())
        put("paymentRequirements", paymentRequirements.asJsonElement())
    }

/** Parse a RailPayment from JSON (server-side receives this from the consumer). */
internal fun railPaymentFromJson(json: JsonObject): RailPayment =
    RailPayment(
        railId = json["railId"]!!.jsonPrimitive.content,
        version = json["version"]!!.jsonPrimitive.int,
        nonce = json["nonce"]!!.jsonPrimitive.content,
        paymentPayload = json["paymentPayload"]!!,
        paymentRequirements = json["paymentRequirements"]!!,
    )

/** Serialize a PaymentReceipt to JSON. */
internal fun PaymentReceipt.toJson(): JsonObject =
    buildJsonObject {
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
        channelId?.let { put("channelId", it) }
    }

/** Coerces an `Any` rail payload (kotlinx [JsonElement] in practice) to a [JsonElement]. */
private fun Any?.asJsonElement(): JsonElement =
    this as? JsonElement
        ?: error("RailPayment payload must be a kotlinx JsonElement, was ${this?.let { it::class.simpleName }}")
