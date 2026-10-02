package com.michaeltchuang.walletsdk.core.passkeys.domain.model

import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import org.json.JSONObject
import java.util.Base64

/**
 * This class is a duplicated version of the original [androidx.credentials.webauthn.PublicKeyCredentialRequestOptions]
 * from the WebAuthn library, which is restricted to library-only usage.
 *
 * It was duplicated intentionally to simplify integration and avoid the need to create
 * multiple sub-models and data mappers that would otherwise be required to work around
 * its restricted visibility.
 *
 * Includes RP/allowCredentials matching shared by credential selection and intent validation.
 */
class PublicKeyCredentialRequestOptions(
    requestJson: String,
) {
    private val json: JSONObject = JSONObject(requestJson)

    val challenge: ByteArray
    private val timeout: Long

    val rpId: String
    private val userVerification: String
    private val allowCredentials: List<PublicKeyCredentialDescriptor>

    init {
        val challengeString = json.getString("challenge")
        challenge = WebAuthnUtils.b64Decode(challengeString)
        timeout = json.optLong("timeout", 0)
        rpId = json.optString("rpId", "")
        userVerification = json.optString("userVerification", "preferred")
        // Only omission or an actual empty array means discoverable credentials.
        // Malformed values must not silently become an unrestricted request.
        val descriptors = if (json.has("allowCredentials")) json.getJSONArray("allowCredentials") else null
        allowCredentials =
            if (descriptors == null) {
                emptyList()
            } else {
                List(descriptors.length()) { index ->
                    val descriptor = descriptors.getJSONObject(index)
                    val id = Base64.getUrlDecoder().decode(descriptor.getString("id"))
                    require(id.isNotEmpty()) { "Credential id must not be empty" }
                    PublicKeyCredentialDescriptor(descriptor.getString("type"), id)
                }
            }
    }

    /** Shared selection/signing policy. Transports are hints, not credential restrictions. */
    fun allows(passkey: Passkey): Boolean {
        if (rpId.isBlank() || passkey.site.url != rpId) return false
        if (allowCredentials.isEmpty()) return true
        val credentialId =
            try {
                Base64.getUrlDecoder().decode(passkey.credId)
            } catch (_: IllegalArgumentException) {
                return false
            }
        return allowCredentials.any { it.type == "public-key" && it.id.contentEquals(credentialId) }
    }

    private class PublicKeyCredentialDescriptor(
        val type: String,
        val id: ByteArray,
    )
}
