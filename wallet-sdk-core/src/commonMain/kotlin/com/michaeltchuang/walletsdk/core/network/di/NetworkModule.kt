package com.michaeltchuang.walletsdk.core.network.di

import com.michaeltchuang.walletsdk.core.account.data.mapper.model.AccountFastLookupMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.AccountFastLookupMapperImpl
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountFastLookup
import com.michaeltchuang.walletsdk.core.network.domain.NodePreferenceRepository
import com.michaeltchuang.walletsdk.core.network.domain.provideNodePreferenceRepository
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.SaveNetworkPreferenceUseCase
import com.michaeltchuang.walletsdk.core.network.service.AccountFastLookupApiService
import com.michaeltchuang.walletsdk.core.network.service.AccountFastLookupRepositoryImpl
import com.michaeltchuang.walletsdk.core.network.service.AccountInformationApiService
import com.michaeltchuang.walletsdk.core.network.service.AccountInformationApiServiceImpl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val networkModule =
    module {
        single<HttpClient> {
            HttpClient {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            coerceInputValues = true
                        },
                    )
                }

                install(Logging) {
                    logger =
                        object : Logger {
                            override fun log(message: String) {
                                println("HTTP Client: $message")
                            }
                        }
                    level = LogLevel.ALL
                }
            }
        }

        single<AccountInformationApiService> {
            AccountInformationApiServiceImpl(
                httpClient = get(),
            )
        }

        single<NodePreferenceRepository> {
            provideNodePreferenceRepository()
        }

        factoryOf(::GetCurrentNetworkUseCase)
        factoryOf(::SaveNetworkPreferenceUseCase)

        single<AccountFastLookupMapper> {
            AccountFastLookupMapperImpl()
        }

        single<AccountFastLookupApiService> {
            AccountFastLookupRepositoryImpl(
                httpClient = get(),
                accountFastLookupMapper = get()
            )
        }
        single { GetAccountFastLookup(get<AccountFastLookupApiService>()::getAccountFastLookup) }

    }
