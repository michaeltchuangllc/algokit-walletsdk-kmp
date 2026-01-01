package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Algo25EntityMapperImpl.
 * Note: These tests require platform-specific encryption infrastructure to be initialized.
 * They will be skipped if encryption is not available in the test environment.
 */
class Algo25EntityMapperImplTest {
    private val sut = Algo25EntityMapperImpl()

    @Test
    fun `EXPECT Algo25Entity to be created from LocalAccount`() {
        val localAccount =
            LocalAccount.Algo25(
                algoAddress = ADDRESS,
            )

        val result = sut(localAccount, PRIVATE_KEY)

        assertEquals(ADDRESS, result.algoAddress)
        // Verify that the private key was encrypted (matches expected encrypted value)
        assertTrue(result.encryptedSecretKey.contentEquals(encryptByteArray(PRIVATE_KEY)))
    }

    @Test
    fun `EXPECT encrypted secret key to be different from original private key`() {
        val localAccount =
            LocalAccount.Algo25(
                algoAddress = ADDRESS,
            )

        val result = sut(localAccount, PRIVATE_KEY)

        // The encrypted key should not match the original private key
        assertTrue(!result.encryptedSecretKey.contentEquals(PRIVATE_KEY))
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        val PRIVATE_KEY = "test_private_key_12345".encodeToByteArray()
    }
}
