package com.michaeltchuang.walletsdk.core.passkeys.validator.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

internal interface AssetLinksApiService {
    suspend fun getAssetLinksCheckResult(
        websiteUrl: String,
        packageName: String,
        certification: String,
        relation: String = "delegate_permission/common.handle_all_urls",
    ): String
}

internal class KtorAssetLinksApiService(
    private val httpClient: HttpClient,
) : AssetLinksApiService {
    override suspend fun getAssetLinksCheckResult(
        websiteUrl: String,
        packageName: String,
        certification: String,
        relation: String,
    ): String =
        httpClient
            .get("https://digitalassetlinks.googleapis.com/v1/assetlinks:check") {
                parameter("source.web.site", websiteUrl)
                parameter("target.android_app.package_name", packageName)
                parameter("target.android_app.certificate.sha256_fingerprint", certification)
                parameter("relation", relation)
            }.body()
}
