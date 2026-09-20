package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model

internal class HostViewerProgress {
    private var viewerAddress: String? = null
    private var totalDeposit: Long? = null
    private var capacity: Long? = null

    fun update(details: HostViewerDetails): HostViewerDetails {
        if (details.viewerAddress != viewerAddress) {
            viewerAddress = details.viewerAddress
            totalDeposit = null
            capacity = null
        }
        val total = details.totalDepositMicroUsdc
        val progress = details.progressBalanceMicroUsdc
        if (total == null || progress == null) {
            return details.copy(progressCapacityMicroUsdc = null)
        }
        val previousTotal = totalDeposit
        if (previousTotal == null || total != previousTotal) {
            capacity = progress.coerceAtLeast(0L)
        }
        totalDeposit = total
        return details.copy(progressCapacityMicroUsdc = capacity)
    }
}
