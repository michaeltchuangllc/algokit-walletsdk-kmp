package com.michaeltchuang.walletsdk.core.account.di

import com.michaeltchuang.walletsdk.core.account.data.database.dao.Algo25Dao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.Falcon24Dao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.Falcon25Dao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdKeyDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdSeedDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.NoAuthDao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25EntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25EntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Falcon24EntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Falcon25EntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Falcon25EntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Falcon24EntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.HdKeyEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.HdKeyEntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.HdSeedEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.HdSeedEntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.NoAuthEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.NoAuthEntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.SolanaAccountEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.SolanaAccountEntityMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Algo25Mapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Algo25MapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon24Mapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon25Mapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon25MapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon24MapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon24WalletSummaryMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon24WalletSummaryMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdKeyMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdKeyMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedWalletSummaryMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedWalletSummaryMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdWalletSummaryMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdWalletSummaryMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.NoAuthMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.NoAuthMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.SolanaAccountMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.SolanaAccountMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.Algo25AccountRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.Falcon24AccountRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.Falcon25AccountRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.HdKeyAccountRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.HdSeedRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.NoAuthAccountRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.SolanaAccountRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Algo25AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon24AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon25AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdKeyAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.NoAuthAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddHdKeyAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddHdKeyAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddHdSeedUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.CreateWatchAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteNoAuthAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteSolanaAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddressesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24WalletSummaries
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdEntropy
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdKeyPrivateKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdWalletSummaries
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetImportedSolanaAddressesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetMaxHdSeedId
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSeedIdIfExistingEntropy
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSolanaAccountsFromSeedVaultUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.ImportSolanaAccountsUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SaveAlgo25Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SaveFalcon24Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SaveFalcon25Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25Entropy
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25PrivateKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25Seed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SaveHdKeyAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SyncSolanaAccountsFromSeedVaultUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.ValidateWatchAccountUseCase
import com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase
import org.koin.dsl.module

val localAccountsModule =
    module {

        single<Algo25Dao> { get<AlgoKitDatabase>().algo25Dao() }
        single<Algo25EntityMapper> { Algo25EntityMapperImpl() }
        single<Algo25Mapper> { Algo25MapperImpl() }

        single<Algo25AccountRepository> {
            Algo25AccountRepositoryImpl(
                algo25Dao = get(),
                algo25EntityMapper = get(),
                algo25Mapper = get(),
                // coroutineDispatcher uses default Dispatchers.IO
            )
        }

        factory { SaveAlgo25Account(get<Algo25AccountRepository>()::addAccount) }
        factory { GetAlgo25SecretKey(get<Algo25AccountRepository>()::getSecretKey) }

        single<Falcon25Dao> { get<AlgoKitDatabase>().falcon25Dao() }
        single<Falcon25EntityMapper> { Falcon25EntityMapperImpl() }
        single<Falcon25Mapper> { Falcon25MapperImpl() }
        single<Falcon25AccountRepository> { Falcon25AccountRepositoryImpl(get(), get(), get()) }
        factory { SaveFalcon25Account(get<Falcon25AccountRepository>()::addAccount) }
        factory { GetFalcon25PrivateKey(get<Falcon25AccountRepository>()::getPrivateKey) }
        factory { GetFalcon25Entropy(get<Falcon25AccountRepository>()::getEntropy) }
        factory { GetFalcon25Seed(get<Falcon25AccountRepository>()::getSeed) }

        single<Falcon24Dao> { get<AlgoKitDatabase>().falcon24Dao() }
        single<Falcon24EntityMapper> { Falcon24EntityMapperImpl() }
        single<Falcon24Mapper> { Falcon24MapperImpl() }

        single<Falcon24AccountRepository> {
            Falcon24AccountRepositoryImpl(
                hdSeedDao = get(),
                falcon24Dao = get(),
                falcon24EntityMapper = get(),
                falcon24Mapper = get(),
                hdSeedWalletSummaryMapper = get(),
                // coroutineDispatcher uses default Dispatchers.IO
            )
        }

        factory { SaveFalcon24Account(get<Falcon24AccountRepository>()::addAccount) }
        factory { GetFalcon24SecretKey(get<Falcon24AccountRepository>()::getSecretKey) }
        single { GetFalcon24WalletSummaries(get<Falcon24AccountRepository>()::getHdWalletSummaries) }
        single<Falcon24WalletSummaryMapper> { Falcon24WalletSummaryMapperImpl() }
        single<Falcon24Mapper> { Falcon24MapperImpl() }

        single<HdKeyDao> { get<AlgoKitDatabase>().hdKeyDao() }
        single<HdKeyEntityMapper> { HdKeyEntityMapperImpl() }
        single<HdWalletSummaryMapper> { HdWalletSummaryMapperImpl() }
        single<HdKeyMapper> { HdKeyMapperImpl() }

        single<HdKeyAccountRepository> {
            HdKeyAccountRepositoryImpl(
                hdKeyDao = get(),
                hdKeyEntityMapper = get(),
                hdWalletSummaryMapper = get(),
                hdKeyMapper = get(),
                hdSeedDao = get(),
                hdSeedWalletSummaryMapper = get(),
                // coroutineDispatcher uses default Dispatchers.IO
            )
        }
        factory { GetHdKeyPrivateKey(get<HdKeyAccountRepository>()::getPrivateKey) }
        single { SaveHdKeyAccount(get<HdKeyAccountRepository>()::addAccount) }
        single { GetHdWalletSummaries(get<HdKeyAccountRepository>()::getHdWalletSummaries) }

        single<AddHdKeyAccount> { AddHdKeyAccountUseCase(get(), get()) }

        single<HdSeedDao> { get<AlgoKitDatabase>().hdSeedDao() }
        single<HdSeedEntityMapper> { HdSeedEntityMapperImpl() }
        single<HdSeedMapper> { HdSeedMapperImpl() }
        single<HdSeedRepository> {
            HdSeedRepositoryImpl(
                hdSeedDao = get(),
                hdSeedEntityMapper = get(),
                hdSeedMapper = get(),
                // coroutineDispatcher uses default Dispatchers.IO
            )
        }
        factory { GetSeedIdIfExistingEntropy(get<HdSeedRepository>()::getSeedIdIfExistingEntropy) }
        single<AddHdSeed> { AddHdSeedUseCase(get(), get(), get()) }
        single { GetMaxHdSeedId(get<HdSeedRepository>()::getMaxSeedId) }
        single { GetHdEntropy(get<HdSeedRepository>()::getEntropy) }
        single<HdSeedWalletSummaryMapper> { HdSeedWalletSummaryMapperImpl() }
        single { GetHdSeed(get<HdSeedRepository>()::getSeed) }

        // NoAuth (Watch Account) dependencies
        single<NoAuthDao> { get<AlgoKitDatabase>().noAuthDao() }
        single<NoAuthEntityMapper> { NoAuthEntityMapperImpl() }
        single<NoAuthMapper> { NoAuthMapperImpl() }

        single<NoAuthAccountRepository> {
            NoAuthAccountRepositoryImpl(
                noAuthDao = get(),
                noAuthEntityMapper = get(),
                noAuthMapper = get(),
                // coroutineDispatcher uses default Dispatchers.IO
            )
        }

        single { CreateWatchAccountUseCase(get(), get(), get()) }
        single { DeleteNoAuthAccountUseCase(get()) }
        single { ValidateWatchAccountUseCase(get()) }
        single<GetAllHdSeedFirstAddresses> { GetAllHdSeedFirstAddressesUseCase(get(), get(), get()) }

        // Solana Account dependencies
        single { get<AlgoKitDatabase>().solanaAccountDao() }
        single<SolanaAccountEntityMapper> { SolanaAccountEntityMapperImpl() }
        single<SolanaAccountMapper> { SolanaAccountMapperImpl() }

        single<SolanaAccountRepository> {
            SolanaAccountRepositoryImpl(
                solanaAccountDao = get(),
                solanaAccountEntityMapper = get(),
                solanaAccountMapper = get(),
                // coroutineDispatcher uses default Dispatchers.IO
            )
        }

        // SeedVaultRepository is provided in platform-specific solanaAccountModule
        single { GetSolanaAccountsFromSeedVaultUseCase(get()) }
        single { GetImportedSolanaAddressesUseCase(get()) }
        single { ImportSolanaAccountsUseCase(get()) }
        single { SyncSolanaAccountsFromSeedVaultUseCase(get(), get()) }
        single { DeleteSolanaAccountUseCase(get()) }
    }
