package com.michaeltchuang.walletsdk.core.railmpp.spec

import com.michaeltchuang.walletsdk.core.railmpp.internal.constantTimeEquals
import com.michaeltchuang.walletsdk.core.railmpp.internal.hmacSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** In-memory MPP charge challenge mirroring mppx's wire `Challenge`. */
internal data class ChargeChallenge(
    val id: String,
    val realm: String,
    val method: String,
    val intent: String,
    /** Full request object — see [ChargeRequest] for the well-known shape. */
    val request: JsonObject,
    val expires: String? = null,
    val description: String? = null,
    val digest: String? = null,
    /** Pass-through opaque map, preserved on both sides. */
    val opaque: JsonObject? = null,
)

/** Algorand charge fields under `request.methodDetails`. */
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

/** Solana charge fields parsed from `request.methodDetails`. */
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
     * HMAC-SHA256 challenge id over canonical fields, matching mppx's `computeId`:
     *   input = realm | method | intent | JCS(request) | expires | digest | opaque
     * Missing optional fields are empty strings. Output: base64url-no-pad.
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

        val digest = hmacSha256(secretKey.encodeToByteArray(), input.encodeToByteArray())
        return Base64Url.encode(digest)
    }

    /** Constant-time challenge id verification. */
    fun verifyId(
        challenge: ChargeChallenge,
        secretKey: String,
    ): Boolean {
        val expected = computeId(challenge, secretKey).encodeToByteArray()
        val actual = challenge.id.encodeToByteArray()
        return constantTimeEquals(expected, actual)
    }

    /** Build the WWW-Authenticate header for the challenge. */
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
    /** Serialize a request object to base64url(JCS). */
    fun serialize(request: JsonObject): String = Base64Url.encode(JcsJson.canonicalize(request))

    /** Decode base64url + parse JSON for a `request` auth-param value. */
    fun deserialize(encoded: String): JsonObject = Json.parseToJsonElement(Base64Url.decodeToString(encoded)).jsonObject

    /** Pull well-known Algorand fields from a challenge request (consumer side). */
    fun parseAlgorandRequest(request: JsonObject): ChargeRequest {
        val md = request.optObject("methodDetails") ?: JsonObject(emptyMap())
        val sp = md.optObject("suggestedParams")
        return ChargeRequest(
            amount = request.reqString("amount"),
            currency = request.reqString("currency"),
            recipient = request.reqString("recipient"),
            description = request.optString("description"),
            externalId = request.optString("externalId"),
            network = md.optString("network"),
            asaId = md.optString("asaId"),
            challengeReference = md.reqString("challengeReference"),
            // lease is REQUIRED per spec (SHA-256 of challengeReference)
            lease = md.reqString("lease"),
            feePayer = md.optBoolean("feePayer", false),
            feePayerKey = md.optString("feePayerKey"),
            suggestedParams =
                sp?.let {
                    SuggestedParams(
                        firstValid = it.reqLong("firstValid"),
                        lastValid = it.reqLong("lastValid"),
                        genesisHash = it.reqString("genesisHash"),
                        genesisId = it.reqString("genesisId"),
                        fee = it.reqLong("fee"),
                        minFee = it.reqLong("minFee"),
                    )
                },
        )
    }

    fun parseSolanaRequest(request: JsonObject): SolanaChargeRequest {
        val md = request.optObject("methodDetails") ?: JsonObject(emptyMap())
        return SolanaChargeRequest(
            amount = request.reqString("amount"),
            currency = request.reqString("currency"),
            recipient = request.reqString("recipient"),
            description = request.optString("description"),
            externalId = request.optString("externalId"),
            network = md.optString("network"),
            mint = md.optString("mint"),
        )
    }
}
