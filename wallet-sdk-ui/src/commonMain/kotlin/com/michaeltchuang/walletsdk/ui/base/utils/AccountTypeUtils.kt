package com.michaeltchuang.walletsdk.ui.base.utils

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_algo25
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_falcon24
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_falcon25
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_hd
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_ledger
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_seedvault
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_seedvault_algorand
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_seedvault_solana
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_watch
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import org.jetbrains.compose.resources.StringResource

fun getAccountTypeResource(localAccount: AccountRegistrationType): StringResource =
    when (localAccount) {
        is AccountRegistrationType.HdKey -> Res.string.account_type_hd
        is AccountRegistrationType.Algo25 -> Res.string.account_type_algo25
        is AccountRegistrationType.Falcon24 -> Res.string.account_type_falcon24
        is AccountRegistrationType.Falcon25 -> Res.string.account_type_falcon25
        is AccountRegistrationType.NoAuth -> Res.string.account_type_watch
        is AccountRegistrationType.LedgerBle -> Res.string.account_type_ledger
        is AccountRegistrationType.SeedVault -> Res.string.account_type_seedvault
    }

fun getAccountTypeResource(account: AccountLite): StringResource =
    if (account.registrationType is AccountRegistrationType.SeedVault) {
        if (account.isSolana) Res.string.account_type_seedvault_solana else Res.string.account_type_seedvault_algorand
    } else {
        getAccountTypeResource(account.registrationType)
    }

