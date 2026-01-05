package com.michaeltchuang.walletsdk.core.foundation.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityProvidersFactoryImplTest {
    private val sut = SecurityProvidersFactoryImpl()

    @Test
    fun `EXPECT BouncyCastleProvider with highest priority`() {
        val result = sut.getProviders()

        assertTrue(result.size == 1)
        assertTrue(result.first().provider is BouncyCastleProvider)
        assertTrue(result.first().priority == 0)
    }
}
