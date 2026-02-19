package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.AssetDetailResponse
import com.michaeltchuang.walletsdk.core.network.utils.getPeraWalletBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

class AssetDetailApiServiceImpl(
    private val httpClient: HttpClient,
) : AssetDetailApiService {
    override suspend fun getAssetDetail(
        assetId: Long,
    ): ApiResult<AssetDetailResponse> =
        try {
            val response: HttpResponse =
                httpClient.get("${getPeraWalletBaseUrl()}/v1/assets/$assetId/")

            when {
                response.status.isSuccess() -> {
                    val assetDetail = response.body<AssetDetailResponse>()
                    ApiResult.Success(assetDetail)
                }

                response.status == HttpStatusCode.NotFound -> {
                    ApiResult.Error(
                        code = response.status.value,
                        message = "Asset not found: $assetId",
                    )
                }

                else -> {
                    val errorMessage =
                        try {
                            response.body<String>()
                        } catch (e: Exception) {
                            "HTTP ${response.status.value}: ${response.status.description}"
                        }

                    ApiResult.Error(
                        code = response.status.value,
                        message = errorMessage,
                    )
                }
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
}
