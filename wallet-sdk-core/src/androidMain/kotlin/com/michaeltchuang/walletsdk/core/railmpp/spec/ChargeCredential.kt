package com.michaeltchuang.walletsdk.core.railmpp.spec

import org.json.JSONArray
import org.json.JSONObject
import xyz.goplausible.webrtcpaymentsdk.railmpp.spec.Base64Url

/**
 * In-memory representation of an MPP credential — the consumer's signed
 * response to a challenge.
 */
internal data class ChargeCredential(
    val challenge: ChargeChallenge,
    val payload: ChargePayload,
    val source: String? = null,
)

/** Algorand-specific credential payload (`type: "transaction"`). */
internal data class ChargePayload(
    /** Always "transaction" for the algorand charge intent. */
    val type: String = "transaction",
    /** Each entry is base64-encoded msgpack of a (signed or unsigned) txn. */
    val paymentGroup: List<String>,
    /** Index of the actual payment txn (recipient + amount). */
    val paymentIndex: Int,
)

internal object ChargeCredentialCodec {
    /**
     * Serialize a credential to the `Authorization` header value.
     *
     * Wire format: `Payment <base64url(JCS({ challenge: { …, request: serialize(req) }, payload, source? }))>`
     *
     * Matches mppx's `Credential.serialize` so the TS server can verify it.
     */
    fun toAuthHeader(credential: ChargeCredential): String {
        val challenge = credential.challenge
        val challengeJson = JSONObject().apply {
            put("id", challenge.id)
            put("realm", challenge.realm)
            put("method", challenge.method)
            put("intent", challenge.intent)
            put("request", ChargeRequestCodec.serialize(challenge.request))
            challenge.expires?.let { put("expires", it) }
            challenge.description?.let { put("description", it) }
            challenge.digest?.let { put("digest", it) }
            challenge.opaque?.let { put("opaque", ChargeRequestCodec.serialize(it)) }
        }

        val payloadJson = JSONObject().apply {
            put("type", credential.payload.type)
            put("paymentGroup", JSONArray(credential.payload.paymentGroup))
            put("paymentIndex", credential.payload.paymentIndex)
        }

        val wire = JSONObject().apply {
            put("challenge", challengeJson)
            put("payload", payloadJson)
            credential.source?.let { put("source", it) }
        }

        val canonical = JcsJson.canonicalize(wire)
        return "Payment " + Base64Url.encode(canonical)
    }

    /** Parse an `Authorization: Payment <token>` header into a [ChargeCredential]. */
    fun fromAuthHeader(header: String): ChargeCredential {
        val token = AuthParams.stripPaymentPrefix(header)
        val json = Base64Url.decodeToString(token)
        val parsed = JSONObject(json)

        val challengeJson = parsed.getJSONObject("challenge")
        val challenge = ChargeChallenge(
            id = challengeJson.getString("id"),
            realm = challengeJson.getString("realm"),
            method = challengeJson.getString("method"),
            intent = challengeJson.getString("intent"),
            request = ChargeRequestCodec.deserialize(challengeJson.getString("request")),
            expires = challengeJson.optString("expires").ifBlank { null },
            description = challengeJson.optString("description").ifBlank { null },
            digest = challengeJson.optString("digest").ifBlank { null },
            opaque = challengeJson.optString("opaque").ifBlank { null }
                ?.let { ChargeRequestCodec.deserialize(it) },
        )

        val payloadJson = parsed.getJSONObject("payload")
        val groupArr = payloadJson.getJSONArray("paymentGroup")
        val payment = ChargePayload(
            type = payloadJson.optString("type", "transaction"),
            paymentGroup = (0 until groupArr.length()).map { groupArr.getString(it) },
            paymentIndex = payloadJson.getInt("paymentIndex"),
        )

        return ChargeCredential(
            challenge = challenge,
            payload = payment,
            source = parsed.optString("source").ifBlank { null },
        )
    }
}
