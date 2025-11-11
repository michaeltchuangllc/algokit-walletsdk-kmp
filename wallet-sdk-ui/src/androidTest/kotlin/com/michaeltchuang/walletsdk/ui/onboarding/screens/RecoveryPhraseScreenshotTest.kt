package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class RecoveryPhraseScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val mnemonicInputs =
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
            ScreenContent(
                navController = navController,
                accountType = AccountMnemonic.AccountType.Falcon24,
                mnemonicList = mnemonicInputs,
                onMnemonicChange = { },
                onClipboardPaste = { },
                onRecover = { },
                onLearnMore = { },
            )
        }
        takeScreenshot("testContent")
    }

    @Test
    fun testEmptyRecoveryPhrase24Words() {
        setTestContent {
            val navController = rememberNavController()
            ScreenContent(
                navController = navController,
                accountType = AccountMnemonic.AccountType.Falcon24,
                mnemonicList = List(24) { "" },
                onMnemonicChange = { },
                onClipboardPaste = { },
                onRecover = { },
                onLearnMore = { },
            )
        }
        takeScreenshot("testEmptyRecoveryPhrase24Words")
    }

    @Test
    fun testEmptyRecoveryPhrase25Words() {
        setTestContent {
            val navController = rememberNavController()
            ScreenContent(
                navController = navController,
                accountType = AccountMnemonic.AccountType.Algo25,
                mnemonicList = List(25) { "" },
                onMnemonicChange = { },
                onClipboardPaste = { },
                onRecover = { },
                onLearnMore = { },
            )
        }
        takeScreenshot("testEmptyRecoveryPhrase25Words")
    }

    @Test
    fun testCompleteRecoveryPhrase() {
        setTestContent {
            val navController = rememberNavController()
            val mnemonicInputs =
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
            ScreenContent(
                navController = navController,
                accountType = AccountMnemonic.AccountType.Falcon24,
                mnemonicList = mnemonicInputs,
                onMnemonicChange = { },
                onClipboardPaste = { },
                onRecover = { },
                onLearnMore = { },
            )
        }
        takeScreenshot("testCompleteRecoveryPhrase")
    }

    @Test
    fun testCompleteRecoveryPhrase25Words() {
        setTestContent {
            val navController = rememberNavController()
            val mnemonicInputs =
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
            ScreenContent(
                navController = navController,
                accountType = AccountMnemonic.AccountType.Algo25,
                mnemonicList = mnemonicInputs,
                onMnemonicChange = { },
                onClipboardPaste = { },
                onRecover = { },
                onLearnMore = { },
            )
        }
        takeScreenshot("testCompleteRecoveryPhrase25Words")
    }
}
