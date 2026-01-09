package com.michaeltchuang.walletsdk.core.passkeys.di

import app.perawallet.deterministicP256.DeterministicP256
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdEntropy
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.passkeys.CreatePublicKeyCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.DefaultCreatePublicKeyCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.DefaultGetCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.GetCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.builder.DefaultPasskeyCreateCredentialEntryBuilder
import com.michaeltchuang.walletsdk.core.passkeys.builder.DefaultPasskeyGetCredentialsEntryBuilder
import com.michaeltchuang.walletsdk.core.passkeys.builder.PasskeyCreateCredentialEntryBuilder
import com.michaeltchuang.walletsdk.core.passkeys.builder.PasskeyGetCredentialsEntryBuilder
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.DefaultPasskeyEntityMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.DefaultPasskeyMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.PasskeyEntityMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.PasskeyMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.repository.DefaultPasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.Bip39SignManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.DeterministicBip39SignManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskeyUseCase
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.ClearAllPasskeys
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.DoesPasskeyExist
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetAllPasskeysAsFlow
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetPasskeyByCredentialId
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetSitePasskeyCount
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetSitePasskeys
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.RemovePasskeyByCredentialId
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.core.passkeys.foundation.CoseMapper
import com.michaeltchuang.walletsdk.core.passkeys.foundation.DefaultCoseMapper
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val passkeyModule =
    module {
        // Passkey DAOs from AlgoKitDatabase
        single { get<com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase>().passkeyDao() }
        single { get<com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase>().passkeySiteDao() }

        // Repository
        singleOf(::DefaultPasskeyRepository) bind PasskeyRepository::class

        // Mappers
        singleOf(::DefaultPasskeyMapper) bind PasskeyMapper::class
        singleOf(::DefaultPasskeyEntityMapper) bind PasskeyEntityMapper::class
        singleOf(::DefaultCoseMapper) bind CoseMapper::class

        // Builders
        singleOf(::DefaultPasskeyCreateCredentialEntryBuilder) bind PasskeyCreateCredentialEntryBuilder::class
        singleOf(::DefaultPasskeyGetCredentialsEntryBuilder) bind PasskeyGetCredentialsEntryBuilder::class

        // Use Cases
        factory<AddNewPasskey> { AddNewPasskeyUseCase(get()) }

        factory<GetSitePasskeyCount> {
            GetSitePasskeyCount(get<PasskeyRepository>()::getSitePasskeysCount)
        }

        factory<GetSitePasskeys> {
            GetSitePasskeys(get<PasskeyRepository>()::getSitePasskeys)
        }

        factory<GetPasskeyByCredentialId> {
            GetPasskeyByCredentialId(get<PasskeyRepository>()::getPasskey)
        }

        factory<GetAllPasskeysAsFlow> {
            GetAllPasskeysAsFlow(get<PasskeyRepository>()::getAllPasskeysAsFlow)
        }

        factory<RemovePasskeyByCredentialId> {
            RemovePasskeyByCredentialId(get<PasskeyRepository>()::removePasskeyByCredentialId)
        }

        factory<ClearAllPasskeys> {
            ClearAllPasskeys(get<PasskeyRepository>()::clearAllPasskeys)
        }

        factory<SetPasskeyLastUsedTime> {
            SetPasskeyLastUsedTime(get<PasskeyRepository>()::setPasskeyLastUsedTime)
        }

        factory<DoesPasskeyExist> {
            DoesPasskeyExist(get<PasskeyRepository>()::doesPasskeyExist)
        }

        // TODO: Bip39SignManager depends on actual implementation
        single<Bip39SignManager> {
            DeterministicBip39SignManager(
                DeterministicP256(),
                get<GetAllHdSeedFirstAddresses>(),
                get<GetHdEntropy>(),
            )
        }

        // View Model Processors
        single<GetCredentialResponseProcessor> {
            DefaultGetCredentialResponseProcessor(
                get<Bip39SignManager>(),
                get<SetPasskeyLastUsedTime>(),
                get<TimeProvider>(),
            )
        }
        single<CreatePublicKeyCredentialResponseProcessor> {
            DefaultCreatePublicKeyCredentialResponseProcessor(get())
        }
    }
