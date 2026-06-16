package com.michaeltchuang.walletsdk.core.railmpp.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Consumer's signed response to a challenge. */
internal data class ChargeCredential(
    val challenge: ChargeChallenge,
    val payload: ChargePayload,
    val source: String? = null,
)

/** Algorand credential payload (`type: "transaction"`). */
internal data class ChargePayload(
    val type: String = "transaction",
    /** Algorand: base64 msgpack of each (signed or unsigned) txn. */
    val paymentGroup: List<String>? = null,
    /** Algorand: index of the actual payment txn. */
    val paymentIndex: Int? = null,
    /** Solana: base64 signed transaction bytes. */
    val signedTransaction: String? = null,
)

internal object ChargeCredentialCodec {
    /**
     * Serialize a credential to the `Authorization` header value.
     * Wire: `Payment <base64url(JCS({ challenge, payload, source? }))>`.
     */
    fun toAuthHeader(credential: ChargeCredential): String {
        val challenge = credential.challenge
        val challengeJson =
            buildJsonObject {
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

        val payloadJson =
            buildJsonObject {
                put("type", credential.payload.type)
                credential.payload.paymentGroup?.let { group ->
                    put("paymentGroup", JsonArray(group.map { JsonPrimitive(it) }))
                }
                credential.payload.paymentIndex?.let { put("paymentIndex", it) }
                credential.payload.signedTransaction?.let { put("signedTransaction", it) }
            }

        val wire =
            buildJsonObject {
                put("challenge", challengeJson)
                put("payload", payloadJson)
                credential.source?.let { put("source", it) }
            }

        return "Payment " + Base64Url.encode(JcsJson.canonicalize(wire))
    }

    /** Parse an `Authorization: Payment <token>` header into a [ChargeCredential]. */
    fun fromAuthHeader(header: String): ChargeCredential {
        val token = AuthParams.stripPaymentPrefix(header)
        val parsed = Json.parseToJsonElement(Base64Url.decodeToString(token)).jsonObject

        val challengeJson = parsed["challenge"]?.jsonObject ?: error("MPP credential missing 'challenge'")
        val challenge =
            ChargeChallenge(
                id = challengeJson.reqString("id"),
                realm = challengeJson.reqString("realm"),
                method = challengeJson.reqString("method"),
                intent = challengeJson.reqString("intent"),
                request = ChargeRequestCodec.deserialize(challengeJson.reqString("request")),
                expires = challengeJson.optString("expires"),
                description = challengeJson.optString("description"),
                digest = challengeJson.optString("digest"),
                opaque = challengeJson.optString("opaque")?.let { ChargeRequestCodec.deserialize(it) },
            )

        val payloadJson = parsed["payload"]?.jsonObject ?: error("MPP credential missing 'payload'")
        val paymentGroup =
            (payloadJson["paymentGroup"] as? JsonArray)?.map { it.jsonPrimitive.content }
        val payment =
            ChargePayload(
                type = payloadJson.optString("type") ?: "transaction",
                paymentGroup = paymentGroup,
                paymentIndex = payloadJson["paymentIndex"]?.jsonPrimitive?.int,
                signedTransaction = payloadJson.optString("signedTransaction"),
            )

        return ChargeCredential(
            challenge = challenge,
            payload = payment,
            source = parsed.optString("source"),
        )
    }
}
