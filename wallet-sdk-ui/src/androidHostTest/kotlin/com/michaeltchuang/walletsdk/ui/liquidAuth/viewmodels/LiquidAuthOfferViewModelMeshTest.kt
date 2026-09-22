package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.LiquidAuthOffer
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel.OfferEvent
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel.OfferState
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel.PaymentState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiquidAuthOfferViewModelMeshTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val generateOfferUseCase = mockk<GenerateLiquidAuthOfferUseCase>()
    private val eventDelegate = mockk<EventDelegate<OfferEvent>>()
    private val getCurrentNetworkUseCase = mockk<GetCurrentNetworkUseCase>()
    private lateinit var vm: LiquidAuthOfferViewModel

    @Before
    fun setUp() {
        // stateIn and the network observer start during ViewModel construction.
        Dispatchers.setMain(dispatcher)
        every { getCurrentNetworkUseCase() } returns MutableStateFlow(AlgorandNetwork.TESTNET)
        coEvery { eventDelegate.sendEvent(any<OfferEvent>()) } returns Unit
        var invitationNumber = 0
        every { generateOfferUseCase.generateOffer(ORIGIN) } answers {
            offer(++invitationNumber)
        }
        vm =
            LiquidAuthOfferViewModel(
                generateOfferUseCase = generateOfferUseCase,
                stateDelegate = StateDelegate(),
                eventDelegate = eventDelegate,
                getAccountASABalance = mockk(),
                getCurrentBlockUseCase = mockk(),
                getCurrentNetworkUseCase = getCurrentNetworkUseCase,
                mppWalletSignerUseCase = mockk(),
            )
        store.put("mesh-host", vm)
        vm.meshHostingEnabled = true
    }

    @After
    fun tearDown() {
        try {
            // Cancels both the network collector and stateIn's sharing coroutine.
            store.clear()
            if (::vm.isInitialized) {
                assertFalse(vm.viewModelScope.isActive)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `EXPECT top-up baselines to be viewer-scoped and cleared WHEN a viewer disconnects`() {
        vm.generateOffer(ORIGIN)
        vm.onMeshViewerConnected("request-1")
        vm.onMeshViewerConnected("request-2")
        val first = HostViewerDetails(totalDepositMicroUsdc = 1_000_000, progressBalanceMicroUsdc = 200_000)
        vm.updateMeshViewerDetails("request-1", first)
        vm.updateMeshViewerDetails("request-2", first)
        val topUp = first.copy(totalDepositMicroUsdc = 2_000_000, progressBalanceMicroUsdc = 1_200_000)
        vm.updateMeshViewerDetails("request-2", topUp)
        assertEquals(200_000L, vm.meshViewerDetails.value["request-1"]?.progressCapacityMicroUsdc)
        assertEquals(1_200_000L, vm.meshViewerDetails.value["request-2"]?.progressCapacityMicroUsdc)
        vm.updateMeshViewerDetails("request-2", topUp.copy(progressBalanceMicroUsdc = 600_000))
        assertEquals(1_200_000L, vm.meshViewerDetails.value["request-2"]?.progressCapacityMicroUsdc)
        vm.onMeshViewerDisconnected("request-2")
        vm.updateMeshViewerDetails("request-2", topUp)
        assertNull(vm.meshViewerDetails.value["request-2"])
        vm.onMeshViewerConnected("request-2")
        vm.updateMeshViewerDetails("request-2", first)
        assertEquals(200_000L, vm.meshViewerDetails.value["request-2"]?.progressCapacityMicroUsdc)
        vm.clearMeshHosting()
        assertTrue(vm.meshViewerDetails.value.isEmpty())
    }

    @Test
    fun `EXPECT the QR to rotate without replacing the primary payment session WHEN a second viewer joins`() {
        vm.generateOffer(ORIGIN)
        assertEquals(invitation(1), vm.state.value)
        assertNull(vm.pendingMeshOffer.value)

        vm.onMeshViewerConnected("request-1")

        assertEquals(
            OfferState.Connected("request-1", offer(1).liquidAuthUrl, ORIGIN, "request-1"),
            vm.state.value,
        )
        assertEquals("request-1", vm.getCurrentSessionId())
        assertEquals(offer(1), vm.getCurrentOffer())
        assertEquals(listOf("request-1"), vm.meshViewerIds.value)
        assertEquals(invitation(2), vm.pendingMeshOffer.value)
        assertEquals(PaymentState.NoPayment, vm.paymentState.value)
        verify(exactly = 2) { generateOfferUseCase.generateOffer(ORIGIN) }
        coVerify(exactly = 1) { eventDelegate.sendEvent(OfferEvent.ClientConnected("request-1")) }
        coVerify(exactly = 1) { eventDelegate.sendEvent(ofType<OfferEvent.OfferGenerated>()) }

        vm.requestPaymentFromClient("creator")
        val primary = vm.state.value as OfferState.WaitingForPayment
        val payment = vm.paymentState.value as PaymentState.WaitingForDeposit
        assertSame(primary.paymentRequest, payment.paymentRequest)
        assertEquals("request-1", payment.paymentRequest.sessionId)

        vm.onMeshViewerConnected("request-2")

        assertHostPreserved(primary, payment)
        assertEquals(listOf("request-1", "request-2"), vm.meshViewerIds.value)
        assertEquals(invitation(3), vm.pendingMeshOffer.value)
        verify(exactly = 3) { generateOfferUseCase.generateOffer(ORIGIN) }
        coVerify(exactly = 1) { eventDelegate.sendEvent(ofType<OfferEvent.PaymentRequested>()) }
        coVerify(exactly = 1) { eventDelegate.sendEvent(ofType<OfferEvent.ClientConnected>()) }
    }

    @Test
    fun `EXPECT refresh and regenerate to only replace the invitation WHEN reentry preserves membership`() {
        startPaidHost()
        vm.onMeshViewerConnected("request-2")
        val primary = vm.state.value
        val payment = vm.paymentState.value

        vm.refreshMeshInvitation(ORIGIN)
        assertEquals(invitation(4), vm.pendingMeshOffer.value)
        assertHostPreserved(primary, payment)
        assertEquals(listOf("request-1", "request-2"), vm.meshViewerIds.value)

        vm.regenerateOffer(ORIGIN)
        assertEquals(invitation(5), vm.pendingMeshOffer.value)
        assertHostPreserved(primary, payment)
        assertEquals(listOf("request-1", "request-2"), vm.meshViewerIds.value)

        vm.generateOffer(ORIGIN)
        assertEquals(invitation(5), vm.pendingMeshOffer.value)
        assertHostPreserved(primary, payment)
        assertBalancesPreserved()
        assertEquals(listOf("request-1", "request-2"), vm.meshViewerIds.value)
        verify(exactly = 5) { generateOfferUseCase.generateOffer(ORIGIN) }
        coVerify(exactly = 1) { eventDelegate.sendEvent(ofType<OfferEvent.OfferGenerated>()) }
    }

    @Test
    fun `EXPECT no QR rotation or duplicate membership WHEN connection callbacks duplicate`() {
        connectPrimary()
        vm.onMeshViewerConnected("request-2")
        val primary = vm.state.value
        val pending = vm.pendingMeshOffer.value

        vm.onMeshViewerConnected("request-1")
        vm.onMeshViewerConnected("request-2")

        assertHostPreserved(primary, PaymentState.NoPayment)
        assertSame(pending, vm.pendingMeshOffer.value)
        assertEquals(listOf("request-1", "request-2"), vm.meshViewerIds.value)
        verify(exactly = 3) { generateOfferUseCase.generateOffer(ORIGIN) }
        coVerify(exactly = 1) { eventDelegate.sendEvent(ofType<OfferEvent.ClientConnected>()) }
    }

    @Test
    fun `EXPECT the stream and payment to remain WHEN the primary and last viewer leave`() {
        startPaidHost()
        vm.onMeshViewerConnected("request-2")
        val primary = vm.state.value
        val payment = vm.paymentState.value
        val pending = vm.pendingMeshOffer.value

        vm.onMeshViewerDisconnected("request-1")

        assertEquals(listOf("request-2"), vm.meshViewerIds.value)
        assertHostPreserved(primary, payment)
        assertBalancesPreserved()
        assertSame(pending, vm.pendingMeshOffer.value)

        vm.onMeshViewerDisconnected("request-2")

        assertTrue(vm.meshViewerIds.value.isEmpty())
        assertHostPreserved(primary, payment)
        assertBalancesPreserved()
        assertSame(pending, vm.pendingMeshOffer.value)
        coVerify(exactly = 0) { eventDelegate.sendEvent(OfferEvent.ClientDisconnected) }
        coVerify(exactly = 0) { eventDelegate.sendEvent(OfferEvent.VideoStreamingStopped) }
    }

    @Test
    fun `EXPECT only the matching QR to be invalidated without overwriting the live host WHEN an invitation fails`() {
        startPaidHost()
        val primary = vm.state.value
        val payment = vm.paymentState.value

        vm.onMeshInvitationFailed("request-2", "Invitation expired")

        assertNull(vm.pendingMeshOffer.value)
        assertEquals("Invitation expired", vm.meshError.value)
        assertHostPreserved(primary, payment)
        assertEquals(listOf("request-1"), vm.meshViewerIds.value)
        verify(exactly = 2) { generateOfferUseCase.generateOffer(ORIGIN) }

        vm.refreshMeshInvitation(ORIGIN)
        assertEquals(invitation(3), vm.pendingMeshOffer.value)
        val replacement = vm.pendingMeshOffer.value

        // A delayed failure for the old invitation, or a live peer, cannot hide the new QR.
        vm.onMeshInvitationFailed("request-2", "Late failure")
        assertSame(replacement, vm.pendingMeshOffer.value)
        vm.onMeshInvitationFailed("request-1", "Live peer error")
        assertSame(replacement, vm.pendingMeshOffer.value)
        assertEquals("Live peer error", vm.meshError.value)
        assertHostPreserved(primary, payment)
        assertBalancesPreserved()
        assertEquals(listOf("request-1"), vm.meshViewerIds.value)
        verify(exactly = 3) { generateOfferUseCase.generateOffer(ORIGIN) }
        coVerify(exactly = 0) { eventDelegate.sendEvent(ofType<OfferEvent.ShowError>()) }

        vm.dismissMeshError()
        assertNull(vm.meshError.value)
        assertSame(replacement, vm.pendingMeshOffer.value)
    }

    @Test
    fun `EXPECT details to only be accepted for connected requests and copy to preserve other fields`() {
        val details = HostViewerDetails(viewerAddress = "viewer-wallet", remainingBalanceMicroUsdc = 0L)
        vm.updateMeshViewerDetails("request-1", details)
        assertTrue(vm.meshViewerDetails.value.isEmpty())

        startPaidHost()
        vm.onMeshViewerConnected("request-2")
        val primary = vm.state.value
        val payment = vm.paymentState.value
        vm.updateMeshViewerDetails("request-1", HostViewerDetails(viewerAddress = "primary-wallet"))
        vm.updateMeshViewerDetails("request-2", details)
        vm.updateMeshViewerDetails(
            "request-2",
            vm.meshViewerDetails.value
                .getValue("request-2")
                .copy(connectionType = IceConnectionType.RELAY),
        )

        assertEquals(details.copy(connectionType = IceConnectionType.RELAY), vm.meshViewerDetails.value["request-2"])
        assertEquals("primary-wallet", vm.meshViewerDetails.value["request-1"]?.viewerAddress)
        assertHostPreserved(primary, payment)
        assertBalancesPreserved()
    }

    @Test
    fun `EXPECT only matching details to be removed and stale updates to be ignored WHEN a viewer disconnects`() {
        connectPrimary()
        vm.onMeshViewerConnected("request-2")
        val details = HostViewerDetails(viewerAddress = "viewer-wallet", progressBalanceMicroUsdc = 0L)
        vm.updateMeshViewerDetails("request-1", details)
        vm.updateMeshViewerDetails("request-2", details)

        vm.onMeshViewerDisconnected("request-1")
        vm.updateMeshViewerDetails("request-1", details.copy(connectionType = IceConnectionType.STUN))

        assertEquals(mapOf("request-2" to details), vm.meshViewerDetails.value)
        vm.onMeshViewerDisconnected("request-2")
        vm.updateMeshViewerDetails("request-2", details)
        assertTrue(vm.meshViewerDetails.value.isEmpty())
    }

    @Test
    fun `EXPECT details to clear and late callbacks to be rejected WHEN mesh hosting is cleared`() {
        connectPrimary()
        val details = HostViewerDetails(viewerAddress = "viewer-wallet")
        vm.updateMeshViewerDetails("request-1", details)

        vm.clearMeshHosting()
        vm.updateMeshViewerDetails("request-1", details)

        assertTrue(vm.meshViewerDetails.value.isEmpty())
        assertTrue(vm.meshViewerIds.value.isEmpty())
        assertNull(vm.pendingMeshOffer.value)
        assertTrue(vm.meshHostingEnabled)
    }

    private fun connectPrimary() {
        vm.generateOffer(ORIGIN)
        vm.onMeshViewerConnected("request-1")
    }

    private fun startPaidHost() {
        connectPrimary()
        vm.startPaidStreaming("creator")
        // Seed balances through the public callback, without native vaults or polling jobs.
        vm.consumeBlock(
            onChainRemainingMicroUsdc = 900_000L,
            progressBarBalanceMicroUsdc = 800_000L,
            lastSettledMicroUsdc = 100_000L,
            paidBlocks = 2,
            freeBlocks = 1,
        )
        assertTrue(vm.state.value is OfferState.Streaming)
        assertTrue(vm.paymentState.value is PaymentState.StreamingWithBalance)
    }

    private fun assertHostPreserved(
        primary: OfferState,
        payment: PaymentState,
    ) {
        assertSame(primary, vm.state.value)
        assertSame(payment, vm.paymentState.value)
        assertEquals("request-1", vm.getCurrentSessionId())
        assertEquals(offer(1), vm.getCurrentOffer())
    }

    private fun assertBalancesPreserved() {
        assertEquals(900_000L, vm.remainingBalanceMicroUsdc.value)
        assertEquals(800_000L, vm.progressBarBalanceMicroUsdc.value)
        assertEquals(100_000L, vm.lastSettledMicroUsdc.value)
    }

    private fun offer(number: Int) =
        LiquidAuthOffer(
            requestId = "request-$number",
            liquidAuthUrl = "liquid://auth.example.com/?requestId=request-$number&appId=LIQUID_AUTH_STREAM",
            origin = ORIGIN,
        )

    private fun invitation(number: Int) =
        offer(number).let {
            OfferState.WaitingForConnection(it.requestId, it.liquidAuthUrl, it.origin)
        }

    private companion object {
        const val ORIGIN = "https://auth.example.com"
    }
}
