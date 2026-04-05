package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel

class LiquidStreamHostViewModel(
    private val stateDelegate: StateDelegate<UiState>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostViewModel.UiState> by stateDelegate {
    init {
        stateDelegate.setDefaultState(mockUiState())
    }

    fun onStreamCostTabSelected(tabId: String) {
        stateDelegate.updateState { it.copy(selectedStreamCostTabId = tabId) }
    }

    fun onPayoutFrequencyTabSelected(tabId: String) {
        stateDelegate.updateState { it.copy(selectedPayoutFrequencyTabId = tabId) }
    }

    fun onSubsidizeViewerFeesChanged(enabled: Boolean) {
        stateDelegate.updateState { it.copy(subsidizeViewerFeesEnabled = enabled) }
    }

    data class UiState(
        val creatorName: String,
        val creatorHandle: String,
        val title: String,
        val qualityLabel: String,
        val viewerCount: String,
        val latestGiftTitle: String,
        val latestGiftMessage: String,
        val likeCount: String,
        val infoCount: String,
        val accountDisplayName: String,
        val streamRate: String,
        val streamRevenue: String,
        val securedVia: String,
        val blockNumberLabel: String,
        val streamCostPaidTitle: String,
        val streamCostInfoText: String,
        val payoutInfoText: String,
        val selectedStreamCostTabId: String,
        val subsidizeViewerFeesEnabled: Boolean,
        val selectedPayoutFrequencyTabId: String,
    )

    companion object {
        const val STREAM_COST_FREE_TAB_ID = "free"
        const val STREAM_COST_PAID_TAB_ID = "paid"
        const val PAYOUT_EVERY_BLOCK_TAB_ID = "every_block"
        const val PAYOUT_EVERY_256_BLOCKS_TAB_ID = "every_256_blocks"

        fun mockUiState() =
            UiState(
                creatorName = "The Crypto Queen",
                creatorHandle = "@CYQUEEN",
                title = "This drop is legendary!\nAlgos well spent 💸",
                qualityLabel = "GOOD",
                viewerCount = "1,284",
                latestGiftTitle = "New Gift",
                latestGiftMessage = "@cyqueen sent 0.888 USDC",
                likeCount = "14",
                infoCount = "8",
                accountDisplayName = "algo_rhytem",
                streamRate = "0.42",
                streamRevenue = "+1.402.15",
                securedVia = "Secured via Algorand Mainnet",
                blockNumberLabel = "Block #38291041",
                streamCostPaidTitle = "8 micro-USDC",
                streamCostInfoText = "Est. 1 Million blocks/month. 8 micro-USDC is ~$8 USD monthly.",
                payoutInfoText = "Batching payouts every 256 blocks saves you ~ $2,000 USD/year in transaction fees compared to every block.",
                selectedStreamCostTabId = STREAM_COST_FREE_TAB_ID,
                subsidizeViewerFeesEnabled = false,
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
            )
    }
}
