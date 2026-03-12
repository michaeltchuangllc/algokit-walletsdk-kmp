package com.michaeltchuang.walletsdk.core.liquidAuth.di

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import org.koin.dsl.module

val liquidAuthDomainModule =
    module {
        single { GenerateLiquidAuthOfferUseCase() }
    }
