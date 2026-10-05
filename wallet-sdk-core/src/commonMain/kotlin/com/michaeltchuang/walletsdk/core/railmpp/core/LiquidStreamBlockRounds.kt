package com.michaeltchuang.walletsdk.core.railmpp.core

import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

internal fun liquidStreamBlockRounds(network: String): Flow<Long> =
    flow {
        val url = HostViewerVaultReader.networkConfig(network).algodUrl
        val client = HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val getCurrentBlock = GetCurrentBlockUseCase(client)
        try {
            while (true) {
                val round =
                    try {
                        withTimeout(10.seconds) {
                            when (val result = getCurrentBlock(url).first { it !is DataResource.Loading }) {
                                is DataResource.Success -> result.data
                                is DataResource.Error -> throw result.exception ?: IllegalStateException("Block lookup failed")
                                is DataResource.Loading -> error("Expected block lookup result")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Napier.w("Liquid Stream block lookup timed out on $network", e)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Napier.w("Liquid Stream block lookup failed on $network", e)
                        null
                    }
                emit(round ?: -1L)
                delay(1.seconds)
            }
        } finally {
            client.close()
        }
    }
