package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import kotlin.test.Test
import kotlin.test.assertEquals

class NoAuthEntityMapperImplTest {
    private val sut = NoAuthEntityMapperImpl()

    @Test
    fun `EXPECT NoAuthEntity to be created from LocalAccount NoAuth`() {
        val localAccount =
            LocalAccount.NoAuth(
                algoAddress = ADDRESS,
            )

        val result = sut(localAccount)

        assertEquals(ADDRESS, result.algoAddress)
    }

    @Test
    fun `EXPECT NoAuthEntity to be created from address string`() {
        val result = sut(ADDRESS)

        assertEquals(ADDRESS, result.algoAddress)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
    }
}
