package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class ConfirmTransactionScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ConfirmTransferScreen(
                provider = "Provider XYZ",
                origin = "michaeltchuang.ngrok.dev",
                session = "Session123",
                fee = "0.001",
                accountBalance = "1000000",
                address = "XWJQV6G54JU62F7ZL7K5HJXH3Z3WZ44Q4ZMZ5W4",
                onTransactionClick = {},
                onClose = {},
            )
        }
        takeScreenshot("testContent")
    }
}
