package com.michaeltchuang.walletsdk.core.liquidAuth.di

import com.michaeltchuang.walletsdk.core.liquidAuth.data.repository.StubMppEscrowRepository
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository.MppEscrowRepository
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.ConsumeMppEscrowBlocksUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.CreateMppEscrowSessionUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.SettleMppEscrowSessionUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.SubmitMppViewerDepositUseCase
import org.koin.dsl.module

val liquidAuthDomainModule =
    module {
        single { GenerateLiquidAuthOfferUseCase() }

        single<MppEscrowRepository> { StubMppEscrowRepository() }
        factory { CreateMppEscrowSessionUseCase(get()) }
        factory { SubmitMppViewerDepositUseCase(get()) }
        factory { ConsumeMppEscrowBlocksUseCase(get()) }
        factory { SettleMppEscrowSessionUseCase(get()) }
    }
