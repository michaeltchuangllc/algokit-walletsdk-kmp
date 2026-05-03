package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.LiquidAuthOffer
import com.michaeltchuang.walletsdk.core.utils.AppId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Use case for generating Liquid Auth offer data
 * This creates the QR code content for the "offer" flow where the wallet
 * generates a QR code for dApps to scan and connect.
 */
class GenerateLiquidAuthOfferUseCase {
    /**
     * Generate a request ID using UUID7 (time-based, sortable)
     * Similar to SignalInterface.generateRequestId() in Android
     */
    @OptIn(ExperimentalUuidApi::class)
    fun generateRequestId(): String = Uuid.random().toString()

    /**
     * Generate the liquid auth URL for QR code display
     * Format: liquid://<host>/?requestId=<requestId>&appId=LIQUID_AUTH_STREAM
     *
     * @param origin The origin URL (e.g., https://auth.example.com)
     * @param requestId The request ID to include in the URL
     * @return The liquid auth URL string
     */
    fun generateLiquidAuthUrl(
        origin: String,
        requestId: String,
    ): String {
        val host =
            origin
                .replace("https://", "")
                .replace("http://", "")
                .removePrefix("/")
                .removeSuffix("/")
        return "liquid://$host/?requestId=$requestId&appId=${AppId.LIQUID_AUTH_STREAM.name}"
    }

    /**
     * Generate complete offer data for QR code
     *
     * @param origin The origin URL of the liquid auth service
     * @return Pair of (requestId, liquidAuthUrl)
     */
    fun generateOffer(origin: String): LiquidAuthOffer {
        val requestId = generateRequestId()
        val url = generateLiquidAuthUrl(origin, requestId)
        return LiquidAuthOffer(
            requestId = requestId,
            liquidAuthUrl = url,
            origin = origin,
        )
    }
}
