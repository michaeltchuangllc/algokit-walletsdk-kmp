package com.michaeltchuang.walletsdk.ui.liquidStream.domain.model

import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments

data class ViewerVaultProgress(
    val sessionId: String? = null,
    val remainingUsdc: Double = 0.0,
    val capacityUsdc: Double = 0.0,
    val revenueCapacityUsdc: Double = 0.0,
    val confirmedDepositMicroUsdc: Long? = null,
    val startRound: Long? = null,
) {
    fun update(
        sessionId: String,
        snapshot: MppPayments.SessionProgressSnapshot,
    ): ViewerVaultProgress {
        val sameVault = this.sessionId == sessionId && startRound == snapshot.startRound
        val previousDeposit = confirmedDepositMicroUsdc.takeIf { sameVault }
        if (previousDeposit != null && snapshot.totalDepositMicroUsdc < previousDeposit) return this
        val capacity =
            when {
                previousDeposit == null -> snapshot.progressBalanceMicroUsdc / 1_000_000.0
                snapshot.totalDepositMicroUsdc > previousDeposit -> snapshot.progressBalanceMicroUsdc / 1_000_000.0
                else -> capacityUsdc
            }
        return ViewerVaultProgress(
            sessionId = sessionId,
            remainingUsdc = snapshot.remainingSettledMicroUsdc / 1_000_000.0,
            capacityUsdc = capacity,
            revenueCapacityUsdc = snapshot.totalDepositMicroUsdc / 1_000_000.0,
            confirmedDepositMicroUsdc = snapshot.totalDepositMicroUsdc,
            startRound = snapshot.startRound,
        )
    }

    fun update(
        sessionId: String,
        remainingUsdc: Double,
    ): ViewerVaultProgress {
        if (this.sessionId == sessionId && confirmedDepositMicroUsdc != null) return this
        if (!remainingUsdc.isFinite() || remainingUsdc < 0.0) return this
        if (this.sessionId != sessionId) {
            return ViewerVaultProgress(sessionId, remainingUsdc, remainingUsdc, remainingUsdc)
        }
        val increase = (remainingUsdc - this.remainingUsdc).coerceAtLeast(0.0)
        return copy(
            remainingUsdc = remainingUsdc,
            capacityUsdc = if (increase > 0.0) remainingUsdc else capacityUsdc,
            revenueCapacityUsdc = revenueCapacityUsdc + increase,
        )
    }
}
