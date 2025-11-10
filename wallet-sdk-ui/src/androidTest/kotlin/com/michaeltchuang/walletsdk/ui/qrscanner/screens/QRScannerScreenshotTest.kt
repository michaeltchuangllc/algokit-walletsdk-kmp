package com.michaeltchuang.walletsdk.ui.qrscanner.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class QRScannerScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testQRScanner() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                hasProcessedResult = false,
                onQrCodeScanned = {},
                onBackPressed = {},
            )
        }

        takeScreenshot("testDefaultState")
    }
}
