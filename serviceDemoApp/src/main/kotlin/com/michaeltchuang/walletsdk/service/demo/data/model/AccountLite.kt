package com.michaeltchuang.walletsdk.service.demo.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountLite(
    val address: String,
    val customName: String,
    val registrationType: AccountRegistrationType,
    val balance: String? = null
)
