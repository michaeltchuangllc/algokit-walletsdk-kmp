package com.michaeltchuang.walletsdk.core.passkeys.validator.di

import com.michaeltchuang.walletsdk.core.passkeys.validator.CallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.PasskeyCallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.AssetLinksApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.GStaticApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.KtorAssetLinksApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.KtorGStaticApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.repository.DefaultAppInfoValidationRepository
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.repository.AppInfoValidationRepository
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.GetCallingAppOriginCheckingGpmAllowlist
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.GetCallingAppOriginCheckingGpmAllowlistUseCase
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.IsAssetLinksValid
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.IsAssetLinksValidUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val validationModule =
    module {

        // HttpClient
        single {
            HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            prettyPrint = true
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }
                install(Logging) {
                    logger =
                        object : Logger {
                            override fun log(message: String) {
                                println("Ktor: $message")
                            }
                        }
                    level = LogLevel.BODY
                }
            }
        }

        // Repository
        singleOf(::DefaultAppInfoValidationRepository) bind AppInfoValidationRepository::class

        // API Services
        single<GStaticApiService> {
            KtorGStaticApiService(get())
        }

        single<AssetLinksApiService> {
            KtorAssetLinksApiService(get())
        }

        // Use Cases
        singleOf(::GetCallingAppOriginCheckingGpmAllowlistUseCase) bind GetCallingAppOriginCheckingGpmAllowlist::class
        singleOf(::IsAssetLinksValidUseCase) bind IsAssetLinksValid::class

        // Validator
        singleOf(::PasskeyCallingAppInfoValidator) bind CallingAppInfoValidator::class
    }
