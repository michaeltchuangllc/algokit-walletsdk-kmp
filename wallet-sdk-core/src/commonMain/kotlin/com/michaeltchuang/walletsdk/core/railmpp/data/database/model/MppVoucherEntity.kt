package com.michaeltchuang.walletsdk.core.railmpp.data.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A row represents the latest known voucher for a single payment channel
 * (uniquely identified by [channelIdBase64], derived from viewer + creator +
 * authorized signer key). [channelIdBase64] is the primary key so that
 * reconnects from the same viewer/creator pair collapse onto one row instead
 * of accumulating a new row per [sessionId].
 *
 * [sessionId] is intentionally NOT unique on its own: multiple viewers may in
 * the future share the same liquid-auth requestId (and therefore the same
 * fallback sessionId), so uniqueness is only enforced for the
 * (session_id, viewer_address) pair.
 */
@Entity(
    tableName = "mpp_vouchers",
    indices = [
        Index(value = ["session_id", "viewer_address"], unique = true),
    ],
)
data class MppVoucherEntity(
    @PrimaryKey
    @ColumnInfo("channel_id_base64")
    val channelIdBase64: String,
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
    @ColumnInfo("note")
    val note: String,
)
