package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetNfdProfileForAddress
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
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
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Clock
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
    private val viewerTipChatCount = mutableMapOf<String, Long>()
    private val viewerTipChatTotalMicroUsdc = mutableMapOf<String, Long>()
    private val viewerFreeChatCount = mutableMapOf<String, Long>()
    private var balanceRefreshJob: Job? = null

    init {
        stateDelegate.setDefaultState(ViewState())
        logDebugAddresses()
        startLivePolling()
        refreshViewerBalances()
        startAutomation()
        loadCreatorNfdProfile()
        loadViewerNfdProfiles()
        startAutoMessagePump()
    }

    private fun logDebugAddresses() {
        DebugAddressHolder.viewerAddresses.forEachIndexed { index, address ->
            Napier.d("Viewer ${index + 1} Address: $address", tag = "LiquidStreamHostDebug")
        }
        Napier.d("Creator Address: ${DebugAddressHolder.creatorAddress}", tag = "LiquidStreamHostDebug")
    }

    private fun startAutoMessagePump() {
        viewModelScope.launch {
            val randomTexts =
                listOf(
                    "Hello world!",
                    "This stream is awesome!",
                    "Keep it up!",
                    "Love the content!",
                    "Wow, very informative.",
                    "Testing 1 2 3",
                    "Liquid Stream is the future!",
                )

            while (true) {
                delay(2.seconds)

                val currentState = state.value
                val realViewerAddresses = DebugAddressHolder.viewerAddresses.filter { it.isNotBlank() }

                val activeViewers =
                    if (realViewerAddresses.isNotEmpty()) {
                        realViewerAddresses
                            .map { address ->
                                val name =
                                    currentState.viewerNfdNames[address]
                                        ?.takeIf { it.isNotBlank() }
                                        ?: address.toShortenedAddress()
                                address to name
                            }.filter { it.second.isNotBlank() }
                    } else {
                        currentState.viewers
                            .mapNotNull { info ->
                                info.viewerAddress?.takeIf { it.isNotBlank() }?.let { name -> "" to name }
                            }
                    }

                if (activeViewers.isEmpty()) continue

                val (targetViewerAddress, senderName) = activeViewers.random()
                val isGift = Random.nextBoolean()
                val text = randomTexts.random()
                val timestamp = Clock.System.now().toEpochMilliseconds()

                val remainingMicroUsdc = getLocalRemainingBalanceMicroUsdc(targetViewerAddress)
                val viewerBalanceUsdc = remainingMicroUsdc / 1_000_000.0
                val canAffordGift = targetViewerAddress.isNotBlank() && remainingMicroUsdc >= DEFAULT_GIFT_MICRO_USDC

                val chatMessage =
                    if (isGift && canAffordGift) {
                        val giftUsdc = DEFAULT_GIFT_MICRO_USDC / 1_000_000.0
                        settleGiftFromViewer(targetViewerAddress, giftUsdc)

                        ChatMessage(
                            sender = senderName,
                            text = "Gifting support: $text",
                            timestamp = timestamp,
                            amount = giftUsdc.toString(),
                            asset = "USDC",
                        )
                    } else {
                        if (isGift) {
                            Napier.w(
                                "[AUTO_GIFT_SKIPPED] Insufficient vault balance for $senderName ($targetViewerAddress) " +
                                    "balanceUsdc=$viewerBalanceUsdc requiredUsdc=${DEFAULT_GIFT_MICRO_USDC / 1_000_000.0}",
                                tag = "LiquidStreamHostDebugVM",
                            )
                        }
                        if (targetViewerAddress.isNotBlank()) {
                            viewerFreeChatCount[targetViewerAddress] = (viewerFreeChatCount[targetViewerAddress] ?: 0L) + 1
                        }
                        ChatMessage(
                            sender = senderName,
                            text = text,
                            timestamp = timestamp,
                        )
                    }

                eventDelegate.sendEvent(viewModelScope, ViewEvent.ChatMessageGenerated(chatMessage))
            }
        }
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

    private fun loadViewerNfdProfiles() {
        val addresses = DebugAddressHolder.viewerAddresses.filter { it.isNotBlank() }
        if (addresses.isEmpty()) return

        viewModelScope.launch {
            addresses.forEach { address ->
                try {
                    val nfdProfile = getNfdProfileForAddress(address)
                    if (nfdProfile?.name != null) {
                        stateDelegate.updateState {
                            val updatedNfdNames = it.viewerNfdNames + (address to nfdProfile.name)
                            it.copy(
                                viewerNfdNames = updatedNfdNames,
                                viewers = buildViewersList(it.viewerBalances, it.liveBlockNumber, it.liveNetworkLabel, updatedNfdNames),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Napier.e("Failed to fetch viewer NFD profile for $address", e, tag = "LiquidStreamHostDebugVM")
                }
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
                performAutomatedConsumptionAndSettlement(isBlockTick = true)
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
                        viewers =
                            buildViewersList(
                                it.viewerBalances,
                                it.liveBlockNumber,
                                network.displayName.uppercase(),
                                it.viewerNfdNames,
                            ),
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
                                viewers = buildViewersList(it.viewerBalances, result.data, it.liveNetworkLabel, it.viewerNfdNames),
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

    private fun getLocalRemainingBalanceMicroUsdc(
        viewerAddress: String,
        totalDepositMicroUsdc: Long = ((viewerMaxBalances[viewerAddress] ?: 1.0) * 1_000_000.0).roundToLong(),
    ): Long {
        val currentPaid = automatedPaidBlocksConsumed[viewerAddress] ?: 0L
        val currentTips = viewerTipChatTotalMicroUsdc[viewerAddress] ?: 0L
        val totalCommitted = (currentPaid * currentCostMicroUsdc) + currentTips
        return (totalDepositMicroUsdc - totalCommitted).coerceAtLeast(0L)
    }

    fun settleGiftFromViewer(
        viewerAddress: String,
        amountUsdc: Double,
    ) {
        val giftMicroUsdc = (amountUsdc * 1_000_000.0).roundToLong()
        if (giftMicroUsdc <= 0) return

        val remainingMicroUsdc = getLocalRemainingBalanceMicroUsdc(viewerAddress)

        if (remainingMicroUsdc < giftMicroUsdc) {
            Napier.w(
                "[GIFT_SKIPPED] Insufficient vault balance for $viewerAddress remainingMicroUsdc=$remainingMicroUsdc giftUsdc=$amountUsdc",
                tag = "LiquidStreamHostDebugVM",
            )
            eventDelegate.sendEvent(
                viewModelScope,
                ViewEvent.ShowStatusMessage("❌ Insufficient vault balance for $viewerAddress to send $amountUsdc USDC gift"),
            )
            return
        }

        viewerTipChatCount[viewerAddress] = (viewerTipChatCount[viewerAddress] ?: 0L) + 1
        viewerTipChatTotalMicroUsdc[viewerAddress] = (viewerTipChatTotalMicroUsdc[viewerAddress] ?: 0L) + giftMicroUsdc
        viewModelScope.launch {
            performAutomatedConsumptionAndSettlement(isBlockTick = false)
        }
    }

    fun refreshViewerBalances() {
        if (balanceRefreshJob?.isActive == true) return
        balanceRefreshJob =
            viewModelScope.launch {
                try {
                    val vaultContext = getSessionVaultContextUseCase()
                    EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                    val addresses = DebugAddressHolder.viewerAddresses.filter { it.isNotBlank() }

                    val newBalances = mutableMapOf<String, Double>()
                    addresses.forEach { address ->
                        try {
                            val signer = mppWalletSignerUseCase(address)
                            val channelId = if (signer != null) getOrInitChannelId(address, signer) else null
                            val remainingOnChain =
                                withContext(Dispatchers.Default) {
                                    MppPayments.getRemainingBalanceFromSessionVault(address, channelId)
                                }
                            val totalDeposit = maxOf(remainingOnChain, ((viewerMaxBalances[address] ?: 1.0) * 1_000_000.0).toLong())
                            val remainingLocal = getLocalRemainingBalanceMicroUsdc(address, totalDeposit)
                            val balanceUsdc = remainingLocal / 1_000_000.0
                            newBalances[address] = balanceUsdc

                            // Track max balance for progress bar capacity
                            val currentMax = viewerMaxBalances[address] ?: 0.0
                            val onChainMax = remainingOnChain / 1_000_000.0
                            if (onChainMax > currentMax) {
                                viewerMaxBalances[address] = onChainMax
                            }
                        } catch (e: Exception) {
                            Napier.e("Failed to fetch balance for $address", e, tag = "LiquidStreamHostDebugVM")
                            newBalances[address] = 0.0
                        }
                    }
                    stateDelegate.updateState {
                        it.copy(
                            viewerBalances = newBalances,
                            viewers = buildViewersList(newBalances, it.liveBlockNumber, it.liveNetworkLabel, it.viewerNfdNames),
                        )
                    }
                } catch (e: Exception) {
                    Napier.e("Failed to configure vault for balances", e, tag = "LiquidStreamHostDebugVM")
                }
            }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun performAutomatedConsumptionAndSettlement(isBlockTick: Boolean = true) {
        try {
            val vaultContext = getSessionVaultContextUseCase()
            EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

            val creator = DebugAddressHolder.creatorAddress
            val creatorSigner = mppWalletSignerUseCase(creator) ?: return

            val addresses = DebugAddressHolder.viewerAddresses.filter { it.isNotBlank() }

            val incrementMicroUsdc = currentCostMicroUsdc

            for (viewer in addresses) {
                val viewerSigner = mppWalletSignerUseCase(viewer) ?: continue
                val channelId = getOrInitChannelId(viewer, viewerSigner)

                // 1. Check Balance before incrementing
                val remainingLocal = getLocalRemainingBalanceMicroUsdc(viewer)
                if (remainingLocal <= 0) {
                    Napier.d("[AUTO_SETTLE_SKIP] reason=vault_exhausted viewer=$viewer remaining=$remainingLocal")
                    continue
                }

                // 2. Increment Block Count (only on block ticks, and only if enough balance remains for a block)
                if (isBlockTick) {
                    if (remainingLocal < incrementMicroUsdc) {
                        Napier.d(
                            "[AUTO_SETTLE_SKIP] reason=insufficient_for_block viewer=$viewer remaining=$remainingLocal required=$incrementMicroUsdc",
                        )
                        continue
                    }
                    val totalBlocks = (viewerBlockCounts[viewer] ?: 0) + 1
                    viewerBlockCounts[viewer] = totalBlocks

                    if (isPaidStreaming) {
                        automatedPaidBlocksConsumed[viewer] = (automatedPaidBlocksConsumed[viewer] ?: 0L) + 1
                    } else {
                        automatedFreeBlocksConsumed[viewer] = (automatedFreeBlocksConsumed[viewer] ?: 0L) + 1
                    }
                }
                val totalBlocks = viewerBlockCounts[viewer] ?: 0

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
                val hasPendingTip = (viewerTipChatTotalMicroUsdc[viewer] ?: 0L) > 0L

                if (isBlockTick && blocksSinceLast < payoutFrequencyBlocks && !hasPendingTip) {
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
                val freeChatCount = viewerFreeChatCount[viewer] ?: 0L
                val tipCount = viewerTipChatCount[viewer] ?: 0L
                val tipTotal = viewerTipChatTotalMicroUsdc[viewer] ?: 0L

                val calculatedTotal = (currentPaid * incrementMicroUsdc) + tipTotal
                val newCumulative =
                    calculatedTotal
                        .coerceAtMost(
                            snapshot.totalDepositMicroUsdc,
                        ).coerceAtLeast(snapshot.latestVoucherAmountMicroUsdc)

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
                            freeChatCount = freeChatCount,
                            tipChatCount = tipCount,
                            tipChatTotal = tipTotal,
                        ),
                    )
                // Napier.d { "[AUTO_SETTLE_NOTE] newCumulative=$newCumulative" }

                // 6. No-progress check: skip settling if cumulative amount hasn't increased since last settle
                if (newCumulative <= snapshot.latestVoucherAmountMicroUsdc) {
                    Napier.d(
                        "[AUTO_SETTLE_SKIP] reason=no-progress viewer=$viewer " +
                            "newCumulative=$newCumulative last=${snapshot.latestVoucherAmountMicroUsdc}",
                    )
                    continue
                }

                // 7. Safety check: don't settle more than deposited
                if (newCumulative > snapshot.totalDepositMicroUsdc) {
                    Napier.w(
                        "[AUTO_SETTLE_SKIP] reason=exceeds-deposit viewer=$viewer " +
                            "newCumulative=$newCumulative deposit=${snapshot.totalDepositMicroUsdc}",
                        tag = "LiquidStreamHostDebugVM",
                    )
                    continue
                }

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
                    Napier.d { "****************AUTO_SETTLE_OK**********************" }
                    Napier.d("[AUTO_SETTLE_OK] viewer=$viewer txId=$txId newCumulative=$newCumulative", tag = "LiquidStreamHostDebugVM")
                    Napier.d { "[AUTO_SETTLE_OK] note=$noteJson" }
                    Napier.d { "*******************************************************" }
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

                val addresses = DebugAddressHolder.viewerAddresses.filter { it.isNotBlank() }

                val depositMicroUsdc = (amountUsdc * 1_000_000.0).roundToLong()

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

                val addresses = DebugAddressHolder.viewerAddresses.filter { it.isNotBlank() }
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
        viewerNfdNames: Map<String, String>,
    ): List<ConnectedViewerInfo> =
        DebugAddressHolder.viewerAddresses
            .mapIndexed { index, address ->
                val originUrl = if (index == 2) "https://viewer-3.app" else "https://liquid-auth-api.pg.nodely.dev/"
                ConnectedViewerInfo(
                    sessionId = channelIdDisplayFor(address),
                    remainingBalanceUSDC = balances[address] ?: 0.0,
                    progressBalanceUSDC = balances[address] ?: 0.0,
                    progressCapacityUSDC =
                        viewerMaxBalances[address] ?: balances[address] ?: 0.0,
                    revenueCapacityUSDC =
                        viewerMaxBalances[address] ?: balances[address] ?: 0.0,
                    connectionType = IceConnectionType.LOCAL,
                    currentBlockNumber = blockNumber,
                    networkLabel = networkLabel,
                    originUrl = originUrl,
                    viewerAddress =
                        viewerNfdNames[address]
                            ?: address.toShortenedAddress(),
                )
            }.filter { !it.viewerAddress.isNullOrBlank() }

    data class ViewState(
        val liveBlockNumber: Long? = null,
        val liveNetworkLabel: String = "TESTNET",
        val viewerBalances: Map<String, Double> = emptyMap(),
        val viewers: List<ConnectedViewerInfo> = emptyList(),
        val isLoading: Boolean = false,
        val totalRevenueMicroUsdc: Long = 0L,
        val creatorNfdName: String? = null,
        val creatorNfdAvatarUrl: String? = null,
        val viewerNfdNames: Map<String, String> = emptyMap(),
    )

    sealed interface ViewEvent {
        data class ShowStatusMessage(
            val message: String,
        ) : ViewEvent

        data class ChatMessageGenerated(
            val message: ChatMessage,
        ) : ViewEvent
    }

    companion object {
        private const val DEFAULT_GIFT_MICRO_USDC = 100_000L // 0.1 USDC
    }
}
