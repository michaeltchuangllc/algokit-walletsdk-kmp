package com.michaeltchuang.walletsdk.core.network.usecase

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.NfdProfile
import com.michaeltchuang.walletsdk.core.network.service.NfdApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetNfdProfileForAddressUseCaseTest {
    private val nfdApiService: NfdApiService = mockk()

    private val sut = GetNfdProfileForAddressUseCase(nfdApiService)

    @Test
    fun `EXPECT NfdProfile with name michaeltchuang_algo WHEN mainnet address has a registered NFD`() =
        runTest {
            val profile =
                NfdProfile(
                    address = MAINNET_ADDRESS,
                    name = NFD_NAME,
                    avatarUrl = AVATAR_URL,
                )
            coEvery { nfdApiService.getNfdForAddress(MAINNET_ADDRESS) } returns ApiResult.Success(profile)

            val result = sut(MAINNET_ADDRESS)

            assertEquals(profile, result)
            assertEquals(NFD_NAME, result?.name)
            assertEquals(AVATAR_URL, result?.avatarUrl)
        }

    @Test
    fun `EXPECT null WHEN no NFD is registered for the address`() =
        runTest {
            coEvery { nfdApiService.getNfdForAddress(MAINNET_ADDRESS) } returns
                ApiResult.Error(code = 404, message = "No NFD found for address: $MAINNET_ADDRESS")

            val result = sut(MAINNET_ADDRESS)

            assertNull(result)
        }

    @Test
    fun `EXPECT null WHEN the lookup fails with a non-404 API error`() =
        runTest {
            coEvery { nfdApiService.getNfdForAddress(MAINNET_ADDRESS) } returns
                ApiResult.Error(code = 500, message = "Internal Server Error")

            val result = sut(MAINNET_ADDRESS)

            assertNull(result)
        }

    @Test
    fun `EXPECT null WHEN the lookup fails with a network error`() =
        runTest {
            coEvery { nfdApiService.getNfdForAddress(MAINNET_ADDRESS) } returns
                ApiResult.NetworkError(Exception("Unable to reach NFD API"))

            val result = sut(MAINNET_ADDRESS)

            assertNull(result)
        }

    private companion object {
        const val MAINNET_ADDRESS = "QB3QBPWWKVYJ5W5KFBAR6W5TCEUZ6FGRRNAROBZR4CGZ3ZDPZDNHJLSPBI"
        const val NFD_NAME = "michaeltchuang.algo"
        const val AVATAR_URL = "https://images.nf.domains/avatar/9f1940b9-2cf5-43cb-96e5-f3b36385fc90"
    }
}
