package com.michaeltchuang.walletsdk.core.passkeys.domain.model

import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import org.json.JSONObject

/**
 * This class is a duplicated version of the original [androidx.credentials.webauthn.FidoPublicKeyCredential]
 * from the WebAuthn library, which is restricted to library-only usage.
 *
 * It was duplicated intentionally to simplify integration and avoid the need to create
 * multiple sub-models and data mappers that would otherwise be required to work around
 * its restricted visibility.
 *
 * The functionality and structure are preserved as-is for compatibility and maintainability.
 */
class FidoPublicKeyCredential(
    val encodedId: String,
    val response: AuthenticatorResponse,
    val authenticatorAttachment: String = DEFAULT_AUTH_ATTACHMENT
) {

    constructor(
        rawId: ByteArray,
        response: AuthenticatorResponse,
        authenticatorAttachment: String = DEFAULT_AUTH_ATTACHMENT
    ) : this(WebAuthnUtils.b64Encode(rawId), response, authenticatorAttachment)

    fun json(): String {
        val ret = JSONObject()
        ret.put("id", encodedId)
        ret.put("rawId", encodedId)
        ret.put("type", "public-key")
        ret.put("authenticatorAttachment", authenticatorAttachment)
        ret.put("response", response.json())
        ret.put("clientExtensionResults", extensionJson())
        return ret.toString()
    }

    private fun extensionJson(): JSONObject {
        val json = JSONObject()
        json.put("credProps", credPropsJson())
        return json
    }

    private fun credPropsJson(): JSONObject {
        val response = JSONObject()
        response.put("rk", true)
        return response
    }

    private companion object {
        const val DEFAULT_AUTH_ATTACHMENT = "platform"
    }
}
