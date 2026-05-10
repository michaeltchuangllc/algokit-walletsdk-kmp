package com.michaeltchuang.walletsdk.core.railmpp.di

import com.michaeltchuang.walletsdk.core.railmpp.data.repository.IosSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.usecases.GetRemainingSessionVaultBalanceUseCase
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val railMppModule =
    module {
        singleOf(::IosSessionVaultBalanceRepository) bind SessionVaultBalanceRepository::class
        singleOf(::GetRemainingSessionVaultBalanceUseCase)
    }
