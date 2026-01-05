package com.michaeltchuang.walletsdk.core.passkeys.validator.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonElement

internal interface GStaticApiService {
    suspend fun getPrivilegedAppAllowlist(): JsonElement?
}

internal class KtorGStaticApiService(
    private val httpClient: HttpClient,
) : GStaticApiService {
    override suspend fun getPrivilegedAppAllowlist(): JsonElement? =
        try {
            httpClient
                .get("https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json")
                .body<JsonElement>()
        } catch (e: Exception) {
            null
        }
}
