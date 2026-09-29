package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.DebugAddressSelectionsUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.SessionVaultContext
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerProgress
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.LiquidStreamDebugBotRunner
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.LiquidStreamDebugConfiguration
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.asDetails
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.mppNetwork
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.uniqueDebugViewers
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** Screen entry calls startBots once; repeated entry/recomposition calls cannot repeat spending. */
class LiquidStreamLiveDebugViewModel(
    private val selectionsUseCase: DebugAddressSelectionsUseCase,
    private val contextUseCase: GetSessionVaultContextUseCase,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val runner: LiquidStreamDebugBotRunner,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
    private val applicationScope: CoroutineScope,
) : ViewModel() {
    data class State(
        val loaded: Boolean = false,
        val error: String? = null,
        val billingErrors: Map<String, String> = emptyMap(),
        val creator: String = "",
        val network: String = "",
        val assetId: Long = 0L,
        val botDetails: Map<String, HostViewerDetails> = emptyMap(),
        val running: Boolean = false,
        val isFunding: Boolean = false,
        val isFundAdded: Boolean = false,
        val fundedAddresses: List<String> = emptyList(),
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val configuration = MutableStateFlow(LiquidStreamDebugConfiguration())
    private var context: SessionVaultContext? = null
    private var viewers: List<String> = emptyList()
    private var producerJob: Job? = null
    private var fundingJob: Job? = null
    private val fundingMutex = Mutex()
    private var generation = 0L
    private var startupRequested = false
    private var pendingStart: ((ChatMessage) -> Unit)? = null
    private val botProgressMap = mutableMapOf<String, HostViewerProgress>()
    private val viewerChannelIds = mutableMapOf<String, ByteArray>()

    init {
        viewModelScope.launch {
            try {
                val selections = selectionsUseCase.get()
                val creator = selections.creatorAddress.trim()
                require(creator.isNotEmpty()) { "Select and save a debug creator first" }
                val captured = contextUseCase()
                context = captured
                viewers = selections.uniqueDebugViewers()
                mutableState.value =
                    State(
                        loaded = true,
                        creator = creator,
                        network = captured.mppNetwork,
                        assetId = captured.usdcAssetId,
                    )
                pendingStart?.let { callback ->
                    pendingStart = null
                    launchBots(callback)
                }
                // Settings has no shared network lock. Fail closed instead of silently switching
                // bot identities/round source. All drains still use the captured old network.
                getCurrentNetworkUseCase().collect { selected ->
                    if (selected != captured.network) {
                        stopBots()
                        mutableState.update {
                            it.copy(loaded = false, error = "Network changed. Reopen live debug before starting bots.")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "Unable to load debug selections") }
            }
        }
    }

    private fun configureDebugSessionContext(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
    ): ByteArray {
        EscrowSessionVaultHybridManagerClient.hostAddress = creatorAddress
        EscrowSessionVaultHybridManagerClient.salt = EscrowSessionVaultHybridManagerClient.defaultSalt
        return EscrowSessionVaultHybridManagerClient.initializeChannelId(
            payerAddress = viewerAddress,
            payeeAddress = creatorAddress,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
        )
    }

    fun openSessionAndDeposit(
        amountUsdc: Double = 1.0,
        checkLowBalanceOnly: Boolean = false,
    ): Job {
        fundingJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope
            .launch {
                try {
                    fundSessions(amountUsdc, checkLowBalanceOnly)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e("Batch deposit failed", e, tag = "LiquidStreamLiveDebugVM")
                    mutableState.update { it.copy(error = "Batch deposit failed: ${e.message}") }
                }
            }.also { fundingJob = it }
    }

    private suspend fun fundSessions(
        amountUsdc: Double = 1.0,
        checkLowBalanceOnly: Boolean = false,
        onViewerReady: (String) -> Unit = {},
    ) = fundingMutex.withLock {
        require(amountUsdc.isFinite() && amountUsdc > 0 && amountUsdc * 1_000_000 < Long.MAX_VALUE)
        val depositMicroUsdc = (amountUsdc * 1_000_000).roundToLong()
        require(depositMicroUsdc > 0)
        val captured = requireNotNull(context) { "Debug session has not loaded" }
        check(state.value.loaded) { "Reopen live debug on the selected network" }
        val creator = state.value.creator
        // Validate the whole batch before spending, including the creator needed for settlement.
        requireNotNull(mppWalletSignerUseCase(creator)) { "Selected creator has no local signer" }
        val signers =
            viewers.map { viewer ->
                requireNotNull(mppWalletSignerUseCase(viewer)) { "Selected viewer has no local signer: $viewer" }.also {
                    require(it.address == viewer && it.authorizedSignerPublicKey.isNotEmpty())
                }
            }
        mutableState.update { it.copy(isFunding = true, isFundAdded = false, fundedAddresses = emptyList()) }
        val funded = mutableListOf<String>()
        try {
            for (signer in signers) {
                currentCoroutineContext().ensureActive()
                val viewer = signer.address
                EscrowSessionVaultHybridManagerClient.configureForNetwork(captured.network)
                val channel = configureDebugSessionContext(viewer, creator, signer.authorizedSignerPublicKey)
                viewerChannelIds[viewer] = channel
                // An open call supports both new and existing channels. Never retry an uncertain
                // top-up submission as a second deposit.
                val shouldDeposit =
                    if (checkLowBalanceOnly) {
                        val snapshot =
                            HostViewerVaultReader
                                .readChannel(
                                    channel,
                                    viewer,
                                    creator,
                                    signer.authorizedSignerPublicKey,
                                    captured.mppNetwork,
                                ).getOrThrow()
                        snapshot.progressBalanceMicroUsdc <= LiquidStreamConstants.SESSION_VAULT_LOW_BALANCE_MICRO_USDC
                    } else {
                        true
                    }
                if (shouldDeposit) {
                    withContext(Dispatchers.Default) {
                        val txId =
                            if (checkLowBalanceOnly) {
                                EscrowSessionVaultHybridManagerClient.topUp(signer, channel, depositMicroUsdc)
                            } else {
                                MppPayments.openSessionAndDeposit(signer, viewer, depositMicroUsdc, channel)
                            }.getOrThrow()
                        check(MppPayments.awaitTransactionConfirmation(txId)) {
                            "Bot funding is not confirmed: $txId"
                        }
                        currentCoroutineContext().ensureActive()
                    }
                    funded += viewer.toShortenedAddress()
                }
                if (!checkLowBalanceOnly) {
                    // Existing funded channels need this too: a prior failed registration must
                    // not leave a bot funded but unable to settle its first voucher.
                    withContext(Dispatchers.Default) {
                        val authorizationTxId =
                            MppPayments
                                .setAuthorizedSignerForSession(
                                    signer,
                                    viewer,
                                    signer.authorizedSignerPublicKey,
                                    channel,
                                ).getOrThrow()
                        check(MppPayments.awaitTransactionConfirmation(authorizationTxId)) {
                            "Bot signer authorization is not confirmed: $authorizationTxId"
                        }
                        currentCoroutineContext().ensureActive()
                        val registrationTxId = MppPayments.registerSettlementLogicSig(signer, creator, channel).getOrThrow()
                        check(MppPayments.awaitTransactionConfirmation(registrationTxId)) {
                            "Bot settlement registration is not confirmed: $registrationTxId"
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
                val snapshot =
                    HostViewerVaultReader
                        .readChannel(
                            channel,
                            viewer,
                            creator,
                            signer.authorizedSignerPublicKey,
                            captured.mppNetwork,
                        ).getOrThrow()
                val details = botProgressMap.getOrPut(viewer) { HostViewerProgress() }.update(snapshot.asDetails(viewer))
                mutableState.update { it.copy(botDetails = it.botDetails + ("debug:$viewer" to details)) }
                // Only release this viewer after all three transactions are confirmed.
                onViewerReady(viewer)
                mutableState.update { it.copy(isFundAdded = funded.isNotEmpty(), fundedAddresses = funded.toList()) }
            }
            mutableState.update { it.copy(isFundAdded = funded.isNotEmpty(), fundedAddresses = funded) }
        } finally {
            mutableState.update { it.copy(isFunding = false) }
        }
    }

    private fun getOrInitChannelId(
        viewer: String,
        signer: MppWalletSigner,
    ): ByteArray =
        viewerChannelIds.getOrPut(viewer) {
            configureDebugSessionContext(viewer, state.value.creator, signer.authorizedSignerPublicKey)
        }

    fun closeAllSessions(): Job =
        applicationScope.launch {
            try {
                val vaultContext = contextUseCase()
                val creatorAddress = state.value.creator
                EscrowSessionVaultHybridManagerClient.configureForNetwork(vaultContext.network)
                EscrowSessionVaultHybridManagerClient.hostAddress = creatorAddress

                val addresses = viewers.filter { it.isNotBlank() }
                val signer = mppWalletSignerUseCase(creatorAddress)
                for (viewer in addresses) {
                    if (signer != null) {
                        val channelId = getOrInitChannelId(viewer, signer)

                        // Close Session Vault
                        withContext(Dispatchers.Default) {
                            MppPayments.closeSessionVault(signer = signer, channelId = channelId)
                        }.onSuccess { txId ->
                            Napier.d("[AUTO_CLOSE_OK] viewer=$viewer txId=$txId", tag = "LiquidStreamLiveDebugVM")
                        }.onFailure { err ->
                            Napier.e("[AUTO_CLOSE_ERR] viewer=$viewer", err, tag = "LiquidStreamLiveDebugVM")
                        }
                    }
                }
            } catch (e: Exception) {
                Napier.e("Close sessions failed", e, tag = "LiquidStreamLiveDebugVM")
            }
        }

    fun configure(
        isPaid: Boolean,
        costMicroUsdc: Long,
        payoutBlocks: Int,
    ) {
        if (costMicroUsdc < 0 || payoutBlocks <= 0) {
            mutableState.update { it.copy(error = "Cost must be nonnegative and payout blocks positive") }
            return
        }
        configuration.value = LiquidStreamDebugConfiguration(isPaid, costMicroUsdc, payoutBlocks)
    }

    fun startBots(onChat: (ChatMessage) -> Unit) {
        if (startupRequested) return
        startupRequested = true
        if (!state.value.loaded) {
            pendingStart = onChat
            return
        }
        launchBots(onChat)
    }

    private fun launchBots(onChat: (ChatMessage) -> Unit) {
        val captured = context ?: return
        if (!state.value.loaded || state.value.running) return
        val token = ++generation
        val previous = producerJob
        val creator = state.value.creator
        botProgressMap.clear()
        mutableState.update {
            it.copy(
                running = true,
                isFunding = viewers.isNotEmpty(),
                isFundAdded = false,
                fundedAddresses = emptyList(),
                error = null,
                billingErrors = emptyMap(),
                botDetails = emptyMap(),
            )
        }
        producerJob =
            viewModelScope.launch {
                try {
                    // A rapid Stop/Start cannot overlap old and new channel producers/drains.
                    previous?.join()
                    runner.run(
                        captured,
                        creator,
                        viewers,
                        configuration,
                        prepareViewers = { ready -> fundSessions(onViewerReady = ready) },
                        onDetails = { viewer, details ->
                            if (generation == token) {
                                val updated = botProgressMap.getOrPut(viewer) { HostViewerProgress() }.update(details)
                                mutableState.update { it.copy(botDetails = it.botDetails + ("debug:$viewer" to updated)) }

                                val remaining = details.progressBalanceMicroUsdc ?: details.remainingBalanceMicroUsdc
                                if (remaining != null && remaining <= LiquidStreamConstants.SESSION_VAULT_LOW_BALANCE_MICRO_USDC) {
                                    if (!mutableState.value.isFunding) {
                                        Napier.d(
                                            "[AUTO_DEPOSIT_TRIGGERED] viewer=$viewer balance=$remaining threshold=${LiquidStreamConstants.SESSION_VAULT_LOW_BALANCE_MICRO_USDC}",
                                            tag = "LiquidStreamLiveDebugVM",
                                        )
                                        openSessionAndDeposit(checkLowBalanceOnly = true)
                                    }
                                }
                            }
                        },
                        onChat = { if (generation == token) onChat(it) },
                        onError = { error ->
                            if (generation == token) mutableState.update { it.copy(error = it.error ?: error) }
                        },
                        onBillingError = { viewer, error ->
                            if (generation == token) {
                                mutableState.update {
                                    it.copy(
                                        billingErrors =
                                            if (error == null) {
                                                it.billingErrors - viewer
                                            } else {
                                                it.billingErrors + (viewer to error)
                                            },
                                    )
                                }
                            }
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == token) mutableState.update { it.copy(error = e.message ?: "Bots stopped") }
                } finally {
                    if (generation == token) {
                        mutableState.update {
                            it.copy(
                                running = false,
                                isFunding = false,
                                isFundAdded = false,
                                fundedAddresses = emptyList(),
                            )
                        }
                    }
                }
            }
    }

    fun stopBots() {
        ++generation // Reject callbacks even while a cancelled receiver is draining on applicationScope.
        pendingStart = null
        botProgressMap.clear()
        producerJob?.cancel()
        fundingJob?.cancel()
        mutableState.update {
            it.copy(
                running = false,
                isFunding = false,
                isFundAdded = false,
                fundedAddresses = emptyList(),
                botDetails = emptyMap(),
            )
        }
    }

    override fun onCleared() {
        stopBots()
        super.onCleared()
    }
}
