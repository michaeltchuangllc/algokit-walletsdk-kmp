package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_every_block
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.SegmentedTabItem
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.StreamMetricsCardStyle

internal const val PAYOUT_EVERY_BLOCK_TAB_ID = "every_block"
internal const val PAYOUT_EVERY_256_BLOCKS_TAB_ID = "every_256_blocks"

val streamMetricsCardStyle =
    StreamMetricsCardStyle(
        sectionSpacing = 15.dp,
        metricSpacing = 5.dp,
        valueFontSize = 42.sp / 1.5f,
        valueLineHeight = 24.sp,
        unitFontSize = 12.sp,
        lockIconSize = 14.dp,
        metricValueSpacing = 4.dp,
        unitTextPrefix = "",
        rootModifier = Modifier,
        useAnnotatedBlockLabel = true,
    )

val payoutFrequencyTabs =
    listOf(
        SegmentedTabItem(
            id = PAYOUT_EVERY_BLOCK_TAB_ID,
            icon = Res.drawable.ic_every_block,
            title = "Every Block",
            subtitle = "(~2.8 secs)",
        ),
        SegmentedTabItem(
            id = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
            icon = Res.drawable.ic_every_block,
            title = "Every 256 Blocks",
            subtitle = "(~12 mins)",
        ),
    )