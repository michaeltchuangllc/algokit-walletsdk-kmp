package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.Algo25Entity
import kotlin.test.Test
import kotlin.test.assertEquals

class Algo25MapperImplTest {
    private val sut = Algo25MapperImpl()

    @Test
    fun `EXPECT LocalAccount Algo25 to be created from Algo25Entity`() {
        val entity =
            Algo25Entity(
                algoAddress = ADDRESS,
                encryptedSecretKey = ENCRYPTED_KEY,
            )

        val result = sut(entity)

        assertEquals(ADDRESS, result.address)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        val ENCRYPTED_KEY = "encrypted_key_12345".encodeToByteArray()
    }
}
