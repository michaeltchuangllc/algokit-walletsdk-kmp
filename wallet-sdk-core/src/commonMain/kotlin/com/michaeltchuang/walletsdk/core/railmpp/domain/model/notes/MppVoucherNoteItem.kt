package com.michaeltchuang.walletsdk.core.railmpp.domain.model.notes

import kotlinx.serialization.Serializable

@Serializable
data class MppVoucherNoteItem(
    val sku: String,
    val name: String,
    val quantity: Long,
    val total: Long,
)
