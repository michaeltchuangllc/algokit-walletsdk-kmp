
package com.michaeltchuang.walletsdk.core.passkeys.validator.di

import com.michaeltchuang.walletsdk.core.passkeys.validator.CallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.PasskeyCallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.AssetLinksApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.GStaticApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.repository.DefaultAppInfoValidationRepository
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.repository.AppInfoValidationRepository
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.GetCallingAppOriginCheckingGpmAllowlist
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.GetCallingAppOriginCheckingGpmAllowlistUseCase
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.IsAssetLinksValid
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.IsAssetLinksValidUseCase
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

val validationModule = module {

    // Repository
    singleOf(::DefaultAppInfoValidationRepository) bind AppInfoValidationRepository::class

    // API Services
    single<GStaticApiService> {
        val okhttpClient = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
        Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("https://www.gstatic.com/")
            .client(okhttpClient)
            .build()
            .create(GStaticApiService::class.java)
    }

    single<AssetLinksApiService> {
        val okhttpClient = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
        Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("https://digitalassetlinks.googleapis.com/")
            .client(okhttpClient)
            .build()
            .create(AssetLinksApiService::class.java)
    }

    // Use Cases
    singleOf(::GetCallingAppOriginCheckingGpmAllowlistUseCase) bind GetCallingAppOriginCheckingGpmAllowlist::class
    singleOf(::IsAssetLinksValidUseCase) bind IsAssetLinksValid::class

    // Validator
    singleOf(::PasskeyCallingAppInfoValidator) bind CallingAppInfoValidator::class
}
