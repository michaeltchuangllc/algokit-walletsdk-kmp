package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class IceConnectionTypePricingTest {
    @Test
    fun costPerBlockScalesBaseByTransportTier() {
        assertEquals(8L, IceConnectionType.LOCAL.costPerBlockMicroUsdc(8L))
        assertEquals(16L, IceConnectionType.STUN.costPerBlockMicroUsdc(8L))
        assertEquals(80L, IceConnectionType.RELAY.costPerBlockMicroUsdc(8L))
    }

    @Test
    fun undetectedConnectionsChargeBaseRate() {
        assertEquals(8L, IceConnectionType.UNKNOWN.costPerBlockMicroUsdc(8L))
        assertEquals(8L, IceConnectionType.FAILED.costPerBlockMicroUsdc(8L))
    }

    @Test
    fun freeStreamStaysFreeOnEveryTier() {
        IceConnectionType.entries.forEach { assertEquals("0", it.scaleMicroUsdcAmount("0")) }
    }

    @Test
    fun scaleAmountLeavesNonNumericAmountsUntouched() {
        assertEquals("80", IceConnectionType.RELAY.scaleMicroUsdcAmount("8"))
        assertEquals("abc", IceConnectionType.RELAY.scaleMicroUsdcAmount("abc"))
    }

    @Test
    fun pricingTierIsStickyAcrossTransientReadings() {
        assertEquals(
            IceConnectionType.RELAY,
            resolvePricingConnectionType(IceConnectionType.RELAY, IceConnectionType.UNKNOWN),
        )
        assertEquals(
            IceConnectionType.STUN,
            resolvePricingConnectionType(IceConnectionType.STUN, IceConnectionType.FAILED),
        )
        assertEquals(
            IceConnectionType.LOCAL,
            resolvePricingConnectionType(IceConnectionType.RELAY, IceConnectionType.LOCAL),
        )
    }
}
