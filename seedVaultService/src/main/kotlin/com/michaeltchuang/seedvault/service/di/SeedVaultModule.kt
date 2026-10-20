package com.michaeltchuang.seedvault.service.di

import com.michaeltchuang.seedvault.service.data.auth.OsUserAuthenticator
import com.michaeltchuang.seedvault.service.data.auth.TrustyUserAuthenticator
import com.michaeltchuang.seedvault.service.data.auth.UserAuthenticators
import com.michaeltchuang.seedvault.service.data.database.SeedVaultDatabase
import com.michaeltchuang.seedvault.service.data.repository.SeedVaultRepositoryImpl
import com.michaeltchuang.seedvault.service.data.vault.RoomSeedKeyVault
import com.michaeltchuang.seedvault.service.data.vault.TrustySeedKeyVault
import com.michaeltchuang.seedvault.service.domain.repository.SeedVaultRepository
import com.michaeltchuang.seedvault.service.domain.usecase.AddSeed
import com.michaeltchuang.seedvault.service.domain.usecase.AuthorizeApp
import com.michaeltchuang.seedvault.service.domain.usecase.DeleteSeed
import com.michaeltchuang.seedvault.service.domain.usecase.GenerateSeedPhrase
import com.michaeltchuang.seedvault.service.domain.usecase.GenerateSeedPhraseUseCase
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeedPhrase
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeeds
import com.michaeltchuang.seedvault.service.domain.usecase.RenameSeed
import com.michaeltchuang.seedvault.service.domain.usecase.RenameSeedUseCase
import com.michaeltchuang.seedvault.service.domain.usecase.RevokeAuthorizedApp
import com.michaeltchuang.seedvault.service.domain.usecase.RevokeAuthorizedAppUseCase
import com.michaeltchuang.seedvault.service.domain.usecase.SignBytes
import com.michaeltchuang.seedvault.service.domain.usecase.ValidateSeedPhrase
import com.michaeltchuang.seedvault.service.domain.usecase.ValidateSeedPhraseUseCase
import com.michaeltchuang.seedvault.service.remote.SignApprovalCoordinator
import com.michaeltchuang.seedvault.service.ui.viewmodel.AddSeedViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.AuthorizeAppViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.AuthorizedAppsViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.ImportSeedViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.RevealSeedPhraseViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedDetailsViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedPhraseViewModel
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedVaultSettingsViewModel
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val seedVaultModule =
    module {
        // Data
        single { SeedVaultDatabase.build(androidContext()) }
        single { get<SeedVaultDatabase>().seedDao() }
        single { RoomSeedKeyVault() }
        single { TrustySeedKeyVault() }
        single { UserAuthenticators(trusty = TrustyUserAuthenticator(), os = OsUserAuthenticator(androidContext())) }
        single<SeedVaultRepository> { SeedVaultRepositoryImpl(get(), get<RoomSeedKeyVault>(), get<TrustySeedKeyVault>()) }

        single { SignApprovalCoordinator() }

        // Domain
        factory { GetSeeds(get<SeedVaultRepository>()::getSeeds) }
        factory { GetSeed(get<SeedVaultRepository>()::getSeed) }
        factory<GenerateSeedPhrase> { GenerateSeedPhraseUseCase() }
        factory<ValidateSeedPhrase> { ValidateSeedPhraseUseCase() }
        factory { AddSeed(get<SeedVaultRepository>()::addSeed) }
        factory { GetSeedPhrase(get<SeedVaultRepository>()::getSeedPhrase) }
        factory { AuthorizeApp(get<SeedVaultRepository>()::authorizeApp) }
        factory { SignBytes(get<SeedVaultRepository>()::signBytes) }
        factory<RenameSeed> { RenameSeedUseCase(get()) }
        factory { DeleteSeed(get<SeedVaultRepository>()::deleteSeed) }
        factory<RevokeAuthorizedApp> { RevokeAuthorizedAppUseCase(get()) }

        // UI
        viewModel {
            SeedVaultSettingsViewModel(
                getSeeds = get(),
                stateDelegate = StateDelegate(),
            )
        }
        viewModel { (seedId: Long) ->
            SeedDetailsViewModel(
                seedId = seedId,
                getSeed = get(),
                renameSeed = get(),
                deleteSeed = get(),
                stateDelegate = StateDelegate(),
                eventDelegate = EventDelegate(),
            )
        }
        viewModel { (appPackageName: String, appLabel: String) ->
            AuthorizeAppViewModel(
                appPackageName = appPackageName,
                appLabel = appLabel,
                getSeeds = get(),
                authorizeApp = get(),
                stateDelegate = StateDelegate(),
                eventDelegate = EventDelegate(),
            )
        }
        viewModel { (seedId: Long) ->
            AuthorizedAppsViewModel(
                seedId = seedId,
                getSeed = get(),
                revokeAuthorizedApp = get(),
                stateDelegate = StateDelegate(),
            )
        }
        viewModel {
            AddSeedViewModel(
                generateSeedPhrase = get(),
                addSeed = get(),
                stateDelegate = StateDelegate(),
                eventDelegate = EventDelegate(),
            )
        }
        viewModel {
            ImportSeedViewModel(
                validateSeedPhrase = get(),
                addSeed = get(),
                stateDelegate = StateDelegate(),
                eventDelegate = EventDelegate(),
            )
        }
        viewModel {
            RevealSeedPhraseViewModel(
                stateDelegate = StateDelegate(),
            )
        }
        viewModel { (seedId: Long) ->
            SeedPhraseViewModel(
                seedId = seedId,
                getSeed = get(),
                getSeedPhrase = get(),
                stateDelegate = StateDelegate(),
                eventDelegate = EventDelegate(),
            )
        }
    }
