package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon24Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Falcon24MapperImplTest {
    private val sut = Falcon24MapperImpl()

    @Test
    fun `EXPECT LocalAccount Falcon24 to be created from Falcon24Entity`() {
        val entity =
            Falcon24Entity(
                algoAddress = ADDRESS,
                seedId = SEED_ID,
                publicKey = PUBLIC_KEY,
                encryptedSecretKey = ENCRYPTED_SECRET_KEY,
            )

        val result = sut(entity)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(SEED_ID, result.seedId)
        assertTrue(result.publicKey.contentEquals(PUBLIC_KEY))
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val SEED_ID = 1
        val PUBLIC_KEY = "falcon24_public_key".encodeToByteArray()
        val ENCRYPTED_SECRET_KEY = "encrypted_secret_key".encodeToByteArray()
    }
}
