package com.michaeltchuang.walletsdk.core.passkeys.domain.model

import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import org.json.JSONObject

/**
 * This class is a duplicated version of the original [androidx.credentials.webauthn.PublicKeyCredentialRequestOptions]
 * from the WebAuthn library, which is restricted to library-only usage.
 *
 * It was duplicated intentionally to simplify integration and avoid the need to create
 * multiple sub-models and data mappers that would otherwise be required to work around
 * its restricted visibility.
 *
 * The functionality and structure are preserved as-is for compatibility and maintainability.
 */
class PublicKeyCredentialRequestOptions(requestJson: String) {
    private val json: JSONObject = JSONObject(requestJson)

    val challenge: ByteArray
    private val timeout: Long

    val rpId: String
    private val userVerification: String

    init {
        val challengeString = json.getString("challenge")
        challenge = WebAuthnUtils.b64Decode(challengeString)
        timeout = json.optLong("timeout", 0)
        rpId = json.optString("rpId", "")
        userVerification = json.optString("userVerification", "preferred")
    }
}
