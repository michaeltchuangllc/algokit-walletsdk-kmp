package com.michaeltchuang.walletsdk.core.network.usecase

import com.michaeltchuang.walletsdk.core.network.utils.getNodeBaseUrl
import com.michaeltchuang.walletsdk.utils.DataResource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Use case for getting the current block number from the Algorand network.
 *
 * Queries the Algod API `/v2/status` endpoint and returns the last round (block number).
 */
class GetCurrentBlockUseCase(
    private val httpClient: HttpClient,
) {
    /**
     * Get the current block number
     *
     * @return Flow of DataResource containing the current block number (lastRound) as Long
     */
    operator fun invoke(): Flow<DataResource<Long>> =
        flow {
            emit(DataResource.Loading())

            try {
                val response: HttpResponse = httpClient.get("${getNodeBaseUrl()}/v2/status")

                when {
                    response.status.isSuccess() -> {
                        val statusResponse = response.body<NodeStatusResponse>()
                        emit(DataResource.Success(statusResponse.lastRound))
                    }

                    response.status == HttpStatusCode.NotFound -> {
                        emit(
                            DataResource.Error.Api(
                                exception = Exception("Node status endpoint not found"),
                                code = response.status.value,
                            ),
                        )
                    }

                    else -> {
                        val errorMessage =
                            try {
                                response.body<String>()
                            } catch (e: Exception) {
                                "HTTP ${response.status.value}: ${response.status.description}"
                            }

                        emit(
                            DataResource.Error.Api(
                                exception = Exception(errorMessage),
                                code = response.status.value,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                emit(
                    DataResource.Error.Api(
                        exception = e,
                        code = -1,
                    ),
                )
            }
        }
}

/**
 * Response from the Algod API /v2/status endpoint
 */
@Serializable
data class NodeStatusResponse(
    @SerialName("last-round") val lastRound: Long,
    @SerialName("last-version") val lastVersion: String? = null,
    @SerialName("next-version") val nextVersion: String? = null,
    @SerialName("next-version-round") val nextVersionRound: Long? = null,
    @SerialName("next-version-supported") val nextVersionSupported: Boolean? = null,
    @SerialName("stopped-at-unsupported-round") val stoppedAtUnsupportedRound: Boolean? = null,
    @SerialName("time-since-last-round") val timeSinceLastRound: Long? = null,
    @SerialName("upgrade-votes") val upgradeVotes: Long? = null,
    @SerialName("upgrade-votes-required") val upgradeVotesRequired: Long? = null,
    @SerialName("upgrade-node-votes") val upgradeNodeVotes: Long? = null,
    @SerialName("genesis-id") val genesisId: String? = null,
    @SerialName("genesis-hash") val genesisHashB64: String? = null,
    val catchpoint: String? = null,
    @SerialName("catchpoint-acquired-blocks") val catchpointAcquiredBlocks: Long? = null,
    @SerialName("catchpoint-processed-accounts") val catchpointProcessedAccounts: Long? = null,
    @SerialName("catchpoint-processed-kvs") val catchpointProcessedKvs: Long? = null,
    @SerialName("catchpoint-total-accounts") val catchpointTotalAccounts: Long? = null,
    @SerialName("catchpoint-total-blocks") val catchpointTotalBlocks: Long? = null,
    @SerialName("catchpoint-total-kvs") val catchpointTotalKvs: Long? = null,
    @SerialName("catchpoint-verified-accounts") val catchpointVerifiedAccounts: Long? = null,
    @SerialName("catchpoint-verified-kvs") val catchpointVerifiedKvs: Long? = null,
    @SerialName("last-catchpoint") val lastCatchpoint: String? = null,
    @SerialName("sync-time") val syncTime: Long? = null,
)
