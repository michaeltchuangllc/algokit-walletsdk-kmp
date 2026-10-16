package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.AssetCreatorResponse
import com.michaeltchuang.walletsdk.core.network.model.AssetDetailResponse
import com.michaeltchuang.walletsdk.core.network.utils.getIndexerBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AssetDetailApiServiceImpl(
    private val httpClient: HttpClient,
) : AssetDetailApiService {
    override suspend fun getAssetDetail(assetId: Long): ApiResult<AssetDetailResponse> =
        try {
            val response: HttpResponse =
                httpClient.get("${getIndexerBaseUrl()}/v2/assets/$assetId")

            when {
                response.status.isSuccess() -> {
                    val assetDetail = response.body<IndexerAssetResponse>().toAssetDetailResponse()
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

@Serializable
private data class IndexerAssetResponse(
    val asset: IndexerAsset,
)

@Serializable
private data class IndexerAsset(
    val index: Long,
    val params: IndexerAssetParams,
)

@Serializable
private data class IndexerAssetParams(
    val name: String? = null,
    @SerialName("unit-name") val unitName: String? = null,
    val decimals: Int? = null,
    val total: ULong? = null,
    val creator: String? = null,
    val url: String? = null,
)

private fun IndexerAssetResponse.toAssetDetailResponse(): AssetDetailResponse =
    AssetDetailResponse(
        assetId = asset.index,
        fullName = asset.params.name,
        shortName = asset.params.unitName,
        fractionDecimals = asset.params.decimals,
        maxSupply = asset.params.total?.toString(),
        assetCreator = asset.params.creator?.let(::AssetCreatorResponse),
        url = asset.params.url,
    )
