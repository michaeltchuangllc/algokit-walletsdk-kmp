package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Provides a configured OkHttpClient for FIDO2 API communication
 *
 * This use case encapsulates the HTTP client configuration including:
 * - Cookie jar for session management
 * - Connection, read, and write timeouts
 * - Any other HTTP client configurations needed for Liquid Auth
 *
 * @property provideCookieJarUseCase Use case that provides the cookie jar
 * @property timeoutSeconds The timeout in seconds for all HTTP operations (default: 30)
 */
class ProvideHttpClientUseCase(
    private val provideCookieJarUseCase: ProvideCookieJarUseCase,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .cookieJar(provideCookieJarUseCase())
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Creates and returns a configured OkHttpClient instance
     *
     * @return Configured OkHttpClient with cookie support and timeouts
     */
    operator fun invoke(): OkHttpClient = client
}
