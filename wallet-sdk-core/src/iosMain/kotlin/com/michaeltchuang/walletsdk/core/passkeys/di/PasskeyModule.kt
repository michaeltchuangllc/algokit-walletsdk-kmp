package com.michaeltchuang.walletsdk.core.passkeys.di

import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.DefaultPasskeyEntityMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.DefaultPasskeyMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.PasskeyEntityMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.PasskeyMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.repository.DefaultPasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val passkeyModule =
    module {
        // Passkey DAOs from AlgoKitDatabase
        single { get<com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase>().passkeyDao() }
        single { get<com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase>().passkeySiteDao() }

        // Mappers
        singleOf(::DefaultPasskeyMapper) bind PasskeyMapper::class
        singleOf(::DefaultPasskeyEntityMapper) bind PasskeyEntityMapper::class
        // Repository
        singleOf(::DefaultPasskeyRepository) bind PasskeyRepository::class
    }
