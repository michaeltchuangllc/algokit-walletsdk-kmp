package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GatingMode(
    val value: String,
) {
    @SerialName("whole-stream") WHOLE_STREAM("whole-stream"),
    @SerialName("partial-time") PARTIAL_TIME("partial-time"),
    @SerialName("partial-bytes") PARTIAL_BYTES("partial-bytes"),
    ;

    companion object {
        fun fromString(s: String): GatingMode =
            entries.firstOrNull { it.value == s }
                ?: throw IllegalArgumentException("Unknown GatingMode: $s")
    }
}
