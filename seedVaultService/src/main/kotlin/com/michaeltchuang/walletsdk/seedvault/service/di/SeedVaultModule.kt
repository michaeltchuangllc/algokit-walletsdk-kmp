package com.michaeltchuang.walletsdk.seedvault.service.di

import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.seedvault.service.data.database.SeedVaultDatabase
import com.michaeltchuang.walletsdk.seedvault.service.data.repository.SeedVaultRepositoryImpl
import com.michaeltchuang.walletsdk.seedvault.service.domain.repository.SeedVaultRepository
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.AddSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.DeleteSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GenerateSeedPhrase
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GenerateSeedPhraseUseCase
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeedPhrase
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeeds
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.RenameSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.RenameSeedUseCase
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.RevokeAuthorizedApp
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.RevokeAuthorizedAppUseCase
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.SignBytes
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AddSeedViewModel
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AuthorizedAppsViewModel
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.RevealSeedPhraseViewModel
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.SeedDetailsViewModel
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.SeedPhraseViewModel
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.SeedVaultSettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val seedVaultModule =
    module {
        // Data
        single { SeedVaultDatabase.build(androidContext()) }
        single { get<SeedVaultDatabase>().seedDao() }
        single<SeedVaultRepository> { SeedVaultRepositoryImpl(get()) }

        // Domain
        factory { GetSeeds(get<SeedVaultRepository>()::getSeeds) }
        factory { GetSeed(get<SeedVaultRepository>()::getSeed) }
        factory<GenerateSeedPhrase> { GenerateSeedPhraseUseCase() }
        factory { AddSeed(get<SeedVaultRepository>()::addSeed) }
        factory { GetSeedPhrase(get<SeedVaultRepository>()::getSeedPhrase) }
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
