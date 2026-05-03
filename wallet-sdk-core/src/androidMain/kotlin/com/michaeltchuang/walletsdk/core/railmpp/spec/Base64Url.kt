package xyz.goplausible.webrtcpaymentsdk.railmpp.spec

import android.util.Base64

/**
 * base64url codec per RFC 4648 §5, no padding — used for credentials, challenge
 * `request` field, and `Payment-Receipt` headers per draft-algorand-charge.
 */
internal object Base64Url {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)

    fun encode(s: String): String = encode(s.toByteArray(Charsets.UTF_8))

    fun decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING)

    fun decodeToString(s: String): String = decode(s).toString(Charsets.UTF_8)
}

/** Standard base64 (with padding) used for individual transactions in `paymentGroup`. */
internal object Base64Std {
    private const val FLAGS = Base64.NO_WRAP

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)

    fun decode(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
}
