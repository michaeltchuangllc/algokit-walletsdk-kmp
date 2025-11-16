package com.michaeltchuang.walletsdk.ui.signing.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class TransactionSuccessScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                transactionId = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                onDoneClick = {},
                onViewInExplorer = {},
            )
        }

        takeScreenshot("testContent")
    }
}
