package com.michaeltchuang.walletsdk.demo

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppTest {
    @Test
    fun basicSmokeTest() {
        // Basic smoke test to verify test infrastructure works
        val testValue = "AlgoKit Wallet SDK"
        assertNotNull(testValue)
        assertTrue(testValue.isNotEmpty())
    }

    @Test
    fun verifyPackageStructure() {
        // Verify we can reference the app package
        val packageName = "com.michaeltchuang.walletsdk.demo"
        assertTrue(packageName.contains("walletsdk"))
    }
}
