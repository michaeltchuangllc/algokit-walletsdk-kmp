package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetNfdProfileForAddress
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.deleteVoucher
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

class LiquidStreamHostDebugToolViewModel(
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val getSessionVaultContextUseCase: GetSessionVaultContextUseCase,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
    private val getMppVoucherNoteUseCase: GetMppVoucherNoteUseCase,
    private val voucherRepository: MppVoucherRepository,
    private val getNfdProfileForAddress: GetNfdProfileForAddress,
    private val applicationScope: CoroutineScope,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostDebugToolViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidStreamHostDebugToolViewModel.ViewEvent> by eventDelegate {
    private val viewerChannelIds = mutableMapOf<String, ByteArray>()
    private val authorizedSignerViewers = mutableSetOf<String>()
    private val automatedPaidBlocksConsumed = mutableMapOf<String, Long>()
    private val automatedFreeBlocksConsumed = mutableMapOf<String, Long>()
    private val viewerMaxBalances = mutableMapOf<String, Double>()
    private var isPaidStreaming: Boolean = true
    private var currentCostMicroUsdc: Long = 100_000L
    private var payoutFrequencyBlocks: Int = 1
    private val viewerBlockCounts = mutableMapOf<String, Int>()
    private val viewerLastSettledBlockCounts = mutableMapOf<String, Int>()
    private var balanceRefreshJob: Job? = null

    init {
        stateDelegate.setDefaultState(ViewState())
        startLivePolling()
        refreshViewerBalances()
        startAutomation()
        loadCreatorNfdProfile()
    }

    private fun loadCreatorNfdProfile() {
        val creatorAddress = DebugAddressHolder.creatorAddress
        if (creatorAddress.isBlank()) return
        viewModelScope.launch {
            try {
                val nfdProfile = getNfdProfileForAddress(creatorAddress)
                stateDelegate.updateState {
                    it.copy(
                        creatorNfdName = nfdProfile?.name,
                        creatorNfdAvatarUrl = nfdProfile?.avatarUrl,
                    )
                }
            } catch (e: Exception) {
                Napier.e("Failed to fetch creator NFD profile", e, tag = "LiquidStreamHostDebugVM")
            }
        }
    }

    private fun getOrInitChannelId(
        viewerAddress: String,
        signer: com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner,
    ): ByteArray {
        viewerChannelIds[viewerAddress]?.let { return it }
        val derived =
            EscrowSessionVaultManagerClient.deriveChannelId(
                payerAddress = viewerAddress,
                payeeAddress = DebugAddressHolder.creatorAddress,
                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
            )
        viewerChannelIds[viewerAddress] = derived
        return derived
    }

    private fun startAutomation() {
        viewModelScope.launch {
            delay(2.seconds) // Wait for UI to stabilize

            // 1. Initial deposit must finish before voucher settlement begins.
            addAmountToAllSessionVaults(1.0).join()

            // 2. Periodic Consumption Cycle (Simplified debug loop)
            while (true) {
                delay(8.seconds)
                performAutomatedConsumptionAndSettlement()
            }
        }
    }

    private fun startLivePolling() {
        viewModelScope.launch {
            try {
                val network = getCurrentNetworkUseCase().first()
                stateDelegate.updateState {
                    it.copy(
                        liveNetworkLabel = network.displayName.uppercase(),
                        viewers = buildViewersList(it.viewerBalances, it.liveBlockNumber, network.displayName.uppercase()),
                    )
                }
            } catch (e: Exception) {
                Napier.e("Failed to fetch initial network", e, tag = "LiquidStreamHostDebugVM")
            }

            while (true) {
                getCurrentBlockUseCase().collect { result ->
                    if (result is DataResource.Success) {
                        stateDelegate.updateState {
                            it.copy(
                                liveBlockNumber = result.data,
                                viewers = buildViewersList(it.viewerBalances, result.data, it.liveNetworkLabel),
                            )
                        }
                    }
                }
                delay(3.seconds)
            }
        }
    }

    fun setIsPaidStreaming(isPaid: Boolean) {
        this.isPaidStreaming = isPaid
        Napier.d("[DEBUG_VM] setIsPaidStreaming=$isPaid", tag = "LiquidStreamHostDebugVM")
    }

    fun setStreamCost(microUsdc: Long) {
        this.currentCostMicroUsdc = microUsdc
        Napier.d("[DEBUG_VM] setStreamCost=$microUsdc", tag = "LiquidStreamHostDebugVM")
    }

    fun setPayoutFrequency(blocks: Int) {
        this.payoutFrequencyBlocks = blocks
        Napier.d("[DEBUG_VM] setPayoutFrequency=$blocks", tag = "LiquidStreamHostDebugVM")
    }

    fun refreshViewerBalances() {
        if (balanceRefreshJob?.isActive == true) return
        balanceRefreshJob =
            viewModelScope.launch {
                try {
                    val vaultContext = getSessionVaultContextUseCase()
                    EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                    val addresses =
                        listOf(
                            DebugAddressHolder.viewerAddress,
                            DebugAddressHolder.viewerAddress2,
                            DebugAddressHolder.viewerAddress3,
                        ).filter { it.isNotBlank() }

                    val newBalances = mutableMapOf<String, Double>()
                    addresses.forEach { address ->
                        try {
                            val signer = mppWalletSignerUseCase(address)
                            val channelId = if (signer != null) getOrInitChannelId(address, signer) else null
                            val remaining =
                                withContext(Dispatchers.Default) {
                                    MppPayments.getRemainingBalanceFromSessionVault(address, channelId)
                                }
                            val balanceUsdc = remaining / 1_000_000.0
                            newBalances[address] = balanceUsdc

                            // Track max balance for progress bar capacity
                            val currentMax = viewerMaxBalances[address] ?: 0.0
                            if (balanceUsdc > currentMax) {
                                viewerMaxBalances[address] = balanceUsdc
                            }
                        } catch (e: Exception) {
                            Napier.e("Failed to fetch balance for $address", e, tag = "LiquidStreamHostDebugVM")
                            newBalances[address] = 0.0
                        }
                    }
                    stateDelegate.updateState {
                        it.copy(
                            viewerBalances = newBalances,
                            viewers = buildViewersList(newBalances, it.liveBlockNumber, it.liveNetworkLabel),
                        )
                    }
                } catch (e: Exception) {
                    Napier.e("Failed to configure vault for balances", e, tag = "LiquidStreamHostDebugVM")
                }
            }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun performAutomatedConsumptionAndSettlement() {
        try {
            val vaultContext = getSessionVaultContextUseCase()
            EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

            val creator = DebugAddressHolder.creatorAddress
            val creatorSigner = mppWalletSignerUseCase(creator) ?: return

            val addresses =
                listOf(
                    DebugAddressHolder.viewerAddress,
                    DebugAddressHolder.viewerAddress2,
                    DebugAddressHolder.viewerAddress3,
                ).filter { it.isNotBlank() }

            val incrementMicroUsdc = currentCostMicroUsdc

            for (viewer in addresses) {
                val viewerSigner = mppWalletSignerUseCase(viewer) ?: continue
                val channelId = getOrInitChannelId(viewer, viewerSigner)

                // 1. Check Balance before incrementing
                val currentBalance =
                    try {
                        MppPayments.getRemainingBalanceFromSessionVault(viewer, channelId)
                    } catch (_: Exception) {
                        0L
                    }

                if (currentBalance <= 0) continue

                // 2. Increment Block Count
                val totalBlocks = (viewerBlockCounts[viewer] ?: 0) + 1
                viewerBlockCounts[viewer] = totalBlocks

                if (isPaidStreaming) {
                    automatedPaidBlocksConsumed[viewer] = (automatedPaidBlocksConsumed[viewer] ?: 0L) + 1
                } else {
                    automatedFreeBlocksConsumed[viewer] = (automatedFreeBlocksConsumed[viewer] ?: 0L) + 1
                }

                if (viewer !in authorizedSignerViewers) {
                    val authorizationResult =
                        withContext(Dispatchers.Default) {
                            MppPayments.setAuthorizedSignerForSession(
                                signer = viewerSigner,
                                viewerAddress = viewer,
                                authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                                channelId = channelId,
                            )
                        }
                    if (authorizationResult.isFailure) continue
                    authorizedSignerViewers += viewer
                }

                // 3. Frequency Check
                val lastSettleCount = viewerLastSettledBlockCounts[viewer] ?: 0
                val blocksSinceLast = totalBlocks - lastSettleCount

                if (blocksSinceLast < payoutFrequencyBlocks) {
                    Napier.d("[AUTO_SETTLE_SKIP] reason=frequency viewer=$viewer since=$blocksSinceLast freq=$payoutFrequencyBlocks")
                    continue
                }

                // 4. Fetch Snapshot to determine next cumulative amount
                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault(channelId)
                    } ?: continue

                val currentPaid = automatedPaidBlocksConsumed[viewer] ?: 0L
                val currentFree = automatedFreeBlocksConsumed[viewer] ?: 0L
                val newCumulative = (currentPaid * incrementMicroUsdc).coerceAtLeast(snapshot.latestVoucherAmountMicroUsdc)

                val channelIdBase64 = Base64.encode(channelId)
                val currentBlock = state.value.liveBlockNumber ?: 0L
                val startRound = snapshot.startRound

                // 5. Generate Note (always, so the receipt is visible even when settlement is skipped below)
                val noteJson =
                    getMppVoucherNoteUseCase(
                        GetMppVoucherNoteUseCase.Params(
                            channelId = channelIdBase64,
                            startBlock = startRound,
                            currentBlock = currentBlock,
                            freeBlocks = currentFree,
                            paidBlocks = currentPaid,
                            costPerPaidBlock = incrementMicroUsdc,
                            settledAmount = snapshot.lastSettledMicroUsdc,
                            totalCumulativeAmount = newCumulative,
                        ),
                    )
                Napier.d { "[AUTO_SETTLE_NOTE] newCumulative=$newCumulative" }
                Napier.d { "[AUTO_SETTLE_NOTE] viewer=$viewer note=$noteJson" }

                // 6. No-progress check: skip settling if cumulative amount hasn't increased since last settle
                if (newCumulative <= snapshot.latestVoucherAmountMicroUsdc) {
                    Napier.d(
                        "[AUTO_SETTLE_SKIP] reason=no-progress viewer=$viewer " +
                            "newCumulative=$newCumulative last=${snapshot.latestVoucherAmountMicroUsdc}",
                    )
                    continue
                }

                // 7. Safety check: don't settle more than deposited
                if (newCumulative > snapshot.totalDepositMicroUsdc) continue

                // 8. Settle
                val settleMessage =
                    MppPayments.settleMessage(
                        cumulativeAmountMicroUsdc = newCumulative,
                        channelId = channelId,
                    )
                val signature = viewerSigner.signMessage(settleMessage)
                val signatureBase64 = Base64.encode(signature)

                voucherRepository.upsertVoucher(
                    MppVoucherEntity(
                        channelIdBase64 = channelIdBase64,
                        sessionId = "debug-session-$viewer",
                        viewerAddress = viewer,
                        viewerPublicKeyBase64 = Base64.encode(viewerSigner.authorizedSignerPublicKey),
                        signatureBase64 = signatureBase64,
                        totalAmountClaimedMicroUsdc = newCumulative,
                        creatorAddress = creator,
                        blockNumber = currentBlock,
                        note = noteJson,
                    ),
                )

                withContext(Dispatchers.Default) {
                    MppPayments.settle(
                        signer = creatorSigner,
                        cumulativeAmountMicroUsdc = newCumulative,
                        signature = signature,
                        channelId = channelId,
                        note = noteJson,
                    )
                }.onSuccess { txId ->
                    Napier.d("[AUTO_SETTLE_OK] viewer=$viewer txId=$txId newCumulative=$newCumulative", tag = "LiquidStreamHostDebugVM")
                    val gained = newCumulative - snapshot.lastSettledMicroUsdc
                    stateDelegate.updateState {
                        it.copy(
                            totalRevenueMicroUsdc = it.totalRevenueMicroUsdc + gained,
                        )
                    }
                    viewerLastSettledBlockCounts[viewer] = totalBlocks
                    voucherRepository.deleteVoucher(
                        sessionId = "debug-session-$viewer",
                        viewerAddress = viewer,
                        channelIdBase64 = channelIdBase64,
                    )
                }.onFailure { err ->
                    Napier.e("[AUTO_SETTLE_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                }
            }
            refreshViewerBalances()
        } catch (e: Exception) {
            Napier.e("Auto-Consumption/Settle failed", e, tag = "LiquidStreamHostDebugVM")
        }
    }

    private fun addAmountToAllSessionVaults(amountUsdc: Double = 1.0): Job =
        viewModelScope.launch {
            try {
                stateDelegate.updateState { it.copy(isLoading = true) }
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                val addresses =
                    listOf(
                        DebugAddressHolder.viewerAddress,
                        DebugAddressHolder.viewerAddress2,
                        DebugAddressHolder.viewerAddress3,
                    ).filter { it.isNotBlank() }

                val depositMicroUsdc = (amountUsdc * 1_000_000).toLong()

                for (viewer in addresses) {
                    val signer = mppWalletSignerUseCase(viewer)
                    if (signer != null) {
                        val channelId = getOrInitChannelId(viewer, signer)

                        // 1. Check if viewer already has funds
                        val currentBalance =
                            withContext(Dispatchers.Default) {
                                try {
                                    MppPayments.getRemainingBalanceFromSessionVault(viewer, channelId)
                                } catch (_: Exception) {
                                    0L
                                }
                            }

                        if (currentBalance > 0) {
                            Napier.d("[AUTO_DEPOSIT_SKIP] viewer=$viewer balance=$currentBalance", tag = "LiquidStreamHostDebugVM")
                            continue
                        }

                        val depositResult =
                            withContext(Dispatchers.Default) {
                                MppPayments.openSessionAndDeposit(
                                    signer = signer,
                                    viewerAddress = viewer,
                                    depositAmountMicroUsdc = depositMicroUsdc,
                                    channelId = channelId,
                                )
                            }
                        depositResult
                            .onSuccess { txId ->
                                val authorizationResult =
                                    withContext(Dispatchers.Default) {
                                        MppPayments.setAuthorizedSignerForSession(
                                            signer = signer,
                                            viewerAddress = viewer,
                                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            channelId = channelId,
                                        )
                                    }
                                authorizationResult
                                    .onSuccess {
                                        eventDelegate.sendEvent(
                                            viewModelScope,
                                            ViewEvent.ShowStatusMessage("✅ Successfully deposited $amountUsdc USDC to $viewer"),
                                        )
                                        Napier.d("[AUTO_DEPOSIT_OK] viewer=$viewer txId=$txId", tag = "LiquidStreamHostDebugVM")
                                    }.onFailure { err ->
                                        eventDelegate.sendEvent(
                                            viewModelScope,
                                            ViewEvent.ShowStatusMessage("❌ Failed to register signer for $viewer"),
                                        )
                                        Napier.e("[AUTO_SET_AUTH_SIGNER_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                                    }
                            }.onFailure { err ->
                                eventDelegate.sendEvent(
                                    viewModelScope,
                                    ViewEvent.ShowStatusMessage("❌ Failed to deposit $amountUsdc USDC to $viewer"),
                                )
                                Napier.e("[AUTO_DEPOSIT_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                            }
                    }
                }
                refreshViewerBalances()
            } catch (e: Exception) {
                Napier.e("Batch deposit failed", e, tag = "LiquidStreamHostDebugVM")
                eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowStatusMessage("❌ Batch deposit failed: ${e.message}"))
            } finally {
                stateDelegate.updateState { it.copy(isLoading = false) }
            }
        }

    fun closeAllSessions() {
        applicationScope.launch {
            try {
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                val addresses =
                    listOf(
                        DebugAddressHolder.viewerAddress,
                        DebugAddressHolder.viewerAddress2,
                        DebugAddressHolder.viewerAddress3,
                    ).filter { it.isNotBlank() }
                val signer = mppWalletSignerUseCase(DebugAddressHolder.creatorAddress)
                for (viewer in addresses) {
                    if (signer != null) {
                        val channelId = getOrInitChannelId(viewer, signer)

                        // 2. Close Session Vault
                        withContext(Dispatchers.Default) {
                            MppPayments.closeSessionVault(signer = signer, channelId = channelId)
                        }.onSuccess { txId ->
                            Napier.d("[AUTO_CLOSE_OK] viewer=$viewer txId=$txId", tag = "LiquidStreamHostDebugVM")
                        }.onFailure { err ->
                            Napier.e("[AUTO_CLOSE_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                        }
                    }
                }
                refreshViewerBalances()
            } catch (e: Exception) {
                Napier.e("Close sessions failed", e, tag = "LiquidStreamHostDebugVM")
            }
        }
    }

    private fun channelIdDisplayFor(viewerAddress: String): String =
        viewerChannelIds[viewerAddress]?.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        } ?: "channel-pending"

    private fun buildViewersList(
        balances: Map<String, Double>,
        blockNumber: Long?,
        networkLabel: String,
    ): List<ConnectedViewerInfo> =
        listOf(
            ConnectedViewerInfo(
                sessionId = channelIdDisplayFor(DebugAddressHolder.viewerAddress),
                remainingBalanceUSDC = balances[DebugAddressHolder.viewerAddress] ?: 0.0,
                progressBalanceUSDC = balances[DebugAddressHolder.viewerAddress] ?: 0.0,
                progressCapacityUSDC =
                    viewerMaxBalances[DebugAddressHolder.viewerAddress] ?: balances[DebugAddressHolder.viewerAddress] ?: 0.0,
                revenueCapacityUSDC =
                    viewerMaxBalances[DebugAddressHolder.viewerAddress] ?: balances[DebugAddressHolder.viewerAddress] ?: 0.0,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = blockNumber,
                networkLabel = networkLabel,
                originUrl = "https://liquid-auth-api.pg.nodely.dev/",
                viewerAddress = DebugAddressHolder.viewerAddress,
            ),
            ConnectedViewerInfo(
                sessionId = channelIdDisplayFor(DebugAddressHolder.viewerAddress2),
                remainingBalanceUSDC = balances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                progressBalanceUSDC = balances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                progressCapacityUSDC =
                    viewerMaxBalances[DebugAddressHolder.viewerAddress2] ?: balances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                revenueCapacityUSDC =
                    viewerMaxBalances[DebugAddressHolder.viewerAddress2] ?: balances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = blockNumber,
                networkLabel = networkLabel,
                originUrl = "https://liquid-auth-api.pg.nodely.dev/",
                viewerAddress = DebugAddressHolder.viewerAddress2,
            ),
            ConnectedViewerInfo(
                sessionId = channelIdDisplayFor(DebugAddressHolder.viewerAddress3),
                remainingBalanceUSDC = balances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                progressBalanceUSDC = balances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                progressCapacityUSDC =
                    viewerMaxBalances[DebugAddressHolder.viewerAddress3] ?: balances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                revenueCapacityUSDC =
                    viewerMaxBalances[DebugAddressHolder.viewerAddress3] ?: balances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = blockNumber,
                networkLabel = networkLabel,
                originUrl = "https://viewer-3.app",
                viewerAddress = DebugAddressHolder.viewerAddress3,
            ),
        ).filter { !it.viewerAddress.isNullOrBlank() }

    data class ViewState(
        val liveBlockNumber: Long? = null,
        val liveNetworkLabel: String = "TESTNET",
        val viewerBalances: Map<String, Double> = emptyMap(),
        val viewers: List<ConnectedViewerInfo> = emptyList(),
        val isLoading: Boolean = false,
        val totalRevenueMicroUsdc: Long = 0L,
        val creatorNfdName: String? = null,
        val creatorNfdAvatarUrl: String? = null,
    )

    sealed interface ViewEvent {
        data class ShowStatusMessage(
            val message: String,
        ) : ViewEvent
    }
}
