package com.michaeltchuang.walletsdk.ui.signing.screens

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectAccountViewModel
import org.junit.Test
import java.util.Locale

class SelectAccountScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testSelectAccountContentWithAccounts() {
        setTestContent {
            ScreenContent(
                viewState =
                    SelectAccountViewModel.AccountsState.Content(
                        accounts =
                            listOf(
                                AccountLite(
                                    address = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                                    customName = "Main Account",
                                    registrationType = AccountRegistrationType.HdKey,
                                    balance = "25000000",
                                ),
                                AccountLite(
                                    address = "BXYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P3XYZ9YUM2P",
                                    customName = "Secondary Account",
                                    registrationType = AccountRegistrationType.Falcon24,
                                    balance = "10500000",
                                ),
                                AccountLite(
                                    address = "CDEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1ABC4DEF1A",
                                    customName = "",
                                    registrationType = AccountRegistrationType.NoAuth,
                                    balance = "0",
                                ),
                            ),
                    ),
                onAccountSelected = {},
                onBack = {},
            )
        }

        takeScreenshot("SelectAccount_ContentWithAccounts")
    }
}
