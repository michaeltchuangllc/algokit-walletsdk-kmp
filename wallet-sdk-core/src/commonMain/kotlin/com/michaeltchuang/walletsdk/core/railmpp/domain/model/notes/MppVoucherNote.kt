package com.michaeltchuang.walletsdk.core.railmpp.domain.model.notes

import kotlinx.serialization.Serializable

@Serializable
data class MppVoucherNote(
    val v: Int = 1,
    val channel: String,
    val range: List<Long>,
    val items: List<MppVoucherNoteItem>,
    val cumulative_total: Long,
)
