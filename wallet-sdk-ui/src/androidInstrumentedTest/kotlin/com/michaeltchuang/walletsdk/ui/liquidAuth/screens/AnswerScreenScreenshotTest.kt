package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class AnswerScreenScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            // Direct call to ScreenContentAnswer with sample data
            ScreenContentAnswer(
                isConnected = true,
                isWaiting = false,
                isConnecting = false,
                hasError = false,
                errorMessage = null,
                session = "Connected",
                origin = "michaeltchuang.ngrok.dev",
                requestId = "019b93d8-ae86-7004-a1b9-945f05c4a91d",
                accountAddress = "X6TTIPDLJQ2S5ARP6PEURNG4DIU6R4CPMRZ7AMCC5AHH2ME374LSCQ2GWI",
            )
        }
        takeScreenshot("testContent")
    }
}
