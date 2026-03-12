package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel

/**
 * iOS stub implementation of LiquidAuthConnectionManager.
 *
 * iOS uses a different architecture for Liquid Auth (see iosDemoApp).
 * This is a placeholder to satisfy the interface contract.
 */
class IOSLiquidAuthConnectionManager : LiquidAuthConnectionManager {

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        // iOS uses a different architecture - see iosDemoApp
        println("IOSLiquidAuthConnectionManager: iOS uses different architecture")
    }

    override fun startListening(origin: String, requestId: String) {
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

    override fun isConnected(): Boolean {
        return false
    }
}

/**
 * iOS actual implementation of factory function.
 */
actual fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager {
    return IOSLiquidAuthConnectionManager()
}
