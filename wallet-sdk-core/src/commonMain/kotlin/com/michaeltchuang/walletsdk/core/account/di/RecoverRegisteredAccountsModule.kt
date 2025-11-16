package com.michaeltchuang.walletsdk.core.account.di

import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationHdKeyTypeMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.DefaultAccountCreationHdKeyTypeMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.DefaultHdAccountAddressMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.DefaultRegisteredHdKeyItemMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.DefaultRegisteredHdKeyMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdAccountAddressMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.RegisteredHdKeyItemMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.RegisteredHdKeyMapper
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.RecoverRegisteredAccountsAccountProcessor
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountFastLookupBatch
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetActiveHdAccountAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetActiveHdAccounts
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccountsAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccountsAddressesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetRegisteredHdKeys
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.DefaultRecoverRegisteredAccountsAccountProcessor
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.GetAccountFastLookupBatchUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.GetActiveHdAccountAddressesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.GetActiveHdAccountsUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.GetRegisteredHdKeysUseCase
import org.koin.dsl.module

val recoverRegisteredAccountsModule = module {

    single<GetLocalAccountsAddresses> {
        GetLocalAccountsAddressesUseCase(
            get(),
            get(),
            get(),
            get()
        )
    }

    // Recovery passphrase related dependencies
    single<HdAccountAddressMapper> { DefaultHdAccountAddressMapper() }
    single<GetAccountFastLookupBatch> { GetAccountFastLookupBatchUseCase(get()) }
    single<GetActiveHdAccounts> { GetActiveHdAccountsUseCase(get(), get()) }
    single<GetActiveHdAccountAddresses> { GetActiveHdAccountAddressesUseCase(get(), get()) }

    // Mappers
    single<AccountCreationHdKeyTypeMapper> { DefaultAccountCreationHdKeyTypeMapperImpl() }
    single<RegisteredHdKeyItemMapper> { DefaultRegisteredHdKeyItemMapper() }
    single<RegisteredHdKeyMapper> { DefaultRegisteredHdKeyMapper() }

    // Use Cases
    single<GetRegisteredHdKeys> { GetRegisteredHdKeysUseCase(get(), get(), get(), get()) }

    // Processors
    single<RecoverRegisteredAccountsAccountProcessor> {
        DefaultRecoverRegisteredAccountsAccountProcessor(
            getRegisteredHdKeys = get<GetRegisteredHdKeys>(),
            registeredHdKeyItemMapper = get<RegisteredHdKeyItemMapper>()
        )
    }
}