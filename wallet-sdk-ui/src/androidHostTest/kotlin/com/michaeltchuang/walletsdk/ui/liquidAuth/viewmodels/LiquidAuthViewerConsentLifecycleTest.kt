package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModelStore
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiquidAuthViewerConsentLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var holder: LiquidAuthViewerStateHolder
    private val approved = ConsentApproval(approved = true, autoPaySegments = true)
    private val rejected = ConsentApproval(approved = false, autoPaySegments = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        holder = LiquidAuthViewerStateHolder()
        store.put("viewer-consent", holder)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun duplicateRequestsRejectWithoutReplacingOrSharingPendingApproval() =
        scenario {
            val originalTerms = terms()
            val original = request(originalTerms)
            val identical = request(originalTerms)
            val different = request(terms("2000000"))

            assertTrue(identical.isCompleted)
            assertTrue(different.isCompleted)
            assertEquals(rejected, identical.await())
            assertEquals(rejected, different.await())
            assertFalse(original.isCompleted)
            assertSame(originalTerms, holder.pendingViewerConsent.value)

            holder.approveViewerConsent(approved)
            runCurrent()

            assertTrue(original.isCompleted)
            assertEquals(approved, original.await())
            assertNull(holder.pendingViewerConsent.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun staleCancellationFinallyCannotClearReplacementRequest() =
        scenario {
            val original = request()
            holder.clearViewerConsent()
            val replacementTerms = terms("2000000")
            val replacement = request(replacementTerms)

            assertSame(replacementTerms, holder.pendingViewerConsent.value)
            runCurrent()

            assertTrue(original.isCancelled)
            assertSame(replacementTerms, holder.pendingViewerConsent.value)
            assertFalse(replacement.isCompleted)
            holder.approveViewerConsent(approved)
            runCurrent()

            assertTrue(replacement.isCompleted)
            assertEquals(approved, replacement.await())
            assertNull(holder.pendingViewerConsent.value)
        }

    @Test
    fun cancellingRequesterClearsPendingAndAllowsAnotherRequest() =
        scenario {
            val original = request()
            original.cancel()
            holder.approveViewerConsent(approved)
            assertFalse(holder.isViewerPaymentProcessing.value)
            runCurrent()

            assertTrue(original.isCancelled)
            assertNull(holder.pendingViewerConsent.value)
            val replacement = request()
            holder.approveViewerConsent(approved)
            runCurrent()

            assertTrue(replacement.isCompleted)
            assertEquals(approved, replacement.await())
        }

    @Test
    fun positiveVaultBalancesDoNotAutoApproveAndSurviveExternalApproval() =
        scenario {
            holder.setViewerSessionVaultProgress(9_000_000L, 12_000_000L)
            val pending = request()
            runCurrent()

            assertFalse(pending.isCompleted)
            assertEquals(terms(), holder.pendingViewerConsent.value)
            holder.setViewerPaymentProcessing(true)
            holder.approveMppConsent(approved)
            runCurrent()

            assertTrue(pending.isCompleted)
            assertEquals(approved, pending.await())
            assertEquals(9_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(12_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun fundedApprovalRemainsAvailableWhileProcessing() =
        scenario {
            holder.setViewerSessionVaultProgress(9_000_000L, 12_000_000L)
            val pending = request()
            holder.setViewerPaymentProcessing(true)
            holder.approveFundedViewerConsent(9_000_000L)
            runCurrent()

            assertTrue(pending.isCompleted)
            val result = pending.await()
            assertTrue(result.approved)
            assertEquals("9000000", result.budgetCap?.amount)
            assertEquals(9_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(12_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun rapidApprovalsResolveOnceAndProcessingLastsUntilManagerCallback() =
        scenario {
            holder.setViewerSessionVaultProgress(9_000_000L, 12_000_000L)
            val pending = request()
            holder.approveViewerConsent(approved)

            assertTrue(holder.isViewerPaymentProcessing.value)
            assertNull(holder.pendingViewerConsent.value)
            repeat(10) {
                holder.approveViewerConsent(rejected)
                holder.approveViewerConsent(approved)
                holder.rejectMppConsent()
            }
            runCurrent()

            assertTrue(pending.isCompleted)
            assertEquals(approved, pending.await())
            assertTrue(holder.isViewerPaymentProcessing.value)
            assertEquals(9_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(12_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            val duringFunding = request()
            assertTrue(duringFunding.isCompleted)
            assertEquals(rejected, duringFunding.await())
            holder.showPendingViewerConsent(terms())
            assertNull(holder.pendingViewerConsent.value)
            assertTrue(holder.isViewerPaymentProcessing.value)

            holder.setViewerPaymentProcessing(false)
            holder.approveViewerConsent(approved)
            assertFalse(holder.isViewerPaymentProcessing.value)
            val next = request()
            holder.approveViewerConsent(approved)
            assertTrue(holder.isViewerPaymentProcessing.value)
            runCurrent()
            assertTrue(next.isCompleted)
            assertEquals(approved, next.await())
        }

    @Test
    fun processingGuardLeavesActiveConsentForExternalCompletion() =
        scenario {
            val pending = request()
            holder.setViewerPaymentProcessing(true)
            holder.approveViewerConsent(approved)

            assertFalse(pending.isCompleted)
            assertEquals(terms(), holder.pendingViewerConsent.value)
            holder.approveMppConsent(approved)
            runCurrent()
            assertTrue(pending.isCompleted)
            assertEquals(approved, pending.await())
        }

    @Test
    fun clearCancelsPendingAndResetsProcessing() =
        scenario {
            val pending = request()
            holder.setViewerPaymentProcessing(true)
            holder.clearViewerConsent()

            assertFalse(holder.isViewerPaymentProcessing.value)
            assertNull(holder.pendingViewerConsent.value)
            runCurrent()
            assertTrue(pending.isCancelled)
            val next = request()
            holder.rejectMppConsent()
            runCurrent()
            assertTrue(next.isCompleted)
            assertEquals(rejected, next.await())
        }

    private fun scenario(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                holder.clearViewerConsent()
                store.clear()
                runCurrent()
            }
        }

    private fun TestScope.request(requestTerms: ConsentTerms = terms()) =
        async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            holder.requestMppConsentFromUi(requestTerms)
        }

    private fun terms(amount: String = "1000000") =
        ConsentTerms(
            gatingMode = GatingMode.PARTIAL_TIME,
            amount = amount,
            asset = "USDC",
            network = "algorand-testnet",
            segmentDuration = 3,
        )
}
