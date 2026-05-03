package com.michaeltchuang.walletsdk.core.railmpp.spec

import org.json.JSONObject
import xyz.goplausible.webrtcpaymentsdk.railmpp.spec.Base64Url
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory representation of an MPP charge challenge.
 *
 * Mirrors the wire format of mppx's `Challenge` so the Kotlin provider can
 * interoperate with web-side consumers (and vice versa).
 */
internal data class ChargeChallenge(
    val id: String,
    val realm: String,
    val method: String,
    val intent: String,
    /** The full request object — see [ChargeRequest] for the well-known shape. */
    val request: JSONObject,
    val expires: String? = null,
    val description: String? = null,
    val digest: String? = null,
    /** Pass-through opaque map; preserved on both sides for free. */
    val opaque: JSONObject? = null,
)

/**
 * Convenience accessor for the algorand charge fields under `request.methodDetails`.
 */
internal data class ChargeRequest(
    val amount: String,
    val currency: String,
    val recipient: String,
    val description: String?,
    val externalId: String?,
    val network: String?,
    val asaId: String?,
    val challengeReference: String,
    /** REQUIRED for Algorand charge. Derived as SHA-256(challengeReference). */
    val lease: String,
    val feePayer: Boolean,
    val feePayerKey: String?,
    val suggestedParams: SuggestedParams?,
)

/**
 * Solana-specific charge request fields parsed from `request.methodDetails`.
 */
internal data class SolanaChargeRequest(
    val amount: String,
    val currency: String,
    val recipient: String,
    val description: String?,
    val externalId: String?,
    val network: String?,
    val mint: String?,
)

internal data class SuggestedParams(
    val firstValid: Long,
    val lastValid: Long,
    val genesisHash: String,
    val genesisId: String,
    val fee: Long,
    val minFee: Long,
)

internal object ChargeChallengeCodec {
    /**
     * Compute the HMAC-SHA256 challenge ID over the canonical challenge fields,
     * matching mppx's `computeId` exactly:
     *
     *   input = realm | method | intent | JCS(request) | expires | digest | opaque
     *
     * Optional fields are empty strings when absent. Output: base64url-no-pad.
     */
    fun computeId(
        challenge: ChargeChallenge,
        secretKey: String,
    ): String {
        val requestSerialized = ChargeRequestCodec.serialize(challenge.request)
        val opaqueSerialized = challenge.opaque?.let { ChargeRequestCodec.serialize(it) } ?: ""
        val input =
            listOf(
                challenge.realm,
                challenge.method,
                challenge.intent,
                requestSerialized,
                challenge.expires ?: "",
                challenge.digest ?: "",
                opaqueSerialized,
            ).joinToString(separator = "|")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(input.toByteArray(Charsets.UTF_8))
        return Base64Url.encode(digest)
    }

    /** Constant-time challenge ID verification. */
    fun verifyId(
        challenge: ChargeChallenge,
        secretKey: String,
    ): Boolean {
        val expected = computeId(challenge, secretKey).toByteArray(Charsets.UTF_8)
        val actual = challenge.id.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }

    /** Build the WWW-Authenticate header string for the challenge. */
    fun toAuthHeader(challenge: ChargeChallenge): String {
        val params = LinkedHashMap<String, String>()
        params["id"] = challenge.id
        params["realm"] = challenge.realm
        params["method"] = challenge.method
        params["intent"] = challenge.intent
        params["request"] = ChargeRequestCodec.serialize(challenge.request)
        challenge.description?.let { params["description"] = it }
        challenge.digest?.let { params["digest"] = it }
        challenge.expires?.let { params["expires"] = it }
        challenge.opaque?.let { params["opaque"] = ChargeRequestCodec.serialize(it) }
        return AuthParams.serialize(params)
    }

    /** Parse a WWW-Authenticate header into a [ChargeChallenge]. */
    fun fromAuthHeader(header: String): ChargeChallenge {
        val params = AuthParams.parse(header)
        val id = params["id"] ?: error("MPP challenge missing 'id'")
        val realm = params["realm"] ?: error("MPP challenge missing 'realm'")
        val method = params["method"] ?: error("MPP challenge missing 'method'")
        val intent = params["intent"] ?: error("MPP challenge missing 'intent'")
        val requestEncoded = params["request"] ?: error("MPP challenge missing 'request'")
        val opaqueEncoded = params["opaque"]

        return ChargeChallenge(
            id = id,
            realm = realm,
            method = method,
            intent = intent,
            request = ChargeRequestCodec.deserialize(requestEncoded),
            expires = params["expires"],
            description = params["description"],
            digest = params["digest"],
            opaque = opaqueEncoded?.let { ChargeRequestCodec.deserialize(it) },
        )
    }
}

internal object ChargeRequestCodec {
    /** Serialize a request JSONObject to base64url(JCS). */
    fun serialize(request: JSONObject): String {
        val canonical = JcsJson.canonicalize(request)
        return Base64Url.encode(canonical)
    }

    /** Decode base64url + parse JSON for a `request` auth-param value. */
    fun deserialize(encoded: String): JSONObject {
        val json = Base64Url.decodeToString(encoded)
        return JSONObject(json)
    }

    /**
     * Pull out the well-known algorand-specific fields from a challenge's
     * request object. Use only on consumer side after parsing the challenge.
     */
    fun parseAlgorandRequest(request: JSONObject): ChargeRequest {
        val md = request.optJSONObject("methodDetails") ?: JSONObject()
        val sp = md.optJSONObject("suggestedParams")
        return ChargeRequest(
            amount = request.getString("amount"),
            currency = request.getString("currency"),
            recipient = request.getString("recipient"),
            description = request.optString("description").ifBlank { null },
            externalId = request.optString("externalId").ifBlank { null },
            network = md.optString("network").ifBlank { null },
            asaId = md.optString("asaId").ifBlank { null },
            challengeReference = md.getString("challengeReference"),
            // lease is REQUIRED per spec (SHA-256 of challengeReference)
            lease = md.getString("lease"),
            feePayer = md.optBoolean("feePayer", false),
            feePayerKey = md.optString("feePayerKey").ifBlank { null },
            suggestedParams =
                sp?.let {
                    SuggestedParams(
                        firstValid = it.getLong("firstValid"),
                        lastValid = it.getLong("lastValid"),
                        genesisHash = it.getString("genesisHash"),
                        genesisId = it.getString("genesisId"),
                        fee = it.getLong("fee"),
                        minFee = it.getLong("minFee"),
                    )
                },
        )
    }

    fun parseSolanaRequest(request: JSONObject): SolanaChargeRequest {
        val md = request.optJSONObject("methodDetails") ?: JSONObject()
        return SolanaChargeRequest(
            amount = request.getString("amount"),
            currency = request.getString("currency"),
            recipient = request.getString("recipient"),
            description = request.optString("description").ifBlank { null },
            externalId = request.optString("externalId").ifBlank { null },
            network = md.optString("network").ifBlank { null },
            mint = md.optString("mint").ifBlank { null },
        )
    }
}
