package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModelStore
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class LiquidAuthViewerConsentStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var holder: LiquidAuthViewerStateHolder
    private val terms =
        ConsentTerms(
            gatingMode = GatingMode.PARTIAL_TIME,
            amount = "1000",
            asset = "USDC",
            network = "algorand-testnet",
            segmentDuration = 3,
        )
    private val approval =
        ConsentApproval(
            approved = true,
            autoPaySegments = true,
            budgetCap = BudgetCap(amount = "1000000", asset = "USDC"),
        )
    private val rejection = ConsentApproval(approved = false, autoPaySegments = false)

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
    fun pendingDuplicatesAreRejectedWithoutReplacingOrSharingApproval() =
        scenario {
            val first = request()
            val identicalDuplicate = request()
            val changedDuplicate = request(terms.copy(amount = "2000"))

            assertEquals(rejection, identicalDuplicate.await())
            assertEquals(rejection, changedDuplicate.await())
            assertFalse(first.isCompleted)
            assertSame(terms, holder.pendingViewerConsent.value)

            holder.showPendingViewerConsent(terms.copy(amount = "3000"))
            assertSame(terms, holder.pendingViewerConsent.value)
            holder.approveViewerConsent(approval)

            assertEquals(approval, first.await())
            assertNull(holder.pendingViewerConsent.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun cancelledOldRequestFinallyDoesNotClearReplacement() =
        scenario {
            val old = request()
            holder.clearViewerConsent()
            val replacementTerms = terms.copy(amount = "2000")
            val replacement = request(replacementTerms)
            assertSame(replacementTerms, holder.pendingViewerConsent.value)

            runCurrent()

            assertTrue(old.isCancelled)
            assertFalse(replacement.isCompleted)
            assertSame(replacementTerms, holder.pendingViewerConsent.value)
            holder.approveViewerConsent(approval)
            assertEquals(approval, replacement.await())
        }

    @Test
    fun callerCancellationClearsPendingRequestWithoutStartingProcessing() =
        scenario {
            val pending = request()
            pending.cancel()
            runCurrent()

            assertTrue(pending.isCancelled)
            assertNull(holder.pendingViewerConsent.value)
            holder.approveViewerConsent(approval)
            assertFalse(holder.isViewerPaymentProcessing.value)

            val replacement = request()
            holder.approveViewerConsent(approval)
            assertEquals(approval, replacement.await())
        }

    @Test
    fun directApprovalPreservesRealBalancesAndWorksWhileProcessing() =
        scenario {
            holder.setViewerSessionVaultProgress(7_000_000L, 9_000_000L)
            val pending = request()
            assertFalse(pending.isCompleted)
            assertSame(terms, holder.pendingViewerConsent.value)
            holder.setViewerPaymentProcessing(true)

            holder.approveMppConsent(approval)

            assertEquals(approval, pending.await())
            assertEquals(7_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(9_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun viewerApprovalPreservesRealBalancesWithoutAutomaticConsent() =
        scenario {
            holder.setViewerSessionVaultProgress(7_000_000L, 9_000_000L)
            val pending = request()
            assertFalse(pending.isCompleted)
            assertFalse(holder.isViewerPaymentProcessing.value)

            holder.approveViewerConsent(approval)

            assertEquals(approval, pending.await())
            assertEquals(7_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(9_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun fundedApprovalRemainsAvailableWhileProcessing() =
        scenario {
            holder.setViewerSessionVaultProgress(7_000_000L, 9_000_000L)
            val pending = request()
            holder.setViewerPaymentProcessing(true)

            holder.approveFundedViewerConsent(7_000_000L)

            assertEquals(approval.copy(budgetCap = BudgetCap("7000000", "USDC")), pending.await())
            assertEquals(7_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(9_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun rapidApprovalsCompleteOnceAndProcessingLastsUntilManagerCallback() =
        scenario {
            val processing = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                holder.isViewerPaymentProcessing.collect { processing += it }
            }
            val pending = request()
            var completions = 0
            var processingAtCompletion = false
            launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                pending.await()
                completions++
                processingAtCompletion = holder.isViewerPaymentProcessing.value
            }

            holder.approveViewerConsent(approval)
            assertTrue(holder.isViewerPaymentProcessing.value)
            assertNull(holder.pendingViewerConsent.value)
            repeat(10) {
                holder.approveViewerConsent(approval.copy(budgetCap = BudgetCap("2000000", "USDC")))
            }
            runCurrent()

            assertEquals(approval, pending.await())
            assertEquals(1, completions)
            assertTrue(processingAtCompletion)
            assertEquals(listOf(false, true), processing)
            assertEquals(rejection, request().await())
            holder.showPendingViewerConsent(terms)
            assertNull(holder.pendingViewerConsent.value)
            holder.rejectMppConsent()
            assertTrue(holder.isViewerPaymentProcessing.value)

            holder.setViewerPaymentProcessing(false)
            holder.approveViewerConsent(approval)
            assertFalse(holder.isViewerPaymentProcessing.value)
            val next = request()
            assertFalse(next.isCompleted)
            holder.approveViewerConsent(approval)
            assertEquals(approval, next.await())
            assertEquals(listOf(false, true, false, true), processing)
        }

    @Test
    fun viewerApprovalDoesNotCompleteActiveConsentWhileProcessing() =
        scenario {
            val pending = request()
            holder.setViewerPaymentProcessing(true)

            holder.approveViewerConsent(approval)

            assertFalse(pending.isCompleted)
            assertSame(terms, holder.pendingViewerConsent.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
            holder.setViewerPaymentProcessing(false)
            holder.approveViewerConsent(approval)
            assertEquals(approval, pending.await())
        }

    @Test
    fun clearCancelsPendingConsentAndResetsProcessing() =
        scenario {
            val pending = request()
            holder.setViewerPaymentProcessing(true)

            holder.clearViewerConsent()
            runCurrent()

            assertTrue(pending.isCancelled)
            assertNull(holder.pendingViewerConsent.value)
            assertFalse(holder.isViewerPaymentProcessing.value)
            val replacement = request()
            holder.approveViewerConsent(approval)
            assertEquals(approval, replacement.await())
        }

    @Test
    fun rejectionNeverStartsPaymentProcessing() =
        scenario {
            val pending = request()

            holder.approveViewerConsent(rejection)

            assertEquals(rejection, pending.await())
            assertNull(holder.pendingViewerConsent.value)
            assertFalse(holder.isViewerPaymentProcessing.value)
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

    private fun CoroutineScope.request(requestTerms: ConsentTerms = terms) =
        async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            holder.requestMppConsentFromUi(requestTerms)
        }
}
