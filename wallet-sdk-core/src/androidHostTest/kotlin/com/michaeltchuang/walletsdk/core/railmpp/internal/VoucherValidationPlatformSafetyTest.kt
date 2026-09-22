package com.michaeltchuang.walletsdk.core.railmpp.internal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoucherValidationPlatformSafetyTest {
    @Test
    fun `EXPECT validation to only compile, build, and simulate WHEN checking both platform sources for wallet-signing calls`() {
        val module = sequenceOf(File("."), File("wallet-sdk-core")).first { File(it, "src/commonMain").isDirectory }
        for (platform in listOf("android", "ios")) {
            val source =
                File(
                    module,
                    "src/${platform}Main/kotlin/com/michaeltchuang/walletsdk/core/railmpp/internal/AlgorandOps.$platform.kt",
                ).readText()
            val validation =
                source
                    .substringAfter("internal actual suspend fun validateLogicSigSettlementInternal(")
                    .substringBefore("private suspend fun executeLogicSigSettlement(")
            assertTrue("buildVoucherVerifierTeal(" in validation)
            assertTrue("buildVoucherValidationGroup(" in validation)
            assertTrue("buildVoucherValidationRequest(" in validation)
            assertTrue("requireVerifiedVoucherSimulation(" in validation)
            assertTrue("funderSigner.address" in validation)
            assertTrue(
                if (platform == "android") {
                    "\"/v2/transactions/simulate\"" in validation
                } else {
                    "syncSimulateTransactionWithAlgodUrl(" in validation
                },
            )
            for (forbidden in listOf(
                "signTransactionBytes",
                "signTransactionsBytes",
                "signTxnGroup",
                "broadcast",
                "ensureLogicSigSetup(",
                "fundLogicSigIfNeeded(",
                "executeLogicSigSettlement(",
                "funderSigner.authorizedSignerPublicKey",
                "funderSigner.signerType",
                "buildAppCallTxn",
            )) {
                assertFalse(forbidden in validation, "$platform validation contains $forbidden")
            }
            assertFalse("/v2/teal/dryrun" in source)
            assertFalse("syncDryrunTransaction" in source)
        }
    }
}
