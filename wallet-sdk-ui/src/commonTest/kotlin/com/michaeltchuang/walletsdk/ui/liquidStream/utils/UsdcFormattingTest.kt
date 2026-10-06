package com.michaeltchuang.walletsdk.ui.liquidStream.utils

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class UsdcFormattingTest {
    @Test
    fun formatsMicroUsdcWithSixDecimals() {
        assertEquals("0.000008", formatMicroUsdc(8L))
        assertEquals("1.250000", formatMicroUsdc(1_250_000L))
        assertEquals("0.000000", formatMicroUsdc(0L))
        assertEquals("-0.000016", formatMicroUsdc(-16L))
    }

    @Test
    fun roundsUsdcToNearestMicroUsdc() {
        assertEquals("0.000008", formatUsdcSixDecimals(8 / 1_000_000.0))
        assertEquals("0.999992", formatUsdcSixDecimals(1.0 - 0.000008))
        assertEquals("0.300000", formatUsdcSixDecimals(0.1 + 0.2))
    }

    @Test
    fun revenueLabelKeepsMicroUsdcPrecision() {
        assertEquals("+0.000080", formatRevenueLabel(0.00008))
        assertEquals(ZERO_USDC_LABEL, formatRevenueLabel(0.0))
        assertEquals(ZERO_USDC_LABEL, formatRevenueLabel(0.0000001))
    }

    @Test
    fun shrinksOnlyLongMetricValues() {
        assertEquals(28.sp, metricValueFontSize(28.sp, "0.50"))
        assertEquals(28.sp * 0.62f, metricValueFontSize(28.sp, "0.000008"))
    }
}
