package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for HdSeedEntityMapperImpl.
 * Note: These tests require platform-specific encryption infrastructure to be initialized.
 * They will be skipped if encryption is not available in the test environment.
 */
class HdSeedEntityMapperImplTest {
    private val sut = HdSeedEntityMapperImpl()

    @Test
    fun `EXPECT HdSeedEntity to be created with encrypted values`() {
        val result =
            sut(
                seedId = SEED_ID,
                entropy = ENTROPY,
                seed = SEED,
            )

        // Room will auto-generate the ID, so it should be 0
        assertEquals(0, result.seedId)
        // Verify entropy and seed are encrypted (match expected encrypted values)
        assertTrue(result.encryptedEntropy.contentEquals(encryptByteArray(ENTROPY)))
        assertTrue(result.encryptedSeed.contentEquals(encryptByteArray(SEED)))
    }

    @Test
    fun `EXPECT encrypted values to be different from original`() {
        val result =
            sut(
                seedId = SEED_ID,
                entropy = ENTROPY,
                seed = SEED,
            )

        // The encrypted values should not match the originals
        assertTrue(!result.encryptedEntropy.contentEquals(ENTROPY))
        assertTrue(!result.encryptedSeed.contentEquals(SEED))
    }

    private companion object {
        const val SEED_ID = 123
        val ENTROPY = "test_entropy_value_12345".encodeToByteArray()
        val SEED = "test_seed_value_67890".encodeToByteArray()
    }
}
