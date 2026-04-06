package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.CameraStreamingPreviewController
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import org.junit.Test
import java.util.Locale

class LiquidAuthOfferScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testIdleState() {
        setStateContent(state = LiquidAuthOfferViewModel.OfferState.Idle)
        takeScreenshot("testIdleState")
    }

    @Test
    fun testLoadingState() {
        setStateContent(state = LiquidAuthOfferViewModel.OfferState.Loading)
        takeScreenshot("testLoadingState")
    }

    @Test
    fun testWaitingForConnectionState() {
        setStateContent(
            state =
                LiquidAuthOfferViewModel.OfferState.WaitingForConnection(
                    requestId = "offer-waiting-req-001",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=offer-waiting-req-001",
                    origin = "https://auth.example.com",
                ),
            connectionType = IceConnectionType.UNKNOWN,
        )
        takeScreenshot("testWaitingForConnectionState")
    }

    @Test
    fun testConnectedFundedState() {
        setStateContent(
            state =
                LiquidAuthOfferViewModel.OfferState.Connected(
                    requestId = "offer-connected-funded-001",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=offer-connected-funded-001",
                    origin = "https://auth.example.com",
                    sessionId = "session-funded-44556677",
                ),
            connectionType = IceConnectionType.STUN,
            balanceAlgos = 1.0,
        )
        takeScreenshot("testConnectedFundedState")
    }

    @Test
    fun testConnectedDepletedState() {
        setStateContent(
            state =
                LiquidAuthOfferViewModel.OfferState.Connected(
                    requestId = "offer-connected-depleted-001",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=offer-connected-depleted-001",
                    origin = "https://auth.example.com",
                    sessionId = "session-depleted-88990011",
                ),
            connectionType = IceConnectionType.RELAY,
            balanceAlgos = 0.0,
        )
        takeScreenshot("testConnectedDepletedState")
    }

    @Test
    fun testWaitingForPaymentState() {
        setStateContent(
            state =
                LiquidAuthOfferViewModel.OfferState.WaitingForPayment(
                    requestId = "offer-payment-wait-001",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=offer-payment-wait-001",
                    origin = "https://auth.example.com",
                    sessionId = "session-payment-33221100",
                    paymentRequest =
                        X402PaymentMessages.PaymentRequest(
                            id = "payment-session-123",
                            amountMicroAlgos = 1_000_000L,
                            creatorAddress = "CREATORADDR1234567890ABCDEFGH",
                            network = "testnet",
                        ),
                ),
            connectionType = IceConnectionType.STUN,
        )
        takeScreenshot("testWaitingForPaymentState")
        Thread.sleep(2000)
    }

    @Test
    fun testStreamingState() {
        setStateContent(
            state =
                LiquidAuthOfferViewModel.OfferState.Streaming(
                    requestId = "offer-streaming-001",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=offer-streaming-001",
                    origin = "https://auth.example.com",
                    sessionId = "session-streaming-12345678",
                    isPaid = true,
                ),
            connectionType = IceConnectionType.LOCAL,
            balanceAlgos = 0.8,
            currentBlockNumber = 45_123_456L,
            cameraPreview = {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Direct camera stream", color = Color.White)
                }
            },
        )
        takeScreenshot("testStreamingState")
    }

    @Test
    fun testErrorState() {
        setStateContent(
            state =
                LiquidAuthOfferViewModel.OfferState.Error(
                    message = "Failed to generate offer. Please check your network and try again.",
                ),
        )
        takeScreenshot("testErrorState")
    }

    private fun setStateContent(
        state: LiquidAuthOfferViewModel.OfferState,
        connectionType: IceConnectionType = IceConnectionType.UNKNOWN,
        balanceAlgos: Double? = null,
        currentBlockNumber: Long? = null,
        cameraPreview: @androidx.compose.runtime.Composable (() -> Unit)? = null,
    ) {
        setTestContent {
            LiquidAuthOfferScreenContent(
                showTopBar = true,
                title = { Text("Liquid Auth") },
                onBackPressed = {},
                state = state,
                connectionType = connectionType,
                balanceAlgos = balanceAlgos,
                currentBlockNumber = currentBlockNumber,
                cameraPreview = cameraPreview,
                onRegenerate = {},
                onStopStreaming = {},
                onRetry = {},
                onMinimise = {},
                streamHostUiMode = remember { mutableStateOf(StreamHostUiMode.Hidden) },
                cameraPreviewController = remember { CameraStreamingPreviewController() },
            )
        }
    }
}
