package com.michaeltchuang.walletsdk.core.passkeys.validator.data.network

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.GET

internal interface GStaticApiService {
    @GET("gpm-passkeys-privileged-apps/apps.json")
    suspend fun getPrivilegedAppAllowlist(): Response<JsonElement>
}
