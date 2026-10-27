package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultAccount
import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlgorandSeedVaultSeedMapperTest {
    private val seed =
        SeedVaultSeed(
            id = 7L,
            name = "Main seed",
            accounts =
                listOf(
                    SeedVaultAccount(1, "ADDRESS_ONE", "PUBKEY_ONE", false, false),
                    SeedVaultAccount(0, "ADDRESS_ZERO", "PUBKEY_ZERO", false, false),
                ),
        ).toAlgorandSeedVaultSeed()

    @Test
    fun keepsAllAccountsWithTheirIndex() {
        assertEquals(listOf(1, 0), seed.accounts.map { it.index })
    }

    @Test
    fun primaryAccountIsIndexZero() {
        assertEquals("ADDRESS_ZERO", seed.primaryAccount?.address)
        assertEquals("PUBKEY_ZERO", seed.primaryAccount?.publicKey)
    }

    @Test
    fun accountAtLooksUpByIndexNotPosition() {
        assertEquals("ADDRESS_ONE", seed.accountAt(1)?.address)
        assertNull(seed.accountAt(2))
    }
}
