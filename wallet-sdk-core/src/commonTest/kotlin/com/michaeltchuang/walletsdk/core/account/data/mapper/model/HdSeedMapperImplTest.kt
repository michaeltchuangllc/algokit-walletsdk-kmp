package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.HdSeedEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class HdSeedMapperImplTest {
    private val sut = HdSeedMapperImpl()

    @Test
    fun `EXPECT HdSeed to be created from HdSeedEntity`() {
        val entity =
            HdSeedEntity(
                seedId = SEED_ID,
                encryptedEntropy = ENCRYPTED_ENTROPY,
                encryptedSeed = ENCRYPTED_SEED,
            )

        val result = sut(entity)

        assertEquals(SEED_ID, result.seedId)
    }

    private companion object {
        const val SEED_ID = 123
        val ENCRYPTED_ENTROPY = "encrypted_entropy".encodeToByteArray()
        val ENCRYPTED_SEED = "encrypted_seed".encodeToByteArray()
    }
}
