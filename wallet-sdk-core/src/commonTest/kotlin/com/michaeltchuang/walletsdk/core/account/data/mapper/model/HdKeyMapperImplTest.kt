package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.HdKeyEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HdKeyMapperImplTest {
    private val sut = HdKeyMapperImpl()

    @Test
    fun `EXPECT LocalAccount HdKey to be created from HdKeyEntity`() {
        val entity =
            HdKeyEntity(
                algoAddress = ADDRESS,
                publicKey = PUBLIC_KEY,
                encryptedPrivateKey = ENCRYPTED_PRIVATE_KEY,
                seedId = SEED_ID,
                account = ACCOUNT,
                change = CHANGE,
                keyIndex = KEY_INDEX,
                derivationType = DERIVATION_TYPE,
            )

        val result = sut(entity)

        assertEquals(ADDRESS, result.address)
        assertTrue(result.publicKey.contentEquals(PUBLIC_KEY))
        assertEquals(SEED_ID, result.seedId)
        assertEquals(ACCOUNT, result.account)
        assertEquals(CHANGE, result.change)
        assertEquals(KEY_INDEX, result.keyIndex)
        assertEquals(DERIVATION_TYPE, result.derivationType)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        val PUBLIC_KEY = "public_key_12345".encodeToByteArray()
        val ENCRYPTED_PRIVATE_KEY = "encrypted_private_key".encodeToByteArray()
        const val SEED_ID = 1
        const val ACCOUNT = 0
        const val CHANGE = 0
        const val KEY_INDEX = 0
        const val DERIVATION_TYPE = 44 // BIP44
    }
}
