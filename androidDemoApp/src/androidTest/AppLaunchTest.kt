package com.michaeltchuang.walletsdk.demo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<AppActivity>()

    @Test
    fun appLaunchesSuccessfully() {
        // Verify the app activity launches and UI is composed
        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun appRendersWithoutCrashing() {
        // Wait for any initial compositions to complete
        composeTestRule.waitForIdle()

        // Basic smoke test - if we get here without crashing, the test passes
        composeTestRule.onRoot().assertExists()
    }
}
