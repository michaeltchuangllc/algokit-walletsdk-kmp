package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.NfdLookupResponse
import com.michaeltchuang.walletsdk.core.network.model.NfdProfile
import com.michaeltchuang.walletsdk.core.network.utils.getNfdBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

internal class NfdApiServiceImpl(
    private val httpClient: HttpClient,
) : NfdApiService {
    override suspend fun getNfdForAddress(address: String): ApiResult<NfdProfile> =
        try {
            val response: HttpResponse =
                httpClient.get("${getNfdBaseUrl()}/nfd/lookup") {
                    parameter("address", address)
                    parameter("view", "thumbnail")
                    // Only match addresses that are cryptographically verified (caAlgo) on the
                    // NFD - simply owning/purchasing a domain isn't enough, since anyone could
                    // buy an NFD without ever proving control of the address it's displayed for.
                    parameter("allowUnverified", "false")
                }

            when {
                response.status.isSuccess() -> {
                    // /nfd/lookup always returns results keyed by the queried address (even for
                    // a single address), e.g. {"<address>": {"name": "example.algo", ...}}.
                    val nfdLookupResponse = response.body<Map<String, NfdLookupResponse>>()[address]
                    if (nfdLookupResponse != null) {
                        ApiResult.Success(nfdLookupResponse.toNfdProfile(address))
                    } else {
                        ApiResult.Error(
                            code = response.status.value,
                            message = "No NFD found for address: $address",
                        )
                    }
                }

                response.status == HttpStatusCode.NotFound -> {
                    ApiResult.Error(
                        code = response.status.value,
                        message = "No NFD found for address: $address",
                    )
                }

                else -> {
                    val errorMessage =
                        try {
                            response.body<String>()
                        } catch (_: Exception) {
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

private fun NfdLookupResponse.toNfdProfile(address: String): NfdProfile =
    NfdProfile(
        address = address,
        name = name,
        // Prefer the verified avatar (set by the NFD owner via an on-chain transaction) over the
        // unverified user-defined one.
        avatarUrl = properties?.verified?.avatar ?: properties?.userDefined?.avatar,
    )
