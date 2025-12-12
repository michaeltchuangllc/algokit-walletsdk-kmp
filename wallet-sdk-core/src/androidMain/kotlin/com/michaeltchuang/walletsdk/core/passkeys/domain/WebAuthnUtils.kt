package com.michaeltchuang.walletsdk.core.passkeys.domain

import android.util.Base64
import androidx.credentials.provider.CallingAppInfo

/**
 * This class is a duplicated version of the original [androidx.credentials.webauthn.WebAuthnUtils]
 * from the WebAuthn library, which is restricted to library-only usage.
 *
 * It was duplicated intentionally to simplify integration and avoid the need to create
 * multiple sub-models and data mappers that would otherwise be required to work around
 * its restricted visibility.
 *
 * The functionality and structure are preserved as-is for compatibility and maintainability.
 */
internal class WebAuthnUtils {

    companion object {
        fun b64Decode(str: String): ByteArray {
            return Base64.decode(str, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
        }

        fun b64Encode(data: ByteArray): String {
            return Base64.encodeToString(
                data,
                Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE
            )
        }

        fun appInfoToOrigin(info: CallingAppInfo): String {
            val cert = info.signingInfo.apkContentsSigners[0].toByteArray()
            val md = PeraMessageDigest.getInstance()
            val certHash = md.digest(cert)
            return "android:apk-key-hash:${b64Encode(certHash)}"
        }
    }
}
