package com.michaeltchuang.walletsdk.core.account.di

import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationFalcon24TypeMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationHdKeyTypeMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25AccountTypeMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25AccountTypeMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.DefaultAccountCreationFalcon24TypeMapperImpl
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.DefaultAccountCreationHdKeyTypeMapperImpl
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AccountAdditionUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddAlgo25Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddAlgo25AccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddFalcon24Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AddFalcon24AccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.DeleteAlgo25AccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.DeleteFalcon24AccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.DeleteHdKeyAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetAccountRegistrationTypeUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountsUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteAlgo25Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteFalcon24Account
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonicUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.RecoverPassphraseUseCase
import org.koin.dsl.module

val accountCoreModule =
    module {
        single { AddAlgo25AccountUseCase(get(), get()) }

        factory<AddAlgo25Account> { get<AddAlgo25AccountUseCase>() }
        single { AccountAdditionUseCase(get(), get(), get(), get()) }

        single { DeleteAlgo25AccountUseCase(get(), get()) }
        single<DeleteAlgo25Account> { get<DeleteAlgo25AccountUseCase>() }

        single { AddFalcon24AccountUseCase(get(), get()) }

        factory<AddFalcon24Account> { get<AddFalcon24AccountUseCase>() }
        single { AccountAdditionUseCase(get(), get(), get(), get()) }

        single {
            DeleteFalcon24AccountUseCase(
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        single<DeleteFalcon24Account> { get<DeleteFalcon24AccountUseCase>() }

        single {
            DeleteHdKeyAccountUseCase(
                get(),
                get(),
                get(),
                get(),
            )
        }

        single { GetLocalAccountsUseCase(get(), get(), get(), get()) }
        single<GetLocalAccounts> { get<GetLocalAccountsUseCase>() }
        single { GetLocalAccountUseCase(get()) }
        single<GetLocalAccount> { get<GetLocalAccountUseCase>() }
        single { GetAccountMnemonicUseCase(get(), get(), get()) }
        single<GetAccountMnemonic> { get<GetAccountMnemonicUseCase>() }
        single { GetAccountRegistrationTypeUseCase(get()) }

        single {
            NameRegistrationUseCase(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        single<AccountCreationHdKeyTypeMapper> { DefaultAccountCreationHdKeyTypeMapperImpl() }
        single<AccountCreationFalcon24TypeMapper> { DefaultAccountCreationFalcon24TypeMapperImpl() }
        single<Algo25AccountTypeMapper> { Algo25AccountTypeMapperImpl() }
        single { RecoverPassphraseUseCase() }
    }
