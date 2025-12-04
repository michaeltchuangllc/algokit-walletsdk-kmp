package com.michaeltchuang.walletsdk.core.passkeys.validator.data.network

import retrofit2.http.GET
import retrofit2.http.Query

internal interface AssetLinksApiService {

    @GET("v1/assetlinks:check")
    suspend fun getAssetLinksCheckResult(
        @Query("source.web.site") websiteUrl: String,
        @Query("target.android_app.package_name") packageName: String,
        @Query("target.android_app.certificate.sha256_fingerprint") certification: String,
        @Query("relation") relation: String = "delegate_permission/common.handle_all_urls"
    ): String
}
