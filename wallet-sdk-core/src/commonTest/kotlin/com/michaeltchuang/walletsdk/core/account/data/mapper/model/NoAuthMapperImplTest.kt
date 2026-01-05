package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.NoAuthEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class NoAuthMapperImplTest {
    private val sut = NoAuthMapperImpl()

    @Test
    fun `EXPECT LocalAccount NoAuth to be created from NoAuthEntity`() {
        val entity =
            NoAuthEntity(
                algoAddress = ADDRESS,
            )

        val result = sut(entity)

        assertEquals(ADDRESS, result.algoAddress)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
    }
}
