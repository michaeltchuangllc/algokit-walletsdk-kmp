package com.michaeltchuang.walletsdk.core.railmpp.data.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mpp_vouchers")
data class MppVoucherEntity(
    @PrimaryKey
    @ColumnInfo("session_id")
    val sessionId: String,
    @ColumnInfo("viewer_address")
    val viewerAddress: String,
    @ColumnInfo("viewer_public_key_base64")
    val viewerPublicKeyBase64: String,
    @ColumnInfo("signature_base64")
    val signatureBase64: String,
    @ColumnInfo("total_amount_claimed_micro_usdc")
    val totalAmountClaimedMicroUsdc: Long,
    @ColumnInfo("creator_address")
    val creatorAddress: String,
    @ColumnInfo("block_number")
    val blockNumber: Long,
    @ColumnInfo("channel_id_base64")
    val channelIdBase64: String?,
    @ColumnInfo("note")
    val note: String,
)
