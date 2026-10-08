package com.michaeltchuang.walletsdk.core.railmpp.domain.model

/** DataChannel message types exchanged between provider and consumer. */
enum class DCMessageType(
    val value: String,
) {
    SEGMENT_REQUEST("segment:request"),
    SEGMENT_PAYMENT("segment:payment"),
    SEGMENT_ACCEPTED("segment:accepted"),
    SEGMENT_REJECTED("segment:rejected"),
    SESSION_TERMINATE("session:terminate"),

    /** Sent by the viewer after a session-vault top-up so the server re-issues the pending request. */
    VIEWER_VAULT_FUNDED("viewer:vault:funded"),
    SEGMENT_HANDSHAKE("segment:handshake"),
    SEGMENT_VOUCHER("segment:voucher"),
    CHAT_MESSAGE("chat:message"),
    STREAM_COST_UPDATE("stream:cost:update"),

    /** Sent by the host when it refuses a viewer (e.g. the wallet is already watching this stream). */
    STREAM_REJECTED("stream:rejected"),
    ;

    companion object {
        fun fromStringOrNull(value: String?): DCMessageType? = entries.firstOrNull { it.value == value }
    }
}
