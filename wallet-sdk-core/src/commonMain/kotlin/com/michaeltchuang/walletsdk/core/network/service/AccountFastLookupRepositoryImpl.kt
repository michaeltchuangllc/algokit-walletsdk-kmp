/*
 * Copyright 2022-2025 Pera Wallet, LDA
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.account.data.mapper.model.AccountFastLookupMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.core.network.model.AccountFastLookupResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

internal class AccountFastLookupRepositoryImpl(
    private val httpClient: HttpClient,
    private val accountFastLookupMapper: AccountFastLookupMapper
) : AccountFastLookupApiService {

    override suspend fun getAccountFastLookup(address: String): AlgoKitResult<AccountFastLookup> {
        return try {
            val response: HttpResponse =
                httpClient.get("https://testnet.api.perawallet.app/v1/accounts/fast-lookup/$address")

            when {
                response.status.isSuccess() -> {
                    val accountInfo = response.body<AccountFastLookupResponse>()
                    val accountFastLookup = accountFastLookupMapper(accountInfo)
                    AlgoKitResult.Success(accountFastLookup)
                }

                response.status == HttpStatusCode.NotFound -> {
                    AlgoKitResult.Error(
                        exception = Exception("Account not found: $address"),
                        code = response.status.value,
                    )
                }

                else -> {
                    val errorMessage =
                        try {
                            response.body<String>()
                        } catch (e: Exception) {
                            "HTTP ${response.status.value}: ${response.status.description}"
                        }

                    AlgoKitResult.Error(
                        exception = Exception(errorMessage),
                        code = response.status.value,
                    )
                }
            }
        } catch (e: Exception) {
            AlgoKitResult.Error(
                exception = e,
                code = -1,
            )
        }
    }
}
