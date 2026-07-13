package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import android.os.Environment
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.QRCodeViewModel
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/*class ShowAddressScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    QRCodeViewModel.ViewState.Content(
                        address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
                        displayAddress = "MCRT...FWRLM",
                    ),
                onCopyAddress = {},
            )
        }

        takeScreenshot("testContent")
    }
}*/

@RunWith(AndroidJUnit4::class)
class ShowAddressScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showAddress_light_en() {
        Locale.setDefault(Locale.ENGLISH)

        composeRule.setContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState = QRCodeViewModel.ViewState.Content(
                    address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
                    displayAddress = "MCRT...FWRLM",
                ),
                onCopyAddress = {},
            )
        }

        composeRule.waitUntil(5000) {
            composeRule.onAllNodesWithText("MCRT...FWRLM").fetchSemanticsNodes().isNotEmpty()
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        val context = instrumentation.targetContext
        val outputDir = File(context.getExternalFilesDir(null), "screenshots")
        outputDir.mkdirs()

        val screenshot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "show_address_en_light.png"
        )
        val success = device.takeScreenshot(screenshot)
        println("Screenshot success = $success")
        println("Saved to = ${screenshot.absolutePath}")
    }
}
