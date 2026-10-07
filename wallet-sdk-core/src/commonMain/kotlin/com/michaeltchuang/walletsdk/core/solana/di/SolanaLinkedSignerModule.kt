package com.michaeltchuang.walletsdk.core.solana.di

import com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaEscrowSessionVaultManager
import com.michaeltchuang.walletsdk.core.solana.data.SolanaLinkedSignerRepository
import com.michaeltchuang.walletsdk.core.solana.data.SolanaLinkedSignerRepositoryImpl
import com.michaeltchuang.walletsdk.core.solana.data.SolanaRpcClient
import com.michaeltchuang.walletsdk.core.solana.data.mapper.entity.SolanaLinkedSignerEntityMapper
import com.michaeltchuang.walletsdk.core.solana.data.mapper.entity.SolanaLinkedSignerEntityMapperImpl
import com.michaeltchuang.walletsdk.core.solana.data.mapper.model.SolanaLinkedSignerMapper
import com.michaeltchuang.walletsdk.core.solana.data.mapper.model.SolanaLinkedSignerMapperImpl
import com.michaeltchuang.walletsdk.core.solana.domain.EnsureSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaLinkedSignerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSessionSignerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSettlementFeePayerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSignerBalanceUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.RegenerateSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.ReturnSolanaSessionKeyFundsUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SubmitSolanaSettlementVoucherUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SweepSolanaLinkedSignerUseCase
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val solanaLinkedSignerModule =
    module {
        single<SolanaLinkedSignerEntityMapper> { SolanaLinkedSignerEntityMapperImpl() }
        single<SolanaLinkedSignerMapper> { SolanaLinkedSignerMapperImpl() }
        single<SolanaLinkedSignerRepository> {
            SolanaLinkedSignerRepositoryImpl(
                dao = get<AlgoKitDatabase>().solanaLinkedSignerDao(),
                solanaLinkedSignerEntityMapper = get(),
                solanaLinkedSignerMapper = get(),
            )
        }
        singleOf(::SolanaRpcClient)
        single { SolanaEscrowSessionVaultManager(rpc = get()) }
        factoryOf(::GetSolanaLinkedSignerUseCase)
        factoryOf(::EnsureSolanaSessionKeyUseCase)
        factoryOf(::RegenerateSolanaSessionKeyUseCase)
        factoryOf(::GetSolanaSignerBalanceUseCase)
        factoryOf(::SweepSolanaLinkedSignerUseCase)
        factoryOf(::ReturnSolanaSessionKeyFundsUseCase)
        factoryOf(::GetSolanaSessionSignerUseCase)
        factoryOf(::GetSolanaSettlementFeePayerUseCase)
        factoryOf(::SubmitSolanaSettlementVoucherUseCase)
    }
