package com.michaeltchuang.walletsdk.core.railmpp.spec

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** base64url per RFC 4648 §5, no padding — credentials, challenge `request`, receipts. */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Url {
    private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun encode(bytes: ByteArray): String = encoder.encode(bytes)

    fun encode(s: String): String = encode(s.encodeToByteArray())

    fun decode(s: String): ByteArray = decoder.decode(s)

    fun decodeToString(s: String): String = decode(s).decodeToString()
}

/** Standard base64 (padding tolerated) used for individual `paymentGroup` transactions. */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Std {
    private val codec = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

    fun decode(s: String): ByteArray = codec.decode(s)
}
