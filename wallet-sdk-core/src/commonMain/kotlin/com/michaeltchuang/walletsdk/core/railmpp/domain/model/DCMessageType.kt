package com.michaeltchuang.walletsdk.core.railmpp.domain.model

/** DataChannel message types exchanged between provider and consumer. */
enum class DCMessageType(val value: String) {
    SEGMENT_REQUEST("segment:request"),
    SEGMENT_PAYMENT("segment:payment"),
    SEGMENT_ACCEPTED("segment:accepted"),
    SEGMENT_REJECTED("segment:rejected"),
    SESSION_TERMINATE("session:terminate"),
    /** Sent by the viewer after a session-vault top-up so the server re-issues the pending request. */
    VIEWER_VAULT_FUNDED("viewer:vault:funded"),
    SEGMENT_HANDSHAKE("segment:handshake"),
    SEGMENT_VOUCHER("segment:voucher"),
    ;

    companion object {
        fun fromString(value: String): DCMessageType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown DCMessageType: $value")

        fun fromStringOrNull(value: String?): DCMessageType? =
            entries.firstOrNull { it.value == value }
    }
}
