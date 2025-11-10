package com.michaeltchuang.walletsdk.ui.signing.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectReceiverViewModel
import org.junit.Test
import java.util.Locale

class SelectReceiverScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testSelectReceiverContentWithAccounts() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    SelectReceiverViewModel.ViewState.Content(
                        searchText = "",
                        accounts =
                            listOf(
                                AccountLite(
                                    address = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                                    customName = "Main Account",
                                    registrationType = AccountRegistrationType.HdKey,
                                ),
                                AccountLite(
                                    address = "BXYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P",
                                    customName = "Secondary Account",
                                    registrationType = AccountRegistrationType.Falcon24,
                                ),
                                AccountLite(
                                    address = "CDEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1A",
                                    customName = "",
                                    registrationType = AccountRegistrationType.NoAuth,
                                ),
                            ),
                        clipboardText = "DEMO1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890ABC",
                    ),
                onSearchTextChange = {},
                onAccountSelected = {},
                onClipboardTapped = {},
                onNextPressed = {},
            )
        }

        takeScreenshot("SelectReceiver_ContentWithAccounts")
    }

    @Test
    fun testSelectReceiverContentWithSearchText() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    SelectReceiverViewModel.ViewState.Content(
                        searchText = "HVTAJEVD6WVPY53MUZGPRJ446WW5C3SUSKNSQ3UCZH2R4XWQZPXE72MQ",
                        accounts = emptyList(),
                        clipboardText = "",
                    ),
                onSearchTextChange = {},
                onAccountSelected = {},
                onClipboardTapped = {},
                onNextPressed = {},
            )
        }

        takeScreenshot("SelectReceiver_ContentWithSearchText")
    }
}
