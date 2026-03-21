package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts

internal class GetLocalAccountUseCase(
    private val getLocalAccounts: GetLocalAccounts,
) : GetLocalAccount {
    override suspend fun invoke(address: String): LocalAccount? = getLocalAccounts().firstOrNull { it.address == address }
}
