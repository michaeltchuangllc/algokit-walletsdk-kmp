package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EnforcementMode(
    val value: String,
) {
    @SerialName("track") TRACK("track"),
    @SerialName("crypto") CRYPTO("crypto"),
}
