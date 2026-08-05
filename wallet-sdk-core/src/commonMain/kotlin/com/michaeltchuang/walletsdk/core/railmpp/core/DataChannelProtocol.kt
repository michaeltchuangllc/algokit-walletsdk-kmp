package com.michaeltchuang.walletsdk.core.railmpp.core

import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.RailPayment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** DataChannel label the SDK uses for payment signaling. */
const val PAYMENT_CHANNEL_LABEL = "x402-payment-channel"

/** JSON field keys used in DataChannel envelope messages. */
internal object DCFieldKey {
    const val TYPE = "type"
    const val SESSION_ID = "sessionId"
    const val SEGMENT_INDEX = "segmentIndex"
    const val PAYLOAD = "payload"
}

private val encodeJson =
    Json {
        encodeDefaults = false
        explicitNulls = false
    }

private val decodeJson =
    Json {
        ignoreUnknownKeys = true
    }

/** Serialize a PaymentRequest to a JSON object for wire transport. */
internal fun PaymentRequest.toJson(): JsonObject = encodeJson.encodeToJsonElement(this).jsonObject

/** Parse a PaymentRequest from JSON. */
internal fun paymentRequestFromJson(json: JsonObject): PaymentRequest = decodeJson.decodeFromJsonElement(json)

/** Serialize a RailPayment to JSON. */
internal fun RailPayment.toJson(): JsonObject = encodeJson.encodeToJsonElement(this).jsonObject

/** Parse a RailPayment from JSON (server-side receives this from the consumer). */
internal fun railPaymentFromJson(json: JsonObject): RailPayment = decodeJson.decodeFromJsonElement(json)

/** Serialize a PaymentReceipt to JSON. */
internal fun PaymentReceipt.toJson(): JsonObject = encodeJson.encodeToJsonElement(this).jsonObject
