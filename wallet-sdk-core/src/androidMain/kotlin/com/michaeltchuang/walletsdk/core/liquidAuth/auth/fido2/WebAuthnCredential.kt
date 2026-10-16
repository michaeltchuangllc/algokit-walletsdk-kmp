package com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2

import org.json.JSONObject
import java.util.Base64

class WebAuthnCredential(
    private val responseJson: String,
) {
    val id: String

    private val decodedRawId: ByteArray

    val rawId: ByteArray
        get() = decodedRawId.copyOf()

    init {
        val payload = JSONObject(responseJson)
        require(payload.opt("type") == "public-key") { "Credential type must be public-key" }
        val credentialId = payload.opt("id")
        require(credentialId is String && credentialId.isNotBlank()) { "Credential id must be a non-empty string" }
        val encodedRawId = payload.opt("rawId")
        require(encodedRawId is String && encodedRawId.isNotBlank()) { "Credential rawId must be a non-empty string" }
        require(payload.opt("response") is JSONObject) { "Credential response must be an object" }

        id = credentialId
        decodedRawId = Base64.getUrlDecoder().decode(encodedRawId)
        require(decodedRawId.isNotEmpty()) { "Credential rawId must decode to non-empty bytes" }
    }

    /** Returns a deep copy so payload decoration cannot mutate the provider response. */
    fun toJson(): JSONObject = JSONObject(responseJson)
}
