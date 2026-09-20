package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModelStore
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiquidAuthViewerConsentTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var holder: LiquidAuthViewerStateHolder
    private val terms =
        ConsentTerms(
            gatingMode = GatingMode.PARTIAL_TIME,
            amount = "1000000",
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
    fun duplicateRequestsAreRejectedWithoutReplacingOrSharingPendingApproval() =
        scenario {
            val first = request(terms)
            val sameTerms = request(terms)
            val differentTerms = request(terms.copy(amount = "2000000"))

            assertEquals(ConsentApproval(false, false), sameTerms.await())
            assertEquals(ConsentApproval(false, false), differentTerms.await())
            assertFalse(first.isCompleted)
            assertEquals(terms, holder.pendingViewerConsent.value)

            holder.approveViewerConsent(approval)

            assertEquals(approval, first.await())
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun cancelledOldRequestFinallyDoesNotClearReplacement() =
        scenario {
            val old = request(terms)
            val replacementTerms = terms.copy(amount = "2000000")
            val replacement =
                async(start = CoroutineStart.UNDISPATCHED) {
                    holder.requestMppConsentFromUi(replacementTerms)
                }

            holder.clearViewerConsent()
            runCurrent()

            assertTrue(old.isCancelled)
            assertFalse(replacement.isCompleted)
            assertEquals(replacementTerms, holder.pendingViewerConsent.value)
            assertFalse(holder.isViewerPaymentProcessing.value)

            holder.approveViewerConsent(approval)

            assertEquals(approval, replacement.await())
            assertNull(holder.pendingViewerConsent.value)
        }

    @Test
    fun callerCancellationClearsPendingConsentAndAllowsAnotherRequest() =
        scenario {
            val cancelled = request(terms)
            cancelled.cancel()
            runCurrent()

            assertNull(holder.pendingViewerConsent.value)
            assertFalse(holder.isViewerPaymentProcessing.value)

            val next = request(terms.copy(amount = "2000000"))
            holder.approveViewerConsent(approval)

            assertEquals(approval, next.await())
        }

    @Test
    fun directApprovalPreservesRealBalancesAndWorksWhileProcessing() =
        scenario {
            holder.setViewerSessionVaultProgress(7_000_000L, 9_000_000L)
            val pending = request(terms)
            assertFalse(pending.isCompleted)
            holder.setViewerPaymentProcessing(true)

            holder.approveMppConsent(approval)

            assertEquals(approval, pending.await())
            assertEquals(7_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(9_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertTrue(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun fundedApprovalPreservesBalancesWithoutStartingFunding() =
        scenario {
            holder.setViewerSessionVaultProgress(7_000_000L, 9_000_000L)
            val pending = request(terms)

            holder.approveFundedViewerConsent(7_000_000L)

            assertEquals(approval.copy(budgetCap = BudgetCap("7000000", "USDC")), pending.await())
            assertEquals(7_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(9_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertFalse(holder.isViewerPaymentProcessing.value)
        }

    @Test
    fun rapidApprovalsCompleteOnlyOnceAndProcessingLastsUntilManagerCallback() =
        scenario {
            holder.setViewerSessionVaultProgress(7_000_000L, 9_000_000L)
            var approvedRequests = 0
            val pending =
                async {
                    holder.requestMppConsentFromUi(terms).also {
                        assertTrue(holder.isViewerPaymentProcessing.value)
                        if (it.approved) approvedRequests++
                    }
                }
            runCurrent()

            holder.approveViewerConsent(approval)
            assertTrue(holder.isViewerPaymentProcessing.value)
            assertNull(holder.pendingViewerConsent.value)
            repeat(10) {
                holder.approveViewerConsent(approval.copy(budgetCap = BudgetCap("2000000", "USDC")))
            }
            holder.rejectMppConsent()

            assertEquals(approval, pending.await())
            assertEquals(1, approvedRequests)
            assertTrue(holder.isViewerPaymentProcessing.value)
            assertEquals(7_000_000L, holder.viewerSessionVaultMicroUsdc.value)
            assertEquals(9_000_000L, holder.viewerProgressBalanceMicroUsdc.value)
            assertEquals(ConsentApproval(false, false), holder.requestMppConsentFromUi(terms))
            holder.showPendingViewerConsent(terms)
            assertNull(holder.pendingViewerConsent.value)

            holder.setViewerPaymentProcessing(false)
            holder.approveViewerConsent(approval)
            assertFalse(holder.isViewerPaymentProcessing.value)

            val next = request(terms)
            assertFalse(next.isCompleted)
            holder.approveViewerConsent(approval)
            assertEquals(approval, next.await())
        }

    @Test
    fun processingGuardDoesNotConsumeAnExistingActiveRequest() =
        scenario {
            val pending = request(terms)
            holder.setViewerPaymentProcessing(true)

            holder.approveViewerConsent(approval)

            assertFalse(pending.isCompleted)
            assertEquals(terms, holder.pendingViewerConsent.value)

            holder.setViewerPaymentProcessing(false)
            holder.approveViewerConsent(approval)
            assertEquals(approval, pending.await())
        }

    @Test
    fun clearCancelsPendingAndResetsProcessing() =
        scenario {
            val pending = request(terms)
            holder.setViewerPaymentProcessing(true)

            holder.clearViewerConsent()
            runCurrent()

            assertTrue(pending.isCancelled)
            assertFalse(holder.isViewerPaymentProcessing.value)
            assertNull(holder.pendingViewerConsent.value)
            holder.approveViewerConsent(approval)
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

    private fun TestScope.request(requestTerms: ConsentTerms) =
        async { holder.requestMppConsentFromUi(requestTerms) }.also { runCurrent() }
}
