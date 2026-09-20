package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model

/** Per-request facts supplied by the host connection manager, never inferred from a peer ID. */
data class HostViewerDetails(
    val viewerAddress: String? = null,
    val connectionType: IceConnectionType = IceConnectionType.UNKNOWN,
    val remainingBalanceMicroUsdc: Long? = null,
    val lastSettledMicroUsdc: Long? = null,
    val progressBalanceMicroUsdc: Long? = null,
    /** On-chain total deposited, not the remaining balance or another viewer's capacity. */
    val totalDepositMicroUsdc: Long? = null,
    val progressCapacityMicroUsdc: Long? = null,
)
