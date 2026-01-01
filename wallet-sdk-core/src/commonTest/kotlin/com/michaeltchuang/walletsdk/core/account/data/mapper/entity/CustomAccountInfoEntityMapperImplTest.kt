package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.custom.CustomAccountInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomAccountInfoEntityMapperImplTest {
    private val sut = CustomAccountInfoEntityMapperImpl()

    @Test
    fun `EXPECT CustomAccountInfoEntity to be created from CustomAccountInfo`() {
        val customInfo =
            CustomAccountInfo(
                address = ADDRESS,
                customName = CUSTOM_NAME,
                orderIndex = ORDER_INDEX,
                isBackedUp = IS_BACKED_UP,
            )

        val result = sut(customInfo)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(CUSTOM_NAME, result.customName)
        assertEquals(ORDER_INDEX, result.orderIndex)
        assertEquals(IS_BACKED_UP, result.isBackedUp)
    }

    @Test
    fun `EXPECT CustomAccountInfoEntity to be created with null custom name`() {
        val customInfo =
            CustomAccountInfo(
                address = ADDRESS,
                customName = null,
                orderIndex = ORDER_INDEX,
                isBackedUp = IS_BACKED_UP,
            )

        val result = sut(customInfo)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(null, result.customName)
        assertEquals(ORDER_INDEX, result.orderIndex)
        assertEquals(IS_BACKED_UP, result.isBackedUp)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val CUSTOM_NAME = "My Wallet"
        const val ORDER_INDEX = 0
        const val IS_BACKED_UP = true
    }
}
