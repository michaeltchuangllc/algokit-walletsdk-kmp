package com.michaeltchuang.walletsdk.ui.signing.screens

import com.michaeltchuang.walletsdk.core.deeplink.model.KeyRegTransactionDetail
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.KeyRegConfirmViewModel
import org.junit.Test
import java.util.Locale

class KeyRegConfirmScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testKeyRegConfirmContent() {
        setTestContent {
            ScreenContent(
                viewState = KeyRegConfirmViewModel.ViewState.Content,
                onConfirm = {},
                onBack = {},
                minimumFee = "0.001",
                transactionDetail =
                    KeyRegTransactionDetail(
                        address = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                        type = "keyreg",
                        voteKey = "eE/mnNyJNWVFqaAP++2Z4TY6w4UKNvwuuwQ3D8vOjm0=",
                        selectionPublicKey = "RkIj6KCLnwqp6WcLQ4HyrnGRyHfvW5SPsAw/RTAr9Rs=",
                        sprfkey = "oCIl8wpXkzBKnJ5b6MaLk4SZlwsGzlgGCFQWdWLf3vw=",
                        voteFirstRound = "1000",
                        voteLastRound = "3000",
                        voteKeyDilution = "10000",
                        fee = "1000",
                        note = null,
                        xnote = null,
                    ),
            )
        }

        takeScreenshot("KeyRegConfirm_Content")
    }

    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                viewState = KeyRegConfirmViewModel.ViewState.Content,
                onConfirm = {},
                onBack = {},
                minimumFee = "0.001",
                transactionDetail =
                    KeyRegTransactionDetail(
                        address = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                        type = "keyreg",
                        voteKey = "eE/mnNyJNWVFqaAP++2Z4TY6w4UKNvwuuwQ3D8vOjm0=",
                        selectionPublicKey = "RkIj6KCLnwqp6WcLQ4HyrnGRyHfvW5SPsAw/RTAr9Rs=",
                        sprfkey = "oCIl8wpXkzBKnJ5b6MaLk4SZlwsGzlgGCFQWdWLf3vw=",
                        voteFirstRound = "1000",
                        voteLastRound = "3000",
                        voteKeyDilution = "10000",
                        fee = "1000",
                        note = "Key registration transaction",
                        xnote = null,
                    ),
            )
        }

        takeScreenshot("testContent")
    }
}
