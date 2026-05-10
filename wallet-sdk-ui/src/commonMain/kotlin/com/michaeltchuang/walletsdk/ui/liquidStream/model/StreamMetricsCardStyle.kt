package com.michaeltchuang.walletsdk.ui.liquidStream.model

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

data class StreamMetricsCardStyle(
    val sectionSpacing: Dp,
    val metricSpacing: Dp,
    val valueFontSize: TextUnit,
    val valueLineHeight: TextUnit,
    val unitFontSize: TextUnit,
    val lockIconSize: Dp,
    val metricValueSpacing: Dp,
    val unitTextPrefix: String,
    val rootModifier: Modifier,
    val useAnnotatedBlockLabel: Boolean,
)
