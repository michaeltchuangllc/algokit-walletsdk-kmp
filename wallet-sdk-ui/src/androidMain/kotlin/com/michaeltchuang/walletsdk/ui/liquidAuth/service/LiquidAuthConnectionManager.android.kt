package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.HostViewerSession
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.toIceCandidatePairStats
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.toIceTransportStats
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.IceConnectionClass
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.classifyIceConnectionType
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamCreator
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.CreatorVoucherClaimSnapshot
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.configuration.IceServerConfig
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LiquidStreamBlockConsumptionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.ViewerVaultBillingSession
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.relayHostChat
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_BATCH_BLOCK_COUNT
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_256_BLOCKS_TAB_ID
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.koin.java.KoinJavaComponent
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64

/**
 * Android implementation of LiquidAuthConnectionManager.
 *
 * Binds to SignalService and manages WebRTC peer connections.
 * Tracks ICE connection type for quality indicators and billing.
 */
actual class LiquidAuthConnectionManager actual constructor(
    platformContext: Any,
) {
    companion object {
        private const val TAG = "CommonLiquidAuthCM"
        private const val NOTIFICATION_ID = 1338
        private const val CHANNEL_ID = "liquid_auth_broadcast"
        private const val VIEWER_DETAILS_POLL_INTERVAL_MS = 5_000L
    }

    private val context = platformContext as Context
    private val koin = KoinJavaComponent.getKoin()
    private val mppWalletSignerUseCase: MppWalletSignerUseCase = koin.get(clazz = MppWalletSignerUseCase::class)
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        koin.get(clazz = GetRemainingSessionVaultBalanceUseCase::class)
    private val getMppVoucherNoteUseCase: GetMppVoucherNoteUseCase =
        koin.get(clazz = GetMppVoucherNoteUseCase::class)
    private val voucherRepository: MppVoucherRepository =
        koin.get(clazz = MppVoucherRepository::class)
    private var viewModel: LiquidAuthOfferViewModel? = null
    private val platformServices: LiquidAuthPlatformServices = KoinJavaComponent.get(LiquidAuthPlatformServices::class.java)
    private var signalService: SignalService? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var permissionPending = false
    private var listeningRequested = false
    private var activeRequestId: String? = null

    // Invitation IDs are not the primary payment session ID. Never promote another peer.
    private var primaryViewerId: String? = null
    private var hostOrigin: String? = null
    private var hostStarted = false

    @Volatile
    private var hostGeneration = 0L
    private val invitations = linkedSetOf<String>()
    private val retiredInvitationIds = mutableSetOf<String>()
    private val connectedViewerIds = linkedSetOf<String>()
    private var paymentTemplate: ResolvedLiquidAuthPaymentRequest? = null
    private var creatorAddress: String? = null
    private var creatorNetwork: String? = null

    private class AdditionalViewer(
        val session: HostViewerSession,
        val generation: Long,
    ) {
        val sessionId = "mesh-${session.requestId}"
        var creator: LiquidStreamCreator? = null
        var viewerAddress: String? = null
        var signerKey: ByteArray? = null
        var vaultChannelHint: ByteArray? = null
        var advertisedSalt: ByteArray? = null
        var config: ServerConfig? = null
        var connectionDetailsJob: Job? = null
        var vaultDetailsJob: Job? = null
        val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var billing: ViewerVaultBillingSession? = null
        var billingClosed = false
        var billingCloseJob: Job? = null
        var billingBlockJob: Job? = null
        var voucherDrainJob: Job? = null
        val pendingVouchers = ArrayDeque<LiquidAuthPaymentVoucherMessage>()
    }

    private val additionalViewers = linkedMapOf<String, AdditionalViewer>()
    private val hostCallbackScope = CoroutineScope(Dispatchers.Main.immediate)
    private val connectionTypePollingController =
        LiquidAuthPollingJobController(
            scope = CoroutineScope(Dispatchers.Default),
            runImmediately = true,
            onPoll = { pollCount ->
                if (pollCount > 0 && pollCount % 5 == 0) {
                    Napier.d("🔄 Connection type poll #$pollCount, service=$signalService", tag = TAG)
                }
                detectAndUpdateConnectionType()
            },
            onStop = { _connectionType.value = IceConnectionType.UNKNOWN },
        )
    private var liquidStreamCreator: LiquidStreamCreator? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activePaymentAmount: String? = null
    private var activePaymentNetwork: String? = null
    private var activeViewerAddressForVault: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null
    private var isPaidStreamingEnabled: Boolean = true

    /**
     * Viewer's authorized-signer public key received via the early [segment:handshake]
     * message.  Stored separately so it survives the race where the hello arrives before
     * [liquidStreamCreator] is constructed (i.e. before [sendPaymentRequest] is called).
     *
     * [sendPaymentRequest] picks this up as a fallback when
     * [activeCreatorVoucherClaimSnapshot] does not yet carry the key.
     */
    private var activeViewerAuthorizedSignerKey: ByteArray? = null

    // Connection type state flow - exposed for UI and billing
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    actual val connectionType: StateFlow<IceConnectionType> = _connectionType

    private val _viewerAddress = MutableStateFlow<String?>(null)
    actual val viewerAddress: StateFlow<String?> = _viewerAddress

    private fun setActiveViewerAddress(address: String?) {
        activeViewerAddressForVault = address
        _viewerAddress.value = address
    }

    private val blockConsumptionManager =
        LiquidStreamBlockConsumptionManager(
            tag = TAG,
            getViewModel = { viewModel },
            getActiveViewerAddress = { activeViewerAddressForVault },
            getActiveCreatorAddress = { activePaymentRecipient },
            getCreatorVoucherClaimSnapshot = { activeCreatorVoucherClaimSnapshot },
            getIsPaidStreaming = { isPaidStreamingEnabled },
            buildCreatorWalletSigner = { creatorAddress -> mppWalletSignerUseCase(creatorAddress) },
            getMppVoucherNoteUseCase = getMppVoucherNoteUseCase,
            voucherRepository = voucherRepository,
        )

    actual fun initialize(viewModel: LiquidAuthOfferViewModel) {
        Napier.d("🔌 initialize() called with viewModel=$viewModel", tag = TAG)
        this.viewModel = viewModel
        viewModel.meshHostingEnabled = true
        Napier.d("🔌 viewModel set, this.viewModel=${this.viewModel}", tag = TAG)
        blockConsumptionManager.processPendingSettlements()
    }

    /**
     * Start X402 block consumption
     */
    actual fun startBlockConsumption(sessionId: String) {
        if (primaryViewerId != null && primaryViewerId !in connectedViewerIds) return
        val targetSessionId = activePaymentSessionId ?: sessionId
        Napier.e(
            "[SESSION_VAULT_START_BLOCK] requested=$sessionId activePaymentSession=$activePaymentSessionId target=$targetSessionId",
            tag = TAG,
        )
        if (activePaymentSessionId != null && activePaymentSessionId != sessionId) {
            Napier.e("[SESSION_VAULT_SESSION_MISMATCH] requested=$sessionId usingActivePaymentSession=$targetSessionId", tag = TAG)
        }
        blockConsumptionManager.start(targetSessionId)
    }

    /**
     * Stop block consumption
     */
    actual fun stopBlockConsumption() {
        blockConsumptionManager.stop()
        if (additionalViewers.values.any { !it.billingClosed && it.billing != null }) {
            viewModel?.startRealtimeBlockNumberUpdates()
        }
    }

    actual fun setIsPaidStreaming(enabled: Boolean) {
        Napier.d("💰 setIsPaidStreaming: $enabled", tag = TAG)
        isPaidStreamingEnabled = enabled
        // NO stopBlockConsumption() here — keep tracking in both modes.
        if (!enabled) {
            setStreamCost(0L)
            // Clear any stale claim snapshot so we don't settle old vouchers when resuming to Paid.
            activeCreatorVoucherClaimSnapshot = null
            activePaymentSessionId?.let { sessionId ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        voucherRepository.deleteVoucherBySessionId(sessionId)
                        Napier.d("💰 Cleared pending vouchers for session $sessionId during switch to Free", tag = TAG)
                    } catch (e: Exception) {
                        Napier.e("❌ Failed to clear vouchers", e, tag = TAG)
                    }
                }
            }
        } else {
            // Resume if we have a session
            activePaymentSessionId?.let { startBlockConsumption(it) }
        }
        refreshAdditionalCreators()
    }

    actual fun setStreamCost(cost: Long) {
        Napier.d("💰 setStreamCost: $cost", tag = TAG)
        activePaymentAmount = cost.toString()
        liquidStreamCreator?.let {
            updateCreatorViewerSignerConfig(activeViewerAuthorizedSignerKey)
        }
        paymentTemplate =
            paymentTemplate?.let {
                it.copy(amount = cost.toString(), gatingConfig = it.gatingConfig.copy(amount = cost.toString()))
            }
        refreshAdditionalCreators()
    }

    actual fun setPayoutFrequency(tabId: String) {
        val blocks =
            if (tabId == PAYOUT_EVERY_256_BLOCKS_TAB_ID) {
                PAYOUT_BATCH_BLOCK_COUNT
            } else {
                1
            }
        Napier.d("💰 setPayoutFrequency: tab=$tabId blocks=$blocks", tag = TAG)
        blockConsumptionManager.payoutFrequencyBlocks = blocks
        additionalViewers.values.toList().forEach { viewer ->
            if (!viewer.billingClosed) viewer.billing?.updatePayoutFrequencyBlocks(blocks)
        }
    }

    /**
     * Send payment request to client
     */
    actual fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        paymentTemplate = resolveLiquidAuthPaymentRequest(paymentRequest)
        creatorAddress = paymentTemplate?.recipient
        creatorNetwork = paymentTemplate?.network
        refreshAdditionalCreators()
        // A departed primary must not turn an update for remaining peers into a VM billing error.
        if (primaryViewerId != null && primaryViewerId !in connectedViewerIds) return
        val service = signalService
        if (!platformServices.isHostPeerConnectionReady(service)) {
            val message = "MPP payment rail unavailable: peer connection is not ready"
            Napier.e(message, tag = TAG)
            viewModel?.onMppPaymentRejected(message)
            return
        }

        val paymentChannel = platformServices.getOrCreateHostPaymentDataChannel(service)
        if (paymentChannel == null) {
            val message = "MPP payment channel unavailable"
            Napier.e(message, tag = TAG)
            viewModel?.onMppPaymentRejected(message)
            return
        }

        try {
            activeCreatorVoucherClaimSnapshot = null
            if (activeViewerAddressForVault.isNullOrBlank()) {
                Napier.e(
                    "[SESSION_VAULT_VIEWER_SET_FROM_REQUEST] viewer=$activeViewerAddressForVault session=${paymentRequest.id}",
                    tag = TAG,
                )
            }
            val resolvedPaymentRequest = resolveLiquidAuthPaymentRequest(paymentRequest)
            val network = resolvedPaymentRequest.network
            val amount = resolvedPaymentRequest.amount
            val recipient = resolvedPaymentRequest.recipient
            // Keep one creator-side payment session id stable for the active connection.
            // If incoming requests churn ids for the same stream, lock to active id.
            val resolvedSessionId = activePaymentSessionId ?: paymentRequest.id
            if (activePaymentSessionId != null && activePaymentSessionId != paymentRequest.id) {
                Napier.w(
                    "[SESSION_VAULT_SESSION_LOCKED] incoming=${paymentRequest.id} active=$activePaymentSessionId using=$resolvedSessionId",
                    tag = TAG,
                )
            }
            val asset = resolvedPaymentRequest.asset
            Napier.d("💰 Building MPP payment request: network=$network recipient=$recipient asset=$asset amount=$amount", tag = TAG)
            val serverConfig =
                ServerConfig(
                    sessionId = resolvedSessionId,
                    gating = resolvedPaymentRequest.gatingConfig,
                    gracePeriod = 5,
                    viewerAddress = activeViewerAddressForVault,
                    // Prefer the key from the most-recent voucher (authoritative).
                    // Fall back to the early hello key for first-connection scenarios where
                    // no voucher exists yet but the viewer already sent segment:handshake.
                    viewerAuthorizedSignerPublicKey =
                        activeCreatorVoucherClaimSnapshot
                            ?.viewerPublicKeyBase64
                            ?.takeIf { it.isNotBlank() }
                            ?.let { encoded ->
                                runCatching {
                                    Base64.decode(encoded)
                                }.getOrNull()
                            }
                            ?: activeViewerAuthorizedSignerKey,
                    skipPaymentRequestWhenSessionFunded = true,
                )

            val current = liquidStreamCreator
            val shouldRecreate =
                current == null ||
                    activePaymentRecipient != recipient

            if (shouldRecreate) {
                current?.terminate("replaced")
                val creator =
                    LiquidStreamCreator(
                        dataChannel = wrapHostPaymentChannel(paymentChannel, primaryViewerId),
                        rtpSenders = emptyList(),
                        mppServerConfig =
                            MppServerConfig(
                                network = network,
                                recipient = recipient,
                                secretKey = "liquid-auth-mpp-${activeRequestId ?: paymentRequest.id}",
                            ),
                        serverConfig = serverConfig,
                        getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
                    )
                val generation = hostGeneration

                fun isCurrentCreator() = generation == hostGeneration && liquidStreamCreator === creator
                creator.rtcServer.onViewerHello = { viewer, viewerPublicKeyBase64 ->
                    if (isCurrentCreator()) {
                        val helloJson = """{"type":"segment:handshake","viewer":"$viewer","viewerPublicKey":"$viewerPublicKeyBase64"}"""
                        tryCaptureViewerAddressFromMessage(helloJson)
                    }
                }
                creator.rtcServer.onVoucherReceived = { voucherJson ->
                    if (isCurrentCreator() &&
                        activePaymentSessionId != null &&
                        parseLiquidAuthHostTransportMessage(voucherJson).paymentVoucher?.sessionId == activePaymentSessionId
                    ) {
                        tryCaptureViewerAddressFromMessage(voucherJson)
                    }
                }
                creator.rtcServer.onPaymentSettled = settled@{ receipt ->
                    if (!isCurrentCreator()) return@settled
                    receipt.payFrom
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            if (it != activeViewerAddressForVault) {
                                Napier.e("[SESSION_VAULT_VIEWER_SET_FROM_RECEIPT] viewer=$it txId=${receipt.txId}", tag = TAG)
                            }
                            setActiveViewerAddress(it)
                        }

                    Napier.e(
                        "[SESSION_VAULT_ON_PAYMENT_SETTLED] txId=${receipt.txId} payFrom=${receipt.payFrom} viewerForVault=$activeViewerAddressForVault session=${receipt.sessionId} activePaymentSession=$activePaymentSessionId",
                        tag = TAG,
                    )
                    viewModel?.onMppPaymentSettled(receipt.txId)

                    val targetSession = activePaymentSessionId ?: receipt.sessionId
                    if (targetSession.isNotBlank() && isPaidStreamingEnabled) {
                        Napier.e("[SESSION_VAULT_FORCE_START_BLOCK] txId=${receipt.txId} targetSession=$targetSession", tag = TAG)
                        startBlockConsumption(targetSession)
                    } else {
                        Napier.e(
                            "[SESSION_VAULT_FORCE_START_BLOCK_SKIP] reason=missing_session_or_free txId=${receipt.txId} isPaid=$isPaidStreamingEnabled",
                            tag = TAG,
                        )
                    }
                }
                creator.rtcServer.onPaymentRejected = { reason ->
                    Napier.e("💰 MPP payment rejected: $reason", tag = TAG)
                    if (isCurrentCreator()) viewModel?.onMppPaymentRejected(reason)
                }
                creator.rtcServer.onError = { e ->
                    Napier.e("💰 MPP creator error", e, tag = TAG)
                    if (isCurrentCreator()) viewModel?.onMppPaymentRejected(e.message ?: "MPP creator error")
                }
                creator.onChatMessageReceived = { message ->
                    if (isCurrentCreator()) {
                        blockConsumptionManager.recordChatMessage(message)
                        viewModel?.onChatMessageReceived(message)
                        broadcastChat(message, source = creator)
                    }
                }
                liquidStreamCreator = creator
                activePaymentSessionId = resolvedSessionId
                activePaymentRecipient = recipient
                activePaymentAmount = amount
                activePaymentNetwork = network
                creator.start()
                Napier.e(
                    "[SESSION_VAULT_BOOTSTRAP_START_BLOCK] source=creator_initialized session=$resolvedSessionId viewer=$activeViewerAddressForVault recipient=$recipient",
                    tag = TAG,
                )
                // Notify the viewer of the creator's payment address + session so it can
                // set up its payment flow (replaces the old `liquid:video:frame` piggyback).
                sendCreatorSessionInfo(recipient, resolvedSessionId)
                if (isPaidStreamingEnabled) {
                    startBlockConsumption(resolvedSessionId)
                }
            } else {
                current.updateConfig(serverConfig)
                activePaymentSessionId = resolvedSessionId
                activePaymentRecipient = recipient
                activePaymentAmount = amount
                activePaymentNetwork = network
                Napier.e(
                    "[SESSION_VAULT_BOOTSTRAP_START_BLOCK] source=creator_reused session=$resolvedSessionId viewer=$activeViewerAddressForVault recipient=$recipient",
                    tag = TAG,
                )
                sendCreatorSessionInfo(recipient, resolvedSessionId)
                if (isPaidStreamingEnabled) {
                    startBlockConsumption(resolvedSessionId)
                }
            }
        } catch (e: Exception) {
            Napier.e("💰 Failed to initialize MPP creator", e, tag = TAG)
            viewModel?.onMppPaymentRejected(e.message ?: "MPP initialization failed")
        }
    }

    actual fun setupCreator(
        creatorAddress: String,
        network: String,
    ) {
        this.creatorAddress = creatorAddress
        creatorNetwork = resolveLiquidAuthMppNetwork(network)
        refreshAdditionalCreators()
        val service = signalService ?: return
        if (!platformServices.isHostPeerConnectionReady(service)) return

        val paymentChannel = platformServices.getOrCreateHostPaymentDataChannel(service) ?: return

        try {
            val current = liquidStreamCreator
            if (current != null && activePaymentRecipient == creatorAddress) return

            val resolvedNetwork = resolveLiquidAuthMppNetwork(network)
            val sessionId = activePaymentSessionId ?: "chat-session-${activeRequestId ?: System.currentTimeMillis()}"

            val serverConfig =
                ServerConfig(
                    sessionId = sessionId,
                    gating =
                        GatingConfig(
                            mode = GatingMode.PARTIAL_TIME,
                            amount = "0", // Free by default until requested
                            asset = "USDC",
                            network = resolvedNetwork,
                            payTo = creatorAddress,
                        ),
                    gracePeriod = 5,
                    viewerAddress = activeViewerAddressForVault,
                    viewerAuthorizedSignerPublicKey = activeViewerAuthorizedSignerKey,
                    skipPaymentRequestWhenSessionFunded = true,
                )

            current?.terminate("replaced")
            val creator =
                LiquidStreamCreator(
                    dataChannel = wrapHostPaymentChannel(paymentChannel, primaryViewerId),
                    rtpSenders = emptyList(),
                    mppServerConfig =
                        MppServerConfig(
                            network = resolvedNetwork,
                            recipient = creatorAddress,
                            secretKey = "liquid-auth-chat-${activeRequestId ?: sessionId}",
                        ),
                    serverConfig = serverConfig,
                    getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
                )

            val generation = hostGeneration

            fun isCurrentCreator() = generation == hostGeneration && liquidStreamCreator === creator
            creator.onChatMessageReceived = { message ->
                if (isCurrentCreator()) {
                    blockConsumptionManager.recordChatMessage(message)
                    viewModel?.onChatMessageReceived(message)
                    broadcastChat(message, source = creator)
                }
            }

            creator.rtcServer.onViewerHello = { viewer, viewerPublicKeyBase64 ->
                if (isCurrentCreator()) {
                    val helloJson = """{"type":"segment:handshake","viewer":"$viewer","viewerPublicKey":"$viewerPublicKeyBase64"}"""
                    tryCaptureViewerAddressFromMessage(helloJson)
                }
            }
            creator.rtcServer.onVoucherReceived = { voucherJson ->
                if (isCurrentCreator() &&
                    activePaymentSessionId != null &&
                    parseLiquidAuthHostTransportMessage(voucherJson).paymentVoucher?.sessionId == activePaymentSessionId
                ) {
                    tryCaptureViewerAddressFromMessage(voucherJson)
                }
            }

            liquidStreamCreator = creator
            activePaymentRecipient = creatorAddress
            activePaymentNetwork = resolvedNetwork
            activePaymentSessionId = sessionId
            creator.start()
            sendCreatorSessionInfo(creatorAddress, sessionId)

            Napier.d("💬 Chat initialized for creator=$creatorAddress network=$resolvedNetwork", tag = TAG)
        } catch (e: Exception) {
            Napier.e("💰 Failed to setup chat creator", e, tag = TAG)
        }
    }

    /**
     * Start polling for connection type changes.
     * This monitors the ICE connection and updates the flow.
     */
    private fun startConnectionTypePolling() {
        Napier.d("🔄 Starting connection type polling", tag = TAG)
        connectionTypePollingController.start()
    }

    /**
     * Stop polling for connection type.
     */
    private fun stopConnectionTypePolling() {
        connectionTypePollingController.stop()
    }

    /**
     * Detect the current ICE connection type from WebRTC stats.
     */
    private fun detectAndUpdateConnectionType() {
        val generation = hostGeneration
        Napier.d("🔍 Trying to detect connection type... signalService=$signalService", tag = TAG)
        signalService?.let { service ->
            Napier.d("🔍 SignalService available, peerConnection=${service.peerConnection}", tag = TAG)
            // Map SignalService.IceConnectionType to our UI model
            service.detectConnectionType { type ->
                hostCallbackScope.launch {
                    if (generation != hostGeneration || signalService !== service || primaryViewerId !in connectedViewerIds) {
                        return@launch
                    }
                    Napier.d("🔍 Raw connection type from service: $type", tag = TAG)
                    val mappedType =
                        when (type) {
                            SignalService.IceConnectionType.LOCAL -> IceConnectionType.LOCAL
                            SignalService.IceConnectionType.STUN -> IceConnectionType.STUN
                            SignalService.IceConnectionType.RELAY -> IceConnectionType.RELAY
                            SignalService.IceConnectionType.FAILED -> IceConnectionType.FAILED
                            SignalService.IceConnectionType.UNKNOWN -> IceConnectionType.UNKNOWN
                        }

                    if (_connectionType.value != mappedType) {
                        _connectionType.value = mappedType
                        Napier.d("🌐 Connection type changed: ${mappedType.displayName()}", tag = TAG)

                        // Notify view model for any connection-type specific logic
                        viewModel?.onConnectionTypeChanged(mappedType)
                    }
                }
            }
        } ?: Napier.w("⚠️ Cannot detect - signalService is null", tag = TAG)
    }

    actual fun startListening(
        origin: String,
        requestId: String,
    ) {
        Napier.d(
            "🔌 startListening() called - isBound=$isBound, activeRequestId=$activeRequestId, newRequestId=$requestId, viewModel=$viewModel",
            tag = TAG,
        )

        // Prevent broadcast from interfering with active viewer session.
        // Both viewer and broadcaster share the same SignalService, and
        // SignalService.start() disconnects any existing WebRTC client.
        if (AnswerScreenState.isVisible || ConnectionStatusState.isVisible) {
            Napier.w(
                "⛔ Active viewer session detected (AnswerScreenState.isVisible=${AnswerScreenState.isVisible}, " +
                    "ConnectionStatusState.isVisible=${ConnectionStatusState.isVisible}). " +
                    "Skipping broadcast start to avoid disconnecting viewer.",
                tag = TAG,
            )
            return
        }

        if (viewModel == null) {
            Napier.e("❌ Cannot start listening - viewModel is null! Call initialize() first", tag = TAG)
            return
        }
        if (hostOrigin != null && hostOrigin != origin) {
            Napier.e("Stop the broadcast before changing its origin", tag = TAG)
            viewModel?.onMeshViewerError("Stop the broadcast before changing its origin")
            return
        }
        if (requestId in retiredInvitationIds) return
        invitations.add(requestId)
        listeningRequested = true
        hostOrigin = origin
        // Queue invitations even while bindService is still pending.
        if (isBound) {
            signalService?.let { setupSignalService(it, origin) }
            return
        }

        ensureHostPermissions()
    }

    private fun hasHostPermissions(): Boolean =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensureHostPermissions() {
        if (!listeningRequested || permissionPending) return
        if (hasHostPermissions()) {
            bindHostService()
            return
        }
        val activity = context as? ComponentActivity
        if (activity == null) {
            failPendingInvitations("Camera and microphone permissions require a ComponentActivity")
            return
        }
        val generation = hostGeneration
        permissionPending = true
        try {
            // No lifecycle owner: the manager can be initialized after Activity STARTED.
            permissionLauncher =
                activity.activityResultRegistry.register(
                    "liquid-host-permissions-${java.util.UUID.randomUUID()}",
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    if (generation != hostGeneration || !listeningRequested) return@register
                    permissionPending = false
                    permissionLauncher?.unregister()
                    permissionLauncher = null
                    if (hasHostPermissions()) {
                        // Invitations are already queued; do not re-enter startListening's dedupe.
                        bindHostService()
                    } else {
                        failPendingInvitations(
                            "Camera and microphone permissions are required to broadcast. Refresh the invitation to retry.",
                        )
                    }
                }
            permissionLauncher?.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } catch (error: Exception) {
            permissionPending = false
            permissionLauncher?.unregister()
            permissionLauncher = null
            failPendingInvitations(error.message ?: "Unable to request camera and microphone permissions")
        }
    }

    private fun failPendingInvitations(message: String) {
        val pending = invitations.filterNot { it in connectedViewerIds }
        pending.forEach { id ->
            invitations.remove(id)
            retiredInvitationIds.add(id)
            viewModel?.onMeshInvitationFailed(id, message)
        }
        if (pending.isEmpty()) viewModel?.onMeshViewerError(message)
    }

    private fun bindHostService() {
        if (!listeningRequested || isBound || !hasHostPermissions()) return
        if (AnswerScreenState.isVisible || ConnectionStatusState.isVisible) return
        val origin = hostOrigin ?: return

        val generation = ++hostGeneration
        serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    if (generation != hostGeneration || serviceConnection !== this) return
                    Napier.d("🔌 onServiceConnected called", tag = TAG)
                    val localBinder = binder as? SignalService.LocalBinder
                    signalService = localBinder?.getServerInstance()
                    Napier.d("SignalService connected, service=$signalService", tag = TAG)

                    signalService?.let { service ->
                        Napier.d("🔌 Calling setupSignalService...", tag = TAG)
                        setupSignalService(service, origin)
                    } ?: Napier.e("❌ SignalService is null after connection!", tag = TAG)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (generation != hostGeneration || serviceConnection !== this) return
                    Napier.d("SignalService disconnected", tag = TAG)
                    // Service death still must not invoke the legacy vault-closing callback.
                    stopListening()
                }
            }

        // Start and bind to service
        val intent = Intent(context, SignalService::class.java)
        isBound = true
        try {
            context.startForegroundService(intent)
            if (!context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)) {
                stopListening()
                viewModel?.onMeshViewerError("Unable to bind broadcast service")
            }
        } catch (error: Exception) {
            Napier.e("Failed to bind host service", error, tag = TAG)
            stopListening()
            viewModel?.onMeshViewerError(error.message ?: "Unable to start broadcast service")
        }
        Napier.d("🔌 Service bind initiated for requestId=$activeRequestId", tag = TAG)
    }

    private fun setupSignalService(
        service: SignalService,
        origin: String,
    ) {
        try {
            check(hasHostPermissions()) { "Camera and microphone permissions are required to broadcast" }
            if (!hostStarted) {
                service.startHost(
                    url = origin,
                    httpClient = OkHttpClient.Builder().build(),
                    notificationBuilder = createNotificationBuilder(),
                    notificationId = NOTIFICATION_ID,
                    activityClass = (context as? Activity)?.javaClass,
                )
                hostStarted = true
            }
            invitations.toList().forEach { requestId ->
                if (requestId !in service.hostViewerSessions) {
                    try {
                        addHostInvitation(service, requestId)
                    } catch (error: Exception) {
                        removeViewerState(requestId)
                        viewModel?.onMeshInvitationFailed(requestId, error.message ?: "Host invitation failed")
                    }
                }
            }
        } catch (error: Exception) {
            Napier.e("Failed to set up host invitation", error, tag = TAG)
            failPendingInvitations(error.message ?: "Failed to start broadcast")
        }
    }

    private fun addHostInvitation(
        service: SignalService,
        requestId: String,
    ) {
        val generation = hostGeneration

        fun isCurrent() = generation == hostGeneration && signalService === service
        service.addHostViewer(
            requestId = requestId,
            iceServers = IceServerConfig.iceServers,
            onConnected = connected@{ session ->
                if (!isCurrent() || service.hostViewerSessions[requestId] !== session) return@connected
                if (!connectedViewerIds.add(requestId)) return@connected
                if (primaryViewerId == null) {
                    primaryViewerId = requestId
                    activeRequestId = requestId
                    startConnectionTypePolling()
                } else if (primaryViewerId != requestId) {
                    val viewer = AdditionalViewer(session, generation)
                    additionalViewers[requestId] = viewer
                }
                if (service.hostViewerSessions[requestId] !== session) return@connected
                // The VM enters legacy billing only once and rotates the invitation.
                viewModel?.onMeshViewerConnected(requestId)
                additionalViewers[requestId]?.let { viewer ->
                    startAdditionalViewerDetailsPolling(viewer)
                    setupAdditionalCreator(viewer)
                }
            },
            onMessage = message@{ session, msg ->
                if (!isCurrent() || service.hostViewerSessions[requestId] !== session) return@message
                if (requestId == primaryViewerId) {
                    tryCaptureViewerAddressFromMessage(msg)
                    activePaymentRecipient?.let { recipient ->
                        activePaymentSessionId?.let { sendCreatorSessionInfo(recipient, it) }
                    }
                } else {
                    additionalViewers[requestId]?.let { viewer ->
                        onAdditionalViewer(viewer) {
                            captureAdditionalViewerHello(viewer, msg)
                            if (viewer.creator == null) setupAdditionalCreator(viewer)
                        }
                    }
                }
            },
            onDisconnected = { id ->
                if (isCurrent()) {
                    val wasPending = id in invitations && id !in connectedViewerIds
                    removeViewerState(id)
                    if (wasPending) viewModel?.onMeshInvitationFailed(id, "Viewer invitation disconnected")
                }
            },
            onError = { id, error ->
                if (isCurrent()) {
                    Napier.e("Host invitation failed: $id", error, tag = TAG)
                    removeViewerState(id)
                    viewModel?.onMeshInvitationFailed(id, error.message ?: "Host invitation failed")
                }
            },
        )
    }

    private fun removeViewerState(id: String) {
        connectedViewerIds.remove(id)
        invitations.remove(id)
        retiredInvitationIds.add(id)
        additionalViewers.remove(id)?.let { viewer ->
            viewer.connectionDetailsJob?.cancel()
            viewer.vaultDetailsJob?.cancel()
            closeAdditionalViewerBilling(viewer)
            runCatching { viewer.creator?.terminate("viewer_disconnected") }
        }
        if (id == primaryViewerId) {
            stopConnectionTypePolling()
            stopBlockConsumption()
            val creator = liquidStreamCreator
            liquidStreamCreator = null
            runCatching { creator?.terminate("viewer_disconnected") }
        }
        viewModel?.onMeshViewerDisconnected(id)
    }

    private fun isCurrent(viewer: AdditionalViewer): Boolean =
        viewer.generation == hostGeneration &&
            additionalViewers[viewer.session.requestId] === viewer &&
            signalService?.hostViewerSessions?.get(viewer.session.requestId) === viewer.session

    private fun onAdditionalViewer(
        viewer: AdditionalViewer,
        action: () -> Unit,
    ) {
        hostCallbackScope.launch {
            if (isCurrent(viewer)) action()
        }
    }

    private fun publishAdditionalViewerDetails(
        viewer: AdditionalViewer,
        update: (HostViewerDetails) -> HostViewerDetails = { it },
    ) {
        if (!isCurrent(viewer) || viewer.session.requestId !in connectedViewerIds) return
        val vm = viewModel ?: return
        // Merge on Main with the latest snapshot, including after an IO await.
        val current = vm.meshViewerDetails.value[viewer.session.requestId] ?: HostViewerDetails()
        vm.updateMeshViewerDetails(
            viewer.session.requestId,
            update(current.copy(viewerAddress = viewer.viewerAddress)),
        )
    }

    private fun startAdditionalViewerDetailsPolling(viewer: AdditionalViewer) {
        publishAdditionalViewerDetails(viewer)
        if (viewer.connectionDetailsJob?.isActive == true) return
        // One suspended stats request per peer; neither a slow peer nor a vault read
        // can delay another card. Late native callbacks cannot publish after removal.
        viewer.connectionDetailsJob =
            hostCallbackScope.launch {
                while (isActive && isCurrent(viewer)) {
                    // Retry prerequisites (for example a host salt loaded after the hello).
                    startAdditionalViewerVaultPolling(viewer)
                    val type =
                        try {
                            withTimeoutOrNull(3_000L) { readAdditionalViewerConnectionType(viewer) }
                                ?: IceConnectionType.UNKNOWN
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Napier.w("Unable to read mesh peer stats (${viewer.session.requestId})", error, tag = TAG)
                            IceConnectionType.UNKNOWN
                        }
                    if (!isCurrent(viewer)) return@launch
                    publishAdditionalViewerDetails(viewer) { it.copy(connectionType = type) }
                    delay(VIEWER_DETAILS_POLL_INTERVAL_MS)
                }
            }
    }

    private class ViewerVaultInputs(
        val address: String,
        val recipient: String,
        val signerKey: ByteArray,
        val network: String,
        val salt: ByteArray?,
        val channelId: ByteArray?,
    )

    private fun viewerVaultInputs(viewer: AdditionalViewer): ViewerVaultInputs? {
        val address = viewer.viewerAddress?.takeIf { it.isNotBlank() } ?: return null
        val signerKey = viewer.signerKey?.takeIf { it.isNotEmpty() }?.copyOf() ?: return null
        val recipient = (viewer.config?.gating?.payTo ?: creatorAddress)?.takeIf { it.isNotBlank() } ?: return null
        val network = viewer.config?.gating?.network ?: creatorNetwork ?: return null
        val channelId = viewer.vaultChannelHint?.copyOf()
        // Only derive using the actual salt observed on this peer's outgoing request.
        // An explicit channel hint needs no salt and is validated by the core reader.
        val salt = viewer.advertisedSalt?.copyOf()
        if (channelId == null && salt == null) return null
        return ViewerVaultInputs(address, recipient, signerKey, network, salt, channelId)
    }

    private fun isCurrentVaultRead(
        viewer: AdditionalViewer,
        inputs: ViewerVaultInputs,
    ): Boolean {
        if (!isCurrent(viewer)) return false
        val current = viewerVaultInputs(viewer) ?: return false
        return current.address == inputs.address &&
            current.recipient == inputs.recipient &&
            current.network == inputs.network &&
            current.signerKey.contentEquals(inputs.signerKey) &&
            current.salt.contentEquals(inputs.salt) &&
            current.channelId.contentEquals(inputs.channelId)
    }

    private fun startAdditionalViewerVaultPolling(viewer: AdditionalViewer) {
        if (!isCurrent(viewer) || viewer.vaultDetailsJob?.isActive == true) return
        // Do not query or invent a zero before the wallet and signer are known.
        if (viewerVaultInputs(viewer) == null) return
        viewer.vaultDetailsJob =
            hostCallbackScope.launch {
                while (isActive && isCurrent(viewer)) {
                    val inputs = viewerVaultInputs(viewer)
                    if (inputs != null) {
                        val snapshot =
                            try {
                                withTimeoutOrNull(10_000L) {
                                    withContext(Dispatchers.IO) {
                                        if (inputs.channelId != null) {
                                            HostViewerVaultReader
                                                .readChannel(
                                                    channelId = inputs.channelId,
                                                    viewerAddress = inputs.address,
                                                    creatorAddress = inputs.recipient,
                                                    authorizedSignerPublicKey = inputs.signerKey,
                                                    network = inputs.network,
                                                ).getOrThrow()
                                        } else {
                                            HostViewerVaultReader
                                                .read(
                                                    viewerAddress = inputs.address,
                                                    creatorAddress = inputs.recipient,
                                                    authorizedSignerPublicKey = inputs.signerKey,
                                                    network = inputs.network,
                                                    salt = checkNotNull(inputs.salt),
                                                ).getOrThrow()
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Napier.w("Unable to read mesh viewer vault (${viewer.session.requestId})", error, tag = TAG)
                                null
                            }
                        if (!isActive || !isCurrent(viewer)) return@launch
                        if (isCurrentVaultRead(viewer, inputs)) {
                            publishAdditionalViewerDetails(viewer) {
                                it.copy(
                                    remainingBalanceMicroUsdc = snapshot?.remainingBalanceMicroUsdc,
                                    lastSettledMicroUsdc = snapshot?.lastSettledMicroUsdc,
                                    progressBalanceMicroUsdc = snapshot?.progressBalanceMicroUsdc,
                                    totalDepositMicroUsdc = snapshot?.totalDepositMicroUsdc,
                                )
                            }
                        }
                    }
                    delay(VIEWER_DETAILS_POLL_INTERVAL_MS)
                }
            }
    }

    private suspend fun readAdditionalViewerConnectionType(viewer: AdditionalViewer): IceConnectionType {
        val peer = viewer.session.peer?.peerConnection ?: return IceConnectionType.UNKNOWN
        if (peer.connectionState() == PeerConnection.PeerConnectionState.FAILED ||
            peer.iceConnectionState() == PeerConnection.IceConnectionState.FAILED
        ) {
            return IceConnectionType.FAILED
        }
        return suspendCancellableCoroutine { continuation ->
            peer.getStats { report ->
                // No JNI or manager/VM state access on the native stats callback thread.
                if (continuation.isActive) continuation.resume(selectedConnectionType(report))
            }
        }
    }

    // Delegates to the shared, platform-agnostic classifier in wallet-sdk-core so this never
    // drifts from the legacy single-viewer path (SignalService.detectConnectionType) or iOS.
    private fun selectedConnectionType(report: RTCStatsReport): IceConnectionType =
        when (classifyIceConnectionType(report.toIceTransportStats(), report.toIceCandidatePairStats())) {
            IceConnectionClass.LOCAL -> IceConnectionType.LOCAL
            IceConnectionClass.STUN -> IceConnectionType.STUN
            IceConnectionClass.RELAY -> IceConnectionType.RELAY
            IceConnectionClass.UNKNOWN -> IceConnectionType.UNKNOWN
        }

    private fun refreshAdditionalCreators() {
        additionalViewers.values.toList().forEach(::setupAdditionalCreator)
    }

    private fun ensureAdditionalViewerBilling(viewer: AdditionalViewer) {
        if (!isCurrent(viewer) || viewer.billingClosed) return
        if (viewer.billing == null) {
            val config = viewer.config ?: return
            val address = viewer.viewerAddress?.takeIf { it.isNotBlank() } ?: return
            val signerKey = viewer.signerKey?.takeIf { it.isNotEmpty() } ?: return
            viewer.billing =
                ViewerVaultBillingSession(
                    scope = viewer.billingScope,
                    sessionId = viewer.sessionId,
                    viewerAddress = address,
                    creatorAddress = config.gating.payTo,
                    network = config.gating.network,
                    signerPublicKey = signerKey.copyOf(),
                    buildCreatorWalletSigner = { recipient -> mppWalletSignerUseCase(recipient) },
                    onSnapshot = { snapshot ->
                        onAdditionalViewer(viewer) {
                            if (!viewer.billingClosed) {
                                publishAdditionalViewerDetails(viewer) {
                                    it.copy(
                                        remainingBalanceMicroUsdc = snapshot.remainingBalanceMicroUsdc,
                                        lastSettledMicroUsdc = snapshot.lastSettledMicroUsdc,
                                        progressBalanceMicroUsdc = snapshot.progressBalanceMicroUsdc,
                                        totalDepositMicroUsdc = snapshot.totalDepositMicroUsdc,
                                    )
                                }
                            }
                        }
                    },
                    onError = { error ->
                        Napier.e("Mesh vault billing failed (${viewer.sessionId})", error, tag = TAG)
                    },
                    payoutFrequencyBlocks = blockConsumptionManager.payoutFrequencyBlocks,
                )
        }
        drainAdditionalViewerVouchers(viewer)
        if (viewer.billingBlockJob?.isActive == true) return
        val vm = viewModel ?: return
        vm.startRealtimeBlockNumberUpdates()
        val billing = checkNotNull(viewer.billing)
        viewer.billingBlockJob =
            viewer.billingScope.launch {
                var lastRound: Long? = null
                vm.currentBlockNumber.collect { round ->
                    if (!viewer.billingClosed &&
                        isCurrent(viewer) &&
                        round != null &&
                        round > (lastRound ?: 0L)
                    ) {
                        lastRound = round
                        billing.onBlock(round)
                    }
                }
            }
    }

    private fun drainAdditionalViewerVouchers(viewer: AdditionalViewer) {
        val billing = viewer.billing ?: return
        if (viewer.billingClosed || viewer.voucherDrainJob?.isActive == true) return
        viewer.voucherDrainJob =
            viewer.billingScope.launch {
                while (viewer.pendingVouchers.isNotEmpty()) {
                    val voucher = viewer.pendingVouchers.removeFirst()
                    try {
                        if (!billing.acceptVoucher(voucher)) {
                            Napier.w("Mesh voucher rejected (${viewer.sessionId})", tag = TAG)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Napier.e("Mesh voucher acceptance failed (${viewer.sessionId})", error, tag = TAG)
                    }
                }
            }
    }

    private fun closeAdditionalViewerBilling(viewer: AdditionalViewer) {
        if (viewer.billingClosed) return
        viewer.billingClosed = true
        viewer.billingBlockJob?.cancel()
        viewer.billingBlockJob = null
        val billing = viewer.billing
        viewer.billingCloseJob =
            viewer.billingScope.launch {
                try {
                    viewer.voucherDrainJob?.join()
                    billing?.close()?.join()
                } finally {
                    viewer.billing = null
                    viewer.pendingVouchers.clear()
                    viewer.billingScope.cancel()
                }
            }
    }

    private fun rejectAdditionalViewer(
        viewer: AdditionalViewer,
        reason: String,
    ) {
        if (!isCurrent(viewer)) return
        viewModel?.onMeshInvitationFailed(viewer.session.requestId, reason)
        // Explain on the application channel too if the payment channel was never opened.
        runCatching {
            viewer.session.send(
                buildJsonObject {
                    put("type", DCMessageType.SESSION_TERMINATE.value)
                    put("sessionId", viewer.sessionId)
                    put("payload", buildJsonObject { put("reason", reason) })
                }.toString(),
            )
        }
        // Prevent termination callbacks from recursively removing the same viewer.
        closeAdditionalViewerBilling(viewer)
        viewer.creator?.rtcServer?.onSessionTerminated = null
        runCatching { viewer.creator?.terminate(reason) }
        signalService?.removeHostViewer(viewer.session.requestId)
    }

    /**
     * The service tears down native channels before its disconnect callback. Core payment
     * timers/termination must not call JNI on a channel that has already been disposed.
     */
    private fun wrapHostPaymentChannel(
        channel: org.webrtc.DataChannel,
        requestId: String?,
    ): RtcDataChannel {
        val transport = platformServices.wrapPaymentDataChannel(channel)
        val generation = hostGeneration
        val service = signalService
        val session = service?.hostViewerSessions?.get(requestId)

        fun isAlive() =
            generation == hostGeneration &&
                signalService === service &&
                session != null &&
                service.hostViewerSessions[requestId] === session
        return object : RtcDataChannel {
            override fun state() = if (isAlive()) transport.state() else RtcDataChannelState.CLOSED

            override fun send(bytes: ByteArray) {
                if (isAlive()) transport.send(bytes)
            }

            override fun close() {
                if (isAlive()) transport.close()
            }

            override fun registerObserver(observer: RtcDataChannelObserver) {
                if (!isAlive()) return
                transport.registerObserver(
                    object : RtcDataChannelObserver {
                        override fun onStateChange() {
                            hostCallbackScope.launch { observer.onStateChange() }
                        }

                        override fun onMessage(data: ByteArray) {
                            hostCallbackScope.launch {
                                if (isAlive()) observer.onMessage(data)
                            }
                        }
                    },
                )
            }
        }
    }

    private fun setupAdditionalCreator(viewer: AdditionalViewer) {
        if (!isCurrent(viewer) || viewer.billingClosed) return
        val recipient = creatorAddress?.takeIf { it.isNotBlank() }
        val network = creatorNetwork
        if (recipient == null || network == null) {
            return
        }
        val template = paymentTemplate?.takeIf { it.recipient == recipient && it.network == network }
        val gating =
            (
                template?.gatingConfig ?: GatingConfig(
                    mode = GatingMode.PARTIAL_TIME,
                    amount = "0", // setupCreator is a free chat bootstrap, not a paid request.
                    asset = "USDC",
                    network = network,
                    payTo = recipient,
                    segmentDuration = 3,
                    leadTime = 0,
                )
            ).let { if (isPaidStreamingEnabled) it else it.copy(amount = "0") }
        val config =
            ServerConfig(
                sessionId = viewer.sessionId,
                gating = gating,
                gracePeriod = 5,
                viewerAddress = viewer.viewerAddress,
                viewerAuthorizedSignerPublicKey = viewer.signerKey,
                skipPaymentRequestWhenSessionFunded = true,
                vaultOnlyBilling = true,
            )
        try {
            val previousConfig = viewer.config
            if ((viewer.creator != null || viewer.billing != null) &&
                previousConfig != null &&
                (previousConfig.gating.payTo != recipient || previousConfig.gating.network != network)
            ) {
                // Reopening a terminated SCTP channel under the same label is not reliable.
                // Fail this peer only; never retarget an in-flight payment to another wallet.
                Napier.w("Creator/network changed; reconnect mesh viewer ${viewer.session.requestId}", tag = TAG)
                signalService?.removeHostViewer(viewer.session.requestId)
                return
            }
            viewer.config = config
            ensureAdditionalViewerBilling(viewer)
            startAdditionalViewerVaultPolling(viewer)
            val current = viewer.creator
            if (current != null) {
                current.updateConfig(config)
                sendAdditionalSessionInfo(viewer, recipient)
                return
            }
            val channel =
                viewer.session.createDataChannel("x402-payment-channel") ?: run {
                    Napier.w("Payment channel not ready for mesh viewer ${viewer.session.requestId}", tag = TAG)
                    return
                }
            val creator =
                LiquidStreamCreator(
                    dataChannel = wrapHostPaymentChannel(channel, viewer.session.requestId),
                    // A shared MediaStreamTrack must never be disabled by a single viewer's gate.
                    rtpSenders = emptyList(),
                    mppServerConfig =
                        MppServerConfig(
                            network = network,
                            recipient = recipient,
                            secretKey = "liquid-auth-mesh-${viewer.sessionId}",
                        ),
                    serverConfig = config,
                    getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
                )
            viewer.creator = creator
            creator.rtcServer.onViewerHelloMessage = { helloJson ->
                onAdditionalViewer(viewer) { captureAdditionalViewerHello(viewer, helloJson) }
            }
            creator.rtcServer.onVoucherReceived = { voucherJson ->
                onAdditionalViewer(viewer) { captureAdditionalViewerHello(viewer, voucherJson) }
            }
            creator.rtcServer.onPaymentRequested = { request ->
                onAdditionalViewer(viewer) {
                    val hostSalt = EscrowSessionVaultHybridManagerClient.defaultSalt?.copyOf()
                    val sentSalt = request.salt?.let { runCatching { Base64.decode(it) }.getOrNull() }
                    if (hostSalt != null && sentSalt != null && hostSalt.contentEquals(sentSalt)) {
                        if (!viewer.advertisedSalt.contentEquals(hostSalt)) {
                            viewer.advertisedSalt = hostSalt
                            invalidateAdditionalViewerVaultDetails(viewer)
                        }
                        startAdditionalViewerVaultPolling(viewer)
                    }
                }
            }
            creator.rtcServer.onPaymentRejected = { reason ->
                onAdditionalViewer(viewer) {
                    Napier.w("Mesh payment rejected (${viewer.session.requestId}): $reason", tag = TAG)
                }
            }
            creator.rtcServer.onError = { error ->
                onAdditionalViewer(viewer) {
                    Napier.e("Mesh creator error (${viewer.session.requestId})", error, tag = TAG)
                }
            }
            creator.rtcServer.onSessionTerminated = {
                onAdditionalViewer(viewer) {
                    // Payment-channel lifetime must not control native media transport.
                    // Network disconnects and explicit host stops still remove this peer.
                    closeAdditionalViewerBilling(viewer)
                    Napier.d("Mesh payment session ended (${viewer.session.requestId}); transport retained", tag = TAG)
                }
            }
            creator.onChatMessageReceived = { message ->
                onAdditionalViewer(viewer) {
                    // Display chat, but do not add another viewer's messages to primary billing.
                    viewModel?.onChatMessageReceived(message)
                    broadcastChat(message, source = creator)
                }
            }
            if (!isCurrent(viewer)) return
            creator.start()
            sendAdditionalSessionInfo(viewer, recipient)
        } catch (error: Exception) {
            Napier.e("Failed to initialize mesh creator ${viewer.session.requestId}", error, tag = TAG)
            // Payment setup failure is not a transport admission failure.
        }
    }

    private fun captureAdditionalViewerHello(
        viewer: AdditionalViewer,
        message: String,
    ) {
        if (!isCurrent(viewer)) return
        runCatching {
            val parsed = parseLiquidAuthHostTransportMessage(message)
            val voucher = parsed.paymentVoucher
            if (voucher?.sessionId != null && voucher.sessionId != viewer.sessionId) return
            val address =
                (voucher?.viewerAddress ?: parsed.viewerHello?.viewerAddress ?: parsed.address)
                    ?.takeIf { it.isNotBlank() }
            val signerKey = voucher?.viewerPublicKey ?: parsed.viewerHello?.viewerPublicKey
            // All transports share the same identity lock; reject the whole inconsistent message.
            if (address != null && viewer.viewerAddress != null && viewer.viewerAddress != address) return
            if (signerKey != null &&
                (signerKey.isEmpty() || (viewer.signerKey != null && !viewer.signerKey.contentEquals(signerKey)))
            ) {
                return
            }
            if (viewer.viewerAddress == null) viewer.viewerAddress = address
            if (viewer.signerKey == null) viewer.signerKey = signerKey?.copyOf()
            (voucher?.channelId ?: parsed.viewerHello?.channelId)?.takeIf { it.size == 32 }?.let { channelId ->
                if (!viewer.vaultChannelHint.contentEquals(channelId)) {
                    viewer.vaultChannelHint = channelId.copyOf()
                    invalidateAdditionalViewerVaultDetails(viewer)
                }
            }
            if (voucher != null && !viewer.billingClosed) {
                viewer.pendingVouchers.addLast(voucher)
            }
            updateAdditionalViewerConfig(viewer)
        }.onFailure { Napier.w("Invalid mesh viewer handshake", it, tag = TAG) }
    }

    private fun invalidateAdditionalViewerVaultDetails(viewer: AdditionalViewer) {
        viewer.vaultDetailsJob?.cancel()
        viewer.vaultDetailsJob = null
        publishAdditionalViewerDetails(viewer) {
            it.copy(
                remainingBalanceMicroUsdc = null,
                lastSettledMicroUsdc = null,
                progressBalanceMicroUsdc = null,
                totalDepositMicroUsdc = null,
            )
        }
    }

    private fun updateAdditionalViewerConfig(viewer: AdditionalViewer) {
        // Identity is useful even before creator/payment configuration or a vault exists.
        publishAdditionalViewerDetails(viewer)
        startAdditionalViewerVaultPolling(viewer)
        val config = viewer.config ?: return
        viewer.config =
            config.copy(
                viewerAddress = viewer.viewerAddress,
                viewerAuthorizedSignerPublicKey = viewer.signerKey,
            )
        viewer.creator?.updateConfig(checkNotNull(viewer.config))
        ensureAdditionalViewerBilling(viewer)
        sendAdditionalSessionInfo(viewer, config.gating.payTo)
    }

    private fun sendAdditionalSessionInfo(
        viewer: AdditionalViewer,
        recipient: String,
    ) {
        if (!isCurrent(viewer)) return
        viewer.session.send(buildLiquidStreamInfoMessage(hostAddress = recipient, sessionId = viewer.sessionId))
        // Never broadcast the primary's cost envelope verbatim: it contains the primary session ID.
        viewer.session.send(
            buildLiquidStreamCostUpdateMessage(
                sessionId = viewer.sessionId,
                costMicroUsdc =
                    viewer.config
                        ?.gating
                        ?.amount
                        ?.toLongOrNull() ?: 0L,
            ),
        )
    }

    private fun createNotificationBuilder(): NotificationCompat.Builder {
        // Create notification channel for Android O+
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Liquid Auth Broadcast",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle("Liquid Auth Broadcast")
            .setContentText("Waiting for peer to connect...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val parsed = parseLiquidAuthHostTransportMessage(msg)
            Napier.e("[SESSION_VAULT_VIEWER_VOUCHER_SIG] voucherRef=${parsed.reference.orEmpty()}", tag = TAG)

            parsed.viewerHello?.let { hello ->
                val helloViewer = hello.viewerAddress
                val signerKey = hello.viewerPublicKey
                if (signerKey != null) {
                    if (helloViewer != null && helloViewer != activeViewerAddressForVault) {
                        setActiveViewerAddress(helloViewer)
                        Napier.e("[SESSION_VAULT_VIEWER_HELLO_ADDR] viewer=$helloViewer", tag = TAG)
                    }
                    activeViewerAuthorizedSignerKey = signerKey

                    Napier.e(
                        "[SESSION_VAULT_VIEWER_HELLO_KEY] viewer=$helloViewer keyLen=${signerKey.size} session=$activePaymentSessionId creatorReady=${liquidStreamCreator != null}",
                    )

                    // If the creator already exists, push the key immediately so
                    // PaywalledRTCServer.viewerKeyDeferred resolves without waiting.
                    updateCreatorViewerSignerConfig(signerKey)
                }
            }

            parsed.paymentVoucher?.let { voucher ->
                voucher.channelIdBase64?.let { Napier.e("channelId=$it", tag = TAG) }
                voucher.channelId?.let { EscrowSessionVaultHybridManagerClient.channelId = it }

                when (
                    val decision =
                        evaluateLiquidAuthVoucher(
                            voucher = voucher,
                            activeSessionId = activePaymentSessionId,
                            previousClaimedAmountMicroUsdc = activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc,
                            isPaidStreamingEnabled = isPaidStreamingEnabled,
                        )
                ) {
                    is LiquidAuthVoucherDecision.InvalidPayload -> {
                        Napier.e(
                            "[SESSION_VAULT_VIEWER_VOUCHER_SIG_SKIP] reason=invalid_payload session=${voucher.sessionId} claimedAmountMicroUsdc=${voucher.totalAmountClaimedMicroUsdc} viewer=${voucher.viewerAddress}",
                            tag = TAG,
                        )
                    }
                    is LiquidAuthVoucherDecision.SessionMismatch -> {
                        Napier.e(
                            "[SESSION_VAULT_VIEWER_VOUCHER_SIG_SKIP] reason=session_mismatch voucherSession=${voucher.sessionId} activeSession=$activePaymentSessionId",
                            tag = TAG,
                        )
                    }
                    is LiquidAuthVoucherDecision.Stale -> {
                        Napier.e(
                            "[SESSION_VAULT_VIEWER_VOUCHER_SIG_STALE_SKIP] session=${voucher.sessionId} claimedAmountMicroUsdc=${voucher.totalAmountClaimedMicroUsdc} previousClaimedAmountMicroUsdc=${activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc}",
                            tag = TAG,
                        )
                    }
                    is LiquidAuthVoucherDecision.Evaluated -> {
                        if (decision.shouldSettle) {
                            activeCreatorVoucherClaimSnapshot = decision.snapshot
                            updateCreatorViewerSignerConfig(decision.viewerPublicKey)
                            Napier.e(
                                "[SESSION_VAULT_VIEWER_VOUCHER_SIG] session=${decision.snapshot.sessionId} sigLen=${decision.snapshot.signatureBase64.length} claimedAmountMicroUsdc=${decision.snapshot.totalAmountClaimedMicroUsdc} viewer=${decision.snapshot.viewerAddress} signerKeyPresent=${decision.viewerPublicKey != null}",
                                tag = TAG,
                            )
                            startBlockConsumption(decision.snapshot.sessionId)
                            blockConsumptionManager.triggerSettlementFromViewerVoucher(
                                decision.snapshot.sessionId,
                                force = true,
                            )
                        } else {
                            Napier.d(
                                "[SESSION_VAULT_VIEWER_VOUCHER_IGNORE] reason=free_mode session=${decision.snapshot.sessionId}",
                                tag = TAG,
                            )
                            // Still update signer config if provided, but don't save voucher or settle
                            decision.viewerPublicKey?.let { updateCreatorViewerSignerConfig(it) }
                        }
                    }
                }
            }

            val candidate = parsed.address
            if (candidate != null && candidate != activeViewerAddressForVault) {
                setActiveViewerAddress(candidate)
                Napier.d("🔑 Captured viewer address from LiquidAuth message: $candidate", tag = TAG)
            }
        }
    }

    private fun updateCreatorViewerSignerConfig(signerKey: ByteArray?) {
        liquidStreamCreator?.updateConfig(
            ServerConfig(
                sessionId = activePaymentSessionId,
                gating =
                    GatingConfig(
                        mode = GatingMode.PARTIAL_TIME,
                        amount =
                            activePaymentAmount
                                ?: MppPayments.voucherSettleWindowMicroUsdc().toString(),
                        asset = "USDC",
                        network = activePaymentNetwork ?: MppNetworks.ALGORAND_TESTNET,
                        payTo = activePaymentRecipient.orEmpty(),
                        segmentDuration = 3,
                        leadTime = 0,
                    ),
                gracePeriod = 5,
                viewerAddress = activeViewerAddressForVault,
                viewerAuthorizedSignerPublicKey = signerKey,
                skipPaymentRequestWhenSessionFunded = true,
            ),
        )
    }

    actual fun stopListening() {
        listeningRequested = false
        permissionPending = false
        permissionLauncher?.unregister()
        permissionLauncher = null
        Napier.d("Stopping SignalService (activeRequestId=$activeRequestId)", tag = TAG)
        // Invalidate callbacks before terminating channels or unbinding (both can reenter).
        hostGeneration++
        additionalViewers.values.forEach { viewer ->
            viewer.connectionDetailsJob?.cancel()
            viewer.vaultDetailsJob?.cancel()
            closeAdditionalViewerBilling(viewer)
        }
        val creators = additionalViewers.values.mapNotNull { it.creator }
        additionalViewers.clear()
        connectedViewerIds.clear()
        invitations.clear()
        retiredInvitationIds.clear()
        creators.forEach { runCatching { it.terminate("stop_listening") } }
        stopConnectionTypePolling()
        stopBlockConsumption()
        runCatching { liquidStreamCreator?.terminate("stop_listening") }
        liquidStreamCreator = null
        activePaymentSessionId = null
        activePaymentRecipient = null
        activePaymentAmount = null
        activePaymentNetwork = null
        setActiveViewerAddress(null)
        activeCreatorVoucherClaimSnapshot = null
        activeViewerAuthorizedSignerKey = null
        paymentTemplate = null
        creatorAddress = null
        creatorNetwork = null
        viewModel?.clearMeshHosting()
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (_: IllegalArgumentException) {
                // Service not bound
            }
        }

        // Stop every peer when this manager owns the host. If it never started a host,
        // preserve a separate viewer session that may own the shared service instead.
        val viewerStillActive = AnswerScreenState.isVisible || ConnectionStatusState.isVisible
        if (hostStarted || !viewerStillActive) {
            signalService?.stop()
        } else {
            Napier.w(
                "⏸️ Viewer session still active (AnswerScreenState.isVisible=${AnswerScreenState.isVisible}, " +
                    "ConnectionStatusState.isVisible=${ConnectionStatusState.isVisible}). " +
                    "Skipping signalService.stop() to preserve viewer connection.",
                tag = TAG,
            )
        }

        signalService = null
        serviceConnection = null
        isBound = false
        activeRequestId = null
        primaryViewerId = null
        hostOrigin = null
        hostStarted = false
    }

    actual fun sendMessage(message: String) {
        val dataChannelState = platformServices.hostDataChannelState(signalService)
        val isOpen = dataChannelState == "OPEN"
        Napier.d(
            "📤 sendMessage called: dcState=$dataChannelState, isOpen=$isOpen, bytes=${message.length}, preview=${
                message.take(
                    120,
                )
            }",
            tag = TAG,
        )
        platformServices.sendHostMessage(signalService, message)
    }

    actual fun sendChatMessage(message: ChatMessage) {
        blockConsumptionManager.recordChatMessage(message)
        broadcastChat(message)
    }

    private fun broadcastChat(
        message: ChatMessage,
        source: LiquidStreamCreator? = null,
    ) {
        val recipients =
            listOfNotNull(liquidStreamCreator) +
                additionalViewers.values
                    .toList()
                    .filter { isCurrent(it) }
                    .mapNotNull { it.creator }
        relayHostChat(
            message = message,
            recipients = recipients,
            source = source,
            send = { creator, chat -> creator.sendChatMessage(chat) },
            onFailure = { Napier.w("Failed to relay chat", it, tag = TAG) },
        )
    }

    private fun sendCreatorSessionInfo(
        hostAddress: String,
        sessionId: String,
    ) {
        if (hostAddress.isBlank()) {
            Napier.w("sendCreatorSessionInfo: skipping — hostAddress is blank", tag = TAG)
            return
        }
        val json = buildLiquidStreamInfoMessage(hostAddress = hostAddress, sessionId = sessionId)
        Napier.d("[CREATOR_SESSION_INFO_SENT] host=$hostAddress session=$sessionId", tag = TAG)
        platformServices.sendHostMessage(signalService, json)
    }

    actual fun isConnected(): Boolean =
        signalService?.hostViewerSessions?.values?.any { it.dataChannel?.state()?.toString() == "OPEN" } == true

    // ── Native WebRTC media track rendering (creator/host) ──────────────────────

    /** Shared EGL context used to initialize a `SurfaceViewRenderer`. */
    fun getStreamEglBaseContext(): org.webrtc.EglBase.Context? = signalService?.eglBaseContext

    /** Local camera track for the creator self-preview. */
    fun getLocalVideoTrack(): org.webrtc.VideoTrack? = signalService?.localVideoTrack

    /** Toggle the creator camera between front and back. */
    fun switchCamera() {
        signalService?.switchCamera()
    }

    actual fun setAudioEnabled(enabled: Boolean) {
        signalService?.setAudioEnabled(enabled)
    }

    actual fun setVideoEnabled(enabled: Boolean) {
        signalService?.setVideoEnabled(enabled)
    }
}

/**
 * Android actual implementation of factory function.
 */
