package com.michaeltchuang.walletsdk.core.foundation.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class WalletSdkExtensionsTest {
    @Test
    fun `EXPECT microAlgo amount formatted as Algo amount WHEN formatAmount is called`() {
        val result = "4000".formatAmount()

        assertEquals("0.004000", result)
    }

    @Test
    fun `EXPECT amount padded to six decimals WHEN formatAmount is called`() {
        val result = "2000000".formatAmount()

        assertEquals("2.000000", result)
    }

    @Test
    fun `EXPECT amount rounded to six decimals WHEN non micro amount has extra decimals`() {
        val result = "0.0040005".formatAmount(convertToMicroAmount = false)

        assertEquals("0.004001", result)
    }

    @Test
    fun `EXPECT amount rounded with carry WHEN decimal rounds into integer`() {
        val result = "0.9999995".formatAmount(convertToMicroAmount = false)

        assertEquals("1.000000", result)
    }
}
