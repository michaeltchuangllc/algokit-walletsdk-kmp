package com.michaeltchuang.walletsdk.core.railmpp.internal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoucherValidationSourceTest {
    @Test
    fun platformValidationNeverSignsSponsorFundsOnChainOrBroadcasts() {
        for (platform in listOf("android", "ios")) {
            val relative = "src/${platform}Main/kotlin/com/michaeltchuang/walletsdk/core/railmpp/internal/AlgorandOps.$platform.kt"
            val file = listOf(File(relative), File("wallet-sdk-core/$relative")).first { it.isFile }
            val validation = file.readText()
                .substringAfter("internal actual suspend fun validateLogicSigSettlementInternal(")
                .substringBefore("private suspend fun executeLogicSigSettlement(")
            assertTrue("buildVoucherValidationGroup(" in validation)
            assertTrue("buildVoucherValidationRequest(envelopes)" in validation)
            assertTrue("requireVerifiedVoucherSimulation(response" in validation)
            assertTrue(if (platform == "android") "\"/v2/transactions/simulate\"" in validation else "syncSimulateTransactionWithAlgodUrl" in validation)
            for (forbidden in listOf(
                "broadcast", "Broadcast", "signTransactionBytes", "signTransactionsBytes",
                "ensureLogicSigSetup", "fundLogicSigIfNeeded", "dryrun", "Dryrun", "executeLogicSigSettlement(",
            )) {
                assertFalse(forbidden in validation, "$platform validation contains $forbidden")
            }
        }
    }
}
