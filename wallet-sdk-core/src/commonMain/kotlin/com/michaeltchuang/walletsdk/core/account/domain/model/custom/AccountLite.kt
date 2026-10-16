package com.michaeltchuang.walletsdk.core.account.domain.model.custom

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import kotlinx.serialization.Serializable

@Serializable
data class AccountLite(
    val address: String,
    val customName: String,
    val registrationType: AccountRegistrationType,
    val balance: String? = null,
    val usdcBalance: String? = null,
)
