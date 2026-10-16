package com.michaeltchuang.walletsdk.ui.signing.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class TransactingTipsScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            TransactingTipsScreen(
                onUnderstandClick = {},
            )
        }

        takeScreenshot("testContent")
    }
}
