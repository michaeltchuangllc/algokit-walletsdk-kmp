package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.CustomAccountInfoEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomAccountInfoMapperImplTest {
    private val sut = CustomAccountInfoMapperImpl()

    @Test
    fun `EXPECT CustomAccountInfo to be created from entity with all values`() {
        val entity =
            CustomAccountInfoEntity(
                algoAddress = ADDRESS,
                customName = CUSTOM_NAME,
                orderIndex = ORDER_INDEX,
                isBackedUp = IS_BACKED_UP,
            )

        val result = sut(ADDRESS, entity)

        assertEquals(ADDRESS, result.address)
        assertEquals(CUSTOM_NAME, result.customName)
        assertEquals(ORDER_INDEX, result.orderIndex)
        assertEquals(IS_BACKED_UP, result.isBackedUp)
    }

    @Test
    fun `EXPECT CustomAccountInfo with defaults WHEN entity is null`() {
        val result = sut(ADDRESS, null)

        assertEquals(ADDRESS, result.address)
        assertEquals(null, result.customName)
        assertEquals(0, result.orderIndex)
        assertEquals(false, result.isBackedUp)
    }

    @Test
    fun `EXPECT CustomAccountInfo with null custom name WHEN entity has null name`() {
        val entity =
            CustomAccountInfoEntity(
                algoAddress = ADDRESS,
                customName = null,
                orderIndex = ORDER_INDEX,
                isBackedUp = IS_BACKED_UP,
            )

        val result = sut(ADDRESS, entity)

        assertEquals(ADDRESS, result.address)
        assertEquals(null, result.customName)
        assertEquals(ORDER_INDEX, result.orderIndex)
        assertEquals(IS_BACKED_UP, result.isBackedUp)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val CUSTOM_NAME = "My Wallet"
        const val ORDER_INDEX = 1
        const val IS_BACKED_UP = true
    }
}
