package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon25AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.DeleteAccountCustomInfo
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteFalcon25Account

class DeleteFalcon25AccountUseCase(
    private val falcon25AccountRepository: Falcon25AccountRepository,
    private val customInfo: DeleteAccountCustomInfo,
) : DeleteFalcon25Account {
    override suspend fun invoke(address: String) {
        falcon25AccountRepository.deleteAccount(address)
        customInfo.invoke(address)
    }
}
