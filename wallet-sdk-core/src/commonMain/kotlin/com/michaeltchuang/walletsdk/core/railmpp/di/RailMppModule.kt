package com.michaeltchuang.walletsdk.core.railmpp.di

import com.michaeltchuang.walletsdk.core.railmpp.data.repository.RailMppDataRepositoryImpl
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.SessionVaultBalanceRepositoryImpl
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.MppVoucherRepositoryImpl
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.RailMppDataRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.DebugAddressSelectionsUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRailMppChannelSaltUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val railMppModule =
    module {
        single<MppVoucherRepository> { MppVoucherRepositoryImpl(get<AlgoKitDatabase>().mppVoucherDao()) }
        single<RailMppDataRepository> { RailMppDataRepositoryImpl() }
        single<SessionVaultBalanceRepository> { SessionVaultBalanceRepositoryImpl() }
        singleOf(::DebugAddressSelectionsUseCase)
        singleOf(::GetRailMppChannelSaltUseCase)
        singleOf(::GetRemainingSessionVaultBalanceUseCase)
        singleOf(::GetSessionVaultConfigUseCase)
        singleOf(::GetSessionVaultContextUseCase)
        singleOf(::MppWalletSignerUseCase)
    }
