package com.michaeltchuang.walletsdk.core.railmpp.domain.model

enum class GatingMode(
    val value: String,
) {
    WHOLE_STREAM("whole-stream"),
    PARTIAL_TIME("partial-time"),
    PARTIAL_BYTES("partial-bytes"),
    ;

    companion object {
        fun fromString(s: String): GatingMode =
            entries.firstOrNull { it.value == s }
                ?: throw IllegalArgumentException("Unknown GatingMode: $s")
    }
}