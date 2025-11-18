package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.ViewPassphraseViewModel
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class ViewPassphraseScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    companion object {
        // Sample 24-word BIP39 mnemonic
        private val SAMPLE_BIP39_WORDS =
            listOf(
                "abandon",
                "ability",
                "able",
                "about",
                "above",
                "absent",
                "absorb",
                "abstract",
                "absurd",
                "abuse",
                "access",
                "accident",
                "account",
                "accuse",
                "achieve",
                "acid",
                "acoustic",
                "acquire",
                "across",
                "act",
                "action",
                "actor",
                "actress",
                "actual",
            )

        // Sample 25-word Algorand mnemonic
        private val SAMPLE_ALGO25_WORDS =
            listOf(
                "abandon",
                "ability",
                "able",
                "about",
                "above",
                "absent",
                "absorb",
                "abstract",
                "absurd",
                "abuse",
                "access",
                "accident",
                "account",
                "accuse",
                "achieve",
                "acid",
                "acoustic",
                "acquire",
                "across",
                "act",
                "action",
                "actor",
                "actress",
                "actual",
                "adapt",
            )
    }

    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    ViewPassphraseViewModel.ViewState.Content(
                        mnemonicWords = SAMPLE_BIP39_WORDS,
                    ),
            )
        }

        takeScreenshot("testContent")
    }

    @Test
    fun testWith25WordMnemonic() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    ViewPassphraseViewModel.ViewState.Content(
                        mnemonicWords = SAMPLE_ALGO25_WORDS,
                    ),
            )
        }
        takeScreenshot("testWith25WordMnemonic")
    }
}
