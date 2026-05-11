package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS stub implementation of LiquidAuthConnectionManager.
 *
 * iOS uses a different architecture for Liquid Auth (see iosDemoApp).
 * This is a placeholder to satisfy the interface contract.
 */
class IOSLiquidAuthConnectionManager : LiquidAuthConnectionManager {
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    override val connectionType: StateFlow<IceConnectionType> = _connectionType

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        // iOS uses a different architecture - see iosDemoApp
        println("IOSLiquidAuthConnectionManager: iOS uses different architecture")
    }

    override fun startListening(
        origin: String,
        requestId: String,
    ) {
        // Stub - iOS implementation is in iosDemoApp
        println("IOSLiquidAuthConnectionManager.startListening: iOS stub")
    }

    override fun stopListening() {
        // Stub
        println("IOSLiquidAuthConnectionManager.stopListening: iOS stub")
    }

    override fun sendMessage(message: String) {
        // Stub
        println("IOSLiquidAuthConnectionManager.sendMessage: iOS stub")
    }

    override fun sendVideoFrame(
        frameId: String,
        timestamp: Long,
        frameData: ByteArray,
        width: Int,
        height: Int,
        format: String,
    ) {
        // Stub - iOS implementation would use LiquidAuthService.swift
        println("IOSLiquidAuthConnectionManager.sendVideoFrame: iOS stub")
    }

    override fun isConnected(): Boolean = false

    // ================= X402 Payment Stubs =================

    override fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        println("IOSLiquidAuthConnectionManager.sendPaymentRequest: iOS stub")
    }

    override fun startBlockConsumption(sessionId: String) {
        println("IOSLiquidAuthConnectionManager.startBlockConsumption: iOS stub")
    }

    override fun stopBlockConsumption() {
        println("IOSLiquidAuthConnectionManager.stopBlockConsumption: iOS stub")
    }
}

/**
 * iOS actual implementation of factory function.
 */
actual fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager = IOSLiquidAuthConnectionManager()
