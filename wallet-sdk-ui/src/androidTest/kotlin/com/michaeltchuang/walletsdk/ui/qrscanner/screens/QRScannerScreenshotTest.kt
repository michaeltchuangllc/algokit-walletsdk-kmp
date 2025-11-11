package com.michaeltchuang.walletsdk.ui.qrscanner.screens

import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class QRScannerScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {

    private fun allowPermissionsIfNeeded() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            // Look for "Allow" or "While using the app" button
            val allowButton = device.findObject(
                UiSelector()
                    .textMatches("(?i)(allow|while using the app|only this time)")
                    .clickable(true)
            )
            if (allowButton.exists()) {
                allowButton.click()
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            // Permission already granted or no dialog shown
        }
    }

    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                hasProcessedResult = false,
                onQrCodeScanned = {},
                onBackPressed = {},
            )
        }

        // Handle permission dialog if it appears
        allowPermissionsIfNeeded()

        takeScreenshot("testContent")
    }
}
