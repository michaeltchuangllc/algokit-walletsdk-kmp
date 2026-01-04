package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Provides a CookieJar implementation for HTTP client
 * 
 * This use case encapsulates cookie management for FIDO2 API communication,
 * handling cookie storage and retrieval for maintaining sessions.
 */
class ProvideCookieJarUseCase {
    /**
     * Creates and returns a CookieJar instance
     * 
     * @return CookieJar implementation for session management
     */
    operator fun invoke(): CookieJar {
        return CookieJarImpl()
    }
    
    /**
     * Internal CookieJar implementation
     */
    private class CookieJarImpl : CookieJar {
        private val storage: MutableList<Cookie> = ArrayList()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            storage.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // Remove expired cookies
            storage.removeIf { cookie -> cookie.expiresAt < System.currentTimeMillis() }
            
            // Return cookies that match the URL
            return storage.filter { cookie -> cookie.matches(url) }
        }
    }
}
