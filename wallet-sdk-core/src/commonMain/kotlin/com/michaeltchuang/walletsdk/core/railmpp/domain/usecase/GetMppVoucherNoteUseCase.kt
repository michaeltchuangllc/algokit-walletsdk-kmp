package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.railmpp.domain.model.notes.MppVoucherNote
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.notes.MppVoucherNoteItem
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.notes.MppVoucherNoteSku
import kotlinx.serialization.json.Json

class GetMppVoucherNoteUseCase {
    data class Params(
        val channelId: String,
        val startBlock: Long,
        val currentBlock: Long,
        val freeBlocks: Long,
        val paidBlocks: Long,
        val costPerPaidBlock: Long,
        val settledAmount: Long,
        val totalCumulativeAmount: Long,
        val freeChatCount: Long = 0,
        val tipChatCount: Long = 0,
        val tipChatTotal: Long = 0,
    )

    operator fun invoke(params: Params): String {
        val paidTotal = params.paidBlocks * params.costPerPaidBlock
        
        val items = listOf(
            MppVoucherNoteItem(
                sku = MppVoucherNoteSku.SUB_FREE,
                name = "Free Subscription",
                quantity = params.freeBlocks,
                total = 0,
            ),
            MppVoucherNoteItem(
                sku = MppVoucherNoteSku.SUB_TIER_1,
                name = "Subscription (${params.costPerPaidBlock} micro-USDC/block)",
                quantity = params.paidBlocks,
                total = paidTotal,
            ),
            MppVoucherNoteItem(
                sku = MppVoucherNoteSku.CHAT_FREE,
                name = "Free Chat Message",
                quantity = params.freeChatCount,
                total = 0,
            ),
            MppVoucherNoteItem(
                sku = MppVoucherNoteSku.CHAT_TIP,
                name = "Chat With Tip (Superchat)",
                quantity = params.tipChatCount,
                total = params.tipChatTotal,
            ),
        )

        val note = MppVoucherNote(
            v = 1,
            channel = params.channelId,
            range = listOf(params.startBlock, params.currentBlock),
            items = items,
            cumulative_total = params.totalCumulativeAmount,
        )

        val json = Json { encodeDefaults = true }
        return json.encodeToString(MppVoucherNote.serializer(), note)
    }
}
