package com.michaeltchuang.walletsdk.core.foundation.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.security.Provider

class AlgoKitSecurityManagerImplTest {
    private val securityManager: SecurityManager = mockk(relaxed = true)
    private val securityProvidersFactory: SecurityProvidersFactory = mockk()

    private val sut = AlgoKitSecurityManagerImpl(securityManager, securityProvidersFactory)

    @Test
    fun `EXPECT security providers to be registered`() {
        every { securityProvidersFactory.getProviders() } returns
            listOf(
                SECURITY_PROVIDER_1,
                SECURITY_PROVIDER_2,
            )

        sut.initializeSecurityManager()

        verify { securityManager.registerProvider(SECURITY_PROVIDER_1) }
        verify { securityManager.registerProvider(SECURITY_PROVIDER_2) }
    }

    private companion object {
        val SECURITY_PROVIDER_1 =
            SecurityProvider(
                provider = object : Provider("1", 1.0, "1") {},
                priority = 1,
            )
        val SECURITY_PROVIDER_2 =
            SecurityProvider(
                provider = object : Provider("2", 1.0, "2") {},
                priority = 2,
            )
    }
}
