package com.michaeltchuang.walletsdk.core.railmpp.di

import com.michaeltchuang.walletsdk.core.railmpp.data.repository.AndroidSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecases.MppWalletSignerUseCase
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val railMppModule =
    module {
        singleOf(::AndroidSessionVaultBalanceRepository) bind SessionVaultBalanceRepository::class
        singleOf(::GetRemainingSessionVaultBalanceUseCase)
        singleOf(::MppWalletSignerUseCase)
    }
