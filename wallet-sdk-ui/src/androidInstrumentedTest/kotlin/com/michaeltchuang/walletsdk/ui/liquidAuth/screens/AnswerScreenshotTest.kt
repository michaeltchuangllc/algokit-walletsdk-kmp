package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import org.junit.Test
import java.util.Locale

class AnswerScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContentConnected() {
        setTestContent {
            ScreenContentAnswer(
                isConnected = true,
                isWaiting = false,
                isConnecting = false,
                hasError = false,
                errorMessage = null,
                session = "Connected",
                origin = "michaeltchuang.ngrok.dev",
                requestId = "019b93d8-ae86-7004-a1b9-945f05c4a91d",
                accountAddress = "X6TTIPDLJQ2S5ARP6PEURNG4DIU6R4CPMRZ7AMCC5AHH2ME374LSCQ2GWI",
            )
        }
        takeScreenshot("testContentConnected")
    }

    @Test
    fun testContentWaiting() {
        setTestContent {
            ScreenContentAnswer(
                isConnected = false,
                isWaiting = true,
                isConnecting = false,
                hasError = false,
                errorMessage = null,
                session = "Logged Out",
                origin = null,
                requestId = null,
                accountAddress = "A1B2C3D4E5F6G7H8I9J0",
                videoFrame = null,
                isStreamActive = false,
            )
        }
        takeScreenshot("testContentWaiting")
    }

    @Test
    fun testContentConnectedWithVideo() {
        val sampleFrame = getSampleFrame()
        setTestContent {
            ScreenContentAnswer(
                isConnected = true,
                isWaiting = false,
                isConnecting = false,
                hasError = false,
                errorMessage = null,
                session = "Connected Session",
                origin = "https://demo.algokit.io",
                requestId = "preview-req-123",
                accountAddress = "A1B2C3D4E5F6G7H8I9J0",
                videoFrame = sampleFrame,
                isStreamActive = true,
                paymentBalance = "0.8",
                fundsDepleted = false,
            )
        }
        takeScreenshot("testContentConnectedWithVideo")
    }

    @Test
    fun testFullScreenVideoPreviewExpanded() {
        val sampleFrame = getSampleFrame()
        setTestContent {
            FullScreenVideoPreview(
                videoFrame = sampleFrame,
                isLive = true,
                onClose = {},
            )
        }
        takeScreenshot("testFullScreenVideoPreviewExpanded")
    }

    private fun getSampleFrame() =
        AnswerViewModel.VideoFrameData(
            id = "preview-frame",
            timestamp = 0L,
            data = byteArrayOf(),
            width = 640,
            height = 480,
            format = "jpeg",
        )
}
