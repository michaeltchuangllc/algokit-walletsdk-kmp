package com.michaeltchuang.walletsdk.core.railmpp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared, cross-platform DataChannel message protocol.
 *
 * Android and iOS each have their own native WebRTC *transport* (Kotlin `RtcDataChannel`
 * on Android, native Swift on iOS), but the *wire protocol* is the same logical thing on
 * both. Historically each side invented its own payment-request envelope:
 *
 *  - **iOS host** (`LiquidAuthConnectionManager`): a `reference`-based envelope
 *    `{"reference":"liquid:payment:request","id":..,"amount":..,"payTo":..,"meta":{..}}`
 *  - **Android host** (`PaywalledRTCServer`): a `type`-based envelope
 *    `{"type":"segment:request","sessionId":..,"segmentIndex":..,"payload":{..}}`
 *
 * Because the two formats differed, an Android viewer could not understand an iOS host
 * (and vice-versa) without bespoke, duplicated parsing on each platform.
 *
 * This object is the single shared codec both platforms call to **normalise either
 * envelope into one canonical [PaymentRequestEnvelope]** and to **rebuild** the canonical
 * `liquid:payment:request` envelope. It is intentionally backward-compatible: it reads
 * both legacy formats so already-deployed peers keep working.
 */
object LiquidDcMessages {
    // ── reference-based envelopes (iOS host + Liquid Auth) ──────────────────────
    const val REF_PAYMENT_REQUEST = "liquid:payment:request"
    const val REF_VIDEO_FRAME = "liquid:video:frame"
    const val REF_VIEWER_HELLO = "liquid:viewer:hello"
    const val REF_PAYMENT_BALANCE = "liquid:payment:balance"
    const val REF_PAYMENT_VOUCHER = "liquid:payment:voucher"
    const val REF_PAYMENT_DEPLETED = "liquid:payment:depleted"
    const val REF_PING = "ping"
    const val REF_PONG = "pong"

    /** Default Algorand network used when the wire message omits an explicit `network`. */
    const val DEFAULT_NETWORK = "algorand-testnet"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Canonical, platform-neutral payment request parsed from either wire envelope.
     *
     * [sessionId]/[segmentIndex] are only present for the Android `segment:request`
     * envelope; iOS hosts (single request, no per-segment protocol) leave them null.
     */
    data class PaymentRequestEnvelope(
        val id: String,
        val amount: String,
        val asset: String,
        val network: String,
        val payTo: String,
        val nonce: String,
        val sessionId: String? = null,
        val segmentIndex: Int? = null,
        val gatingMode: String? = null,
        val segmentDuration: Int? = null,
    )

    /** Returns the `reference` of a reference-based message, or null. */
    fun referenceOf(raw: String): String? = parseObjectOrNull(raw)?.str("reference")

    /** Returns the `type` of an Android type-based message, or null. */
    fun typeOf(raw: String): String? = parseObjectOrNull(raw)?.str("type")

    /** True if [raw] is a payment request in either the iOS or Android envelope. */
    fun isPaymentRequest(raw: String): Boolean {
        val obj = parseObjectOrNull(raw) ?: return false
        return obj.str("reference") == REF_PAYMENT_REQUEST ||
            obj.str("type") == DCMessageType.SEGMENT_REQUEST
    }

    /**
     * Normalises a payment request from EITHER:
     *  - iOS host:     `{"reference":"liquid:payment:request","id":..,"amount":..,"payTo":..,"meta":{..}}`
     *  - Android host: `{"type":"segment:request","sessionId":..,"segmentIndex":..,"payload":{..}}`
     *
     * Returns null if [raw] is not a payment request.
     */
    fun parsePaymentRequest(raw: String): PaymentRequestEnvelope? {
        val obj = parseObjectOrNull(raw) ?: return null

        return when {
            obj.str("reference") == REF_PAYMENT_REQUEST -> {
                val meta = obj["meta"] as? JsonObject
                PaymentRequestEnvelope(
                    id = obj.str("id").orEmpty(),
                    amount = obj.str("amount").orEmpty(),
                    asset = obj.str("asset").orEmpty(),
                    network = obj.str("network") ?: DEFAULT_NETWORK,
                    payTo = obj.str("payTo").orEmpty(),
                    nonce = obj.str("nonce").orEmpty(),
                    sessionId = obj.str("sessionId") ?: obj.str("id"),
                    segmentIndex = obj.intOrNull("segmentIndex"),
                    gatingMode = meta?.str("gatingMode"),
                    segmentDuration = meta?.intOrNull("segmentDuration"),
                )
            }

            obj.str("type") == DCMessageType.SEGMENT_REQUEST -> {
                val payload = obj["payload"] as? JsonObject
                val meta = payload?.get("meta") as? JsonObject
                // Prefer payload fields, fall back to the top-level envelope.
                fun field(key: String): String? = payload?.str(key) ?: obj.str(key)
                PaymentRequestEnvelope(
                    id = obj.str("sessionId") ?: field("id").orEmpty(),
                    amount = field("amount").orEmpty(),
                    asset = field("asset").orEmpty(),
                    network = field("network") ?: DEFAULT_NETWORK,
                    payTo = field("payTo").orEmpty(),
                    nonce = field("nonce").orEmpty(),
                    sessionId = obj.str("sessionId"),
                    segmentIndex = obj.intOrNull("segmentIndex") ?: payload?.intOrNull("segmentIndex"),
                    gatingMode = meta?.str("gatingMode"),
                    segmentDuration = meta?.intOrNull("segmentDuration"),
                )
            }

            else -> null
        }
    }

    /**
     * Builds the canonical, cross-platform `liquid:payment:request` envelope (the format the
     * iOS host emits and both viewers understand). Use this to re-emit a normalised request.
     */
    fun buildPaymentRequestJson(req: PaymentRequestEnvelope): String =
        buildString {
            append("""{"reference":"$REF_PAYMENT_REQUEST"""")
            append(""","id":"${req.id}"""")
            append(""","amount":"${req.amount}"""")
            append(""","asset":"${req.asset}"""")
            append(""","network":"${req.network}"""")
            append(""","payTo":"${req.payTo}"""")
            append(""","nonce":"${req.nonce}"""")
            if (req.gatingMode != null || req.segmentDuration != null) {
                append(""","meta":{""")
                val parts = mutableListOf<String>()
                req.gatingMode?.let { parts.add(""""gatingMode":"$it"""") }
                req.segmentDuration?.let { parts.add(""""segmentDuration":$it""") }
                append(parts.joinToString(","))
                append("}")
            }
            append("}")
        }

    // ── private helpers ─────────────────────────────────────────────────────────

    private fun parseObjectOrNull(raw: String): JsonObject? =
        runCatching {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return null
            json.parseToJsonElement(trimmed).jsonObject
        }.getOrNull()

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}
