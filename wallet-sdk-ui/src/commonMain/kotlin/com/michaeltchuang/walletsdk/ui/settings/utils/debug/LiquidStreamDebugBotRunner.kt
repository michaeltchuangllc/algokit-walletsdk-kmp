package com.michaeltchuang.walletsdk.ui.settings.utils.debug

import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.network.usecase.NodeStatusResponse
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.DebugAddressSelections
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRailMppChannelSaltUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.SessionVaultContext
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.parseLiquidAuthHostTransportMessage
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.ViewerVaultBillingSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val VOUCHER_INTERVAL_MS = 1_000L
private const val CHAT_INTERVAL_MS = 5_000L
private const val GIFT_INTERVAL_MS = 15_000L

private val DEBUG_CHAT_MESSAGES =
    listOf(
        "Hello everyone!",
        "Great stream today!",
        "Loving the content!",
        "Super clear explanation, thanks!",
        "Awesome stream, keep it up!",
        "Hey from the community!",
        "Glad to be tuned in!",
        "Quality looks great!",
    )

private val DEBUG_GIFT_MESSAGES =
    listOf(
        "Keep up the awesome work!",
        "Thanks for the great stream!",
        "Enjoy a coffee on me!",
        "Love the stream, here's some support!",
        "Great content, keep going!",
    )

data class LiquidStreamDebugConfiguration(
    val isPaid: Boolean = true,
    val costMicroUsdc: Long = LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC,
    val payoutBlocks: Int = 1,
) {
    init {
        require(costMicroUsdc >= 0) { "Stream cost must not be negative" }
        require(payoutBlocks > 0) { "Payout frequency must be positive" }
    }
}

internal fun DebugAddressSelections.uniqueDebugViewers(): List<String> =
    listOf(viewerAddress, viewerAddress2, viewerAddress3)
        .map(String::trim)
        .filter { it.isNotEmpty() && it != creatorAddress.trim() }
        .distinct()

internal val SessionVaultContext.mppNetwork: String
    get() =
        when (network) {
            AlgorandNetwork.MAINNET -> MppNetworks.ALGORAND_MAINNET
            AlgorandNetwork.TESTNET -> MppNetworks.ALGORAND_TESTNET
            AlgorandNetwork.FUTURENET -> MppNetworks.ALGORAND_FUTURENET
        }

@OptIn(ExperimentalEncodingApi::class)
class LiquidStreamDebugBotRunner(
    private val signerUseCase: MppWalletSignerUseCase,
    private val channelSaltUseCase: GetRailMppChannelSaltUseCase,
    private val voucherRepository: MppVoucherRepository,
    private val noteUseCase: GetMppVoucherNoteUseCase,
    private val httpClient: HttpClient,
    private val applicationScope: CoroutineScope,
) {
    private val runMutex = Mutex()

    internal suspend fun run(
        context: SessionVaultContext,
        creator: String,
        viewers: List<String>,
        configuration: StateFlow<LiquidStreamDebugConfiguration>,
        onDetails: (String, HostViewerDetails) -> Unit,
        onChat: (ChatMessage) -> Unit,
        onError: (String) -> Unit,
        onFunding: (Boolean) -> Unit = {},
        onBillingError: (String, String?) -> Unit = { viewer, error ->
            if (error != null) onError("$viewer: $error")
        },
        prepareViewers: suspend ((String) -> Unit) -> Unit = { ready -> viewers.forEach(ready) },
    ): Unit =
        withContext(Dispatchers.Main.immediate) {
            runMutex.withLock {
                val selected = viewers.map(String::trim).filter { it.isNotEmpty() && it != creator }.distinct()
                if (selected.isEmpty()) awaitCancellation()
                val creatorSigner =
                    withTimeout(30_000.milliseconds) {
                        requireNotNull(signerUseCase(creator)) { "Selected creator has no local signer" }
                    }
                require(creatorSigner.address == creator) { "Creator signer mismatch" }
                val salt = channelSaltUseCase()
                val signers = prepareDebugBotViewers(creator, selected, signerUseCase::invoke, onFunding)
                runSession(
                    context,
                    creator,
                    creatorSigner,
                    signers,
                    salt,
                    configuration,
                    onDetails,
                    onChat,
                    onError,
                    onBillingError,
                    prepareViewers,
                )
            }
        }

    private suspend fun runSession(
        context: SessionVaultContext,
        creator: String,
        creatorSigner: MppWalletSigner,
        viewers: List<MppWalletSigner>,
        salt: ByteArray,
        configuration: StateFlow<LiquidStreamDebugConfiguration>,
        onDetails: (String, HostViewerDetails) -> Unit,
        onChat: (ChatMessage) -> Unit,
        onError: (String) -> Unit,
        onBillingError: (String, String?) -> Unit,
        prepareViewers: suspend ((String) -> Unit) -> Unit,
    ): Unit =
        coroutineScope {
            // Zero bots is valid: a real QR viewer may be the only participant.
            if (viewers.isEmpty()) awaitCancellation()
            val round = MutableStateFlow<Long?>(null)
            val nodeUrl =
                when (context.network) {
                    AlgorandNetwork.MAINNET -> NODE_MAINNET_BASE_URL
                    AlgorandNetwork.TESTNET -> NODE_TESTNET_BASE_URL
                    AlgorandNetwork.FUTURENET -> NODE_FUTURENET_BASE_URL
                }
            val roundJob =
                launch {
                    while (isActive) {
                        val observed =
                            try {
                                withTimeout(15_000.milliseconds) {
                                    val response = httpClient.get("$nodeUrl/v2/status")
                                    check(response.status.isSuccess()) { "Round lookup failed: ${response.status}" }
                                    response.body<NodeStatusResponse>().lastRound
                                }
                            } catch (e: TimeoutCancellationException) {
                                // A child timeout must fail the run, not silently leave stale rounds charging.
                                throw IllegalStateException("Round lookup timed out; bots stopped", e)
                            }
                        require(observed >= 0) { "Invalid blockchain round" }
                        round.value = maxOf(round.value ?: observed, observed)
                        delay(1_000.milliseconds)
                    }
                }
            runDebugViewersWhenReady(
                viewers = viewers.map { it.address },
                prepareViewers = prepareViewers,
                onError = onError,
            ) { address ->
                val signer = viewers.first { it.address == address }
                val viewer = signer.address
                var billing: ViewerVaultBillingSession? = null
                try {
                    val channel =
                        HostViewerVaultReader.deriveChannelId(
                            viewer,
                            creator,
                            signer.authorizedSignerPublicKey,
                            context.mppNetwork,
                            salt,
                        )
                    val snapshot =
                        withTimeout(30_000.milliseconds) {
                            HostViewerVaultReader
                                .readChannel(
                                    channel,
                                    viewer,
                                    creator,
                                    signer.authorizedSignerPublicKey,
                                    context.mppNetwork,
                                ).getOrThrow()
                        }
                    require(snapshot.totalDepositMicroUsdc > 0) { "Selected viewer channel is not funded" }
                    onDetails(viewer, snapshot.asDetails(viewer))
                    val session =
                        ViewerVaultBillingSession(
                            scope = applicationScope,
                            sessionId = "live-debug:${Base64.encode(channel)}",
                            viewerAddress = viewer,
                            creatorAddress = creator,
                            network = context.mppNetwork,
                            signerPublicKey = signer.authorizedSignerPublicKey,
                            buildCreatorWalletSigner = { address -> creatorSigner.takeIf { address == creator } },
                            onSnapshot = { onDetails(viewer, it.asDetails(viewer)) },
                            onError = { onError("$viewer: ${it.message ?: "Billing failed"}") },
                            voucherRepository = voucherRepository,
                            getMppVoucherNoteUseCase = noteUseCase,
                            payoutFrequencyBlocks = configuration.value.payoutBlocks,
                            onSettlementError = { error ->
                                onBillingError(viewer, error?.let { it.message ?: "Settlement failed" })
                            },
                        )
                    billing = session
                    val persisted = session.restorePending(channel)
                    // Recovery may settle and delete a higher durable authorization. Re-read before
                    // seeding both endpoints; never treat an unconfirmed persisted amount as on-chain.
                    val recoveredSnapshot =
                        withTimeout(30_000.milliseconds) {
                            HostViewerVaultReader
                                .readChannel(
                                    channel,
                                    viewer,
                                    creator,
                                    signer.authorizedSignerPublicKey,
                                    context.mppNetwork,
                                ).getOrThrow()
                        }
                    val accounting = DebugBotAccounting(recoveredSnapshot, persisted)
                    onDetails(viewer, recoveredSnapshot.asDetails(viewer))
                    val receiver =
                        DebugBotHostReceiver(
                            session,
                            onChat,
                            channel,
                            accounting.onChainBaseline,
                        )
                    runProducer(
                        signer,
                        creator,
                        context.appId,
                        channel,
                        session.sessionId,
                        accounting,
                        round,
                        configuration,
                        receiveVoucher = receiver::acceptVoucher,
                        receiveChat = receiver::acceptChat,
                        observeBlock = { block, config ->
                            session.updatePayoutFrequencyBlocks(config.payoutBlocks).join()
                            session.onBlock(block, config.isPaid, config.costMicroUsdc).join()
                        },
                        refreshSnapshot = {
                            HostViewerVaultReader
                                .readChannel(
                                    channel,
                                    viewer,
                                    creator,
                                    signer.authorizedSignerPublicKey,
                                    context.mppNetwork,
                                ).getOrThrow()
                                .also { onDetails(viewer, it.asDetails(viewer)) }
                        },
                    )
                } catch (_: TimeoutCancellationException) {
                    onError("$viewer: Bot operation timed out; stopped")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError("$viewer: ${e.message ?: "Bot stopped"}")
                } finally {
                    // Cancellation stops signing first. Drain survives screen/ViewModel disposal.
                    withContext(NonCancellable) {
                        billing?.close()?.join()
                    }
                }
            }
            roundJob.cancel()
        }
}

internal suspend fun runDebugViewersWhenReady(
    viewers: List<String>,
    prepareViewers: suspend ((String) -> Unit) -> Unit,
    onError: (String) -> Unit,
    runViewer: suspend (String) -> Unit,
): Unit =
    coroutineScope {
        val readiness = viewers.distinct().associateWith { CompletableDeferred<Boolean>() }
        launch {
            try {
                prepareViewers { viewer -> readiness[viewer]?.complete(true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Bot funding failed")
            } finally {
                readiness.values.forEach { it.complete(false) }
            }
        }
        readiness
            .map { (viewer, ready) ->
                launch {
                    if (ready.await()) runViewer(viewer)
                }
            }.joinAll()
    }

internal suspend fun prepareDebugBotViewers(
    creator: String,
    viewers: List<String>,
    signerFor: suspend (String) -> MppWalletSigner?,
    onFunding: (Boolean) -> Unit,
): List<MppWalletSigner> {
    val selected = viewers.map(String::trim).filter { it.isNotEmpty() && it != creator }.distinct()
    if (selected.isEmpty()) return emptyList()
    try {
        // Validate local identities before starting any voucher producers.
        val signers =
            selected.map { viewer ->
                withTimeout(30_000.milliseconds) {
                    requireNotNull(signerFor(viewer)) { "Selected viewer has no local signer: $viewer" }.also {
                        require(
                            it.address == viewer && it.authorizedSignerPublicKey.isNotEmpty(),
                        ) { "Viewer signer unavailable or mismatched" }
                    }
                }
            }
        return signers
    } finally {
        onFunding(false)
    }
}

// Debug bots share the host's device and never negotiate a real WebRTC/ICE connection, so the
// generic UNKNOWN default would otherwise show "Detecting..." forever. Report LOCAL instead.
internal fun HostViewerVaultReader.Snapshot.asDetails(viewer: String) =
    HostViewerDetails(
        viewerAddress = viewer,
        connectionType = IceConnectionType.LOCAL,
        remainingBalanceMicroUsdc = remainingBalanceMicroUsdc,
        lastSettledMicroUsdc = lastSettledMicroUsdc,
        progressBalanceMicroUsdc = progressBalanceMicroUsdc,
        totalDepositMicroUsdc = totalDepositMicroUsdc,
        progressCapacityMicroUsdc = totalDepositMicroUsdc,
    )

internal class DebugBotAccounting(
    snapshot: HostViewerVaultReader.Snapshot,
    persisted: Long,
) {
    private val startRound = snapshot.startRound
    var deposit = snapshot.totalDepositMicroUsdc
        private set
    val onChainBaseline: Long =
        maxOf(
            snapshot.lastSettledMicroUsdc,
            deposit - snapshot.progressBalanceMicroUsdc,
        )
    var cumulative: Long = maxOf(onChainBaseline, persisted)
        private set
    private var lastRound: Long? = null

    init {
        require(deposit >= 0 && persisted >= 0 && cumulative in 0..deposit) {
            "Stored authorization ($persisted microUSDC) exceeds the channel deposit ($deposit microUSDC). " +
                "Billing stopped to protect pending payment; channel history must be reconciled."
        }
    }

    /** Reconcile confirmed top-ups without resetting already authorized block/gift charges. */
    fun refresh(snapshot: HostViewerVaultReader.Snapshot) {
        check(startRound == null || snapshot.startRound == null || snapshot.startRound == startRound) {
            "Channel was reopened while billing; reopen live debug before continuing"
        }
        // A delayed pre-top-up read must not lower the available deposit.
        if (snapshot.totalDepositMicroUsdc < deposit) return
        deposit = snapshot.totalDepositMicroUsdc
        cumulative =
            maxOf(
                cumulative,
                snapshot.lastSettledMicroUsdc,
                deposit - snapshot.progressBalanceMicroUsdc,
            )
        require(cumulative in 0..deposit) { "Authorization exceeds the refreshed channel deposit" }
    }

    fun observeRound(
        round: Long,
        config: LiquidStreamDebugConfiguration,
    ) {
        require(round >= 0)
        val previous = lastRound
        if (previous != null && round <= previous) return
        lastRound = round
        if (previous == null || !config.isPaid || config.costMicroUsdc == 0L) return
        val advanced = round - previous
        val remaining = deposit - cumulative
        // Clamp before multiplication/addition so even Long.MAX_VALUE costs/rounds are safe.
        cumulative +=
            if (advanced > remaining / config.costMicroUsdc) {
                remaining
            } else {
                advanced * config.costMicroUsdc
            }
    }

    fun giftCandidate(): Long? = if (deposit - cumulative >= GIFT_MICRO_USDC) cumulative + GIFT_MICRO_USDC else null

    fun commitGift(candidate: Long) {
        require(candidate == giftCandidate()) { "Gift amount changed before acceptance" }
        cumulative = candidate
    }

    companion object {
        const val GIFT_MICRO_USDC = 1_000L
    }
}

internal fun debugSettlementVoucher(
    appId: Long,
    channel: ByteArray,
    amount: Long,
    creator: String,
): ByteArray {
    require(appId > 0 && channel.size == 32 && amount >= 0)
    return MppPayments.buildLogicSigSettlementVoucher(channel, amount, creator, appId = appId)
}

internal fun parseDebugBotChat(
    wire: String,
    viewer: String,
): ChatMessage {
    val message = Json.parseToJsonElement(wire).jsonObject
    require(message["type"]?.jsonPrimitive?.content == DCMessageType.CHAT_MESSAGE.value)
    val chat = Json.decodeFromJsonElement(ChatMessage.serializer(), requireNotNull(message["payload"]))
    require(chat.sender == viewer) { "Chat viewer mismatch" }
    return chat
}

internal fun serializeDebugBotChat(chat: ChatMessage): String =
    buildJsonObject {
        put("type", DCMessageType.CHAT_MESSAGE.value)
        put("payload", Json.encodeToJsonElement(ChatMessage.serializer(), chat))
    }.toString()

@OptIn(ExperimentalEncodingApi::class)
internal class DebugBotHostReceiver(
    private val billing: ViewerVaultBillingSession,
    private val onChat: (ChatMessage) -> Unit,
    channelId: ByteArray? = null,
    private val onChainBaseline: Long? = null,
) {
    private var channel = channelId?.copyOf()
    private var acceptedAmount: Long? = onChainBaseline
    private var giftCredit = false

    init {
        require(channel == null || channel?.size == 32)
        require(onChainBaseline == null || (onChainBaseline >= 0 && channel != null))
    }

    suspend fun acceptVoucher(
        wire: String,
        gift: Boolean,
    ): Boolean {
        val message = parseLiquidAuthHostTransportMessage(wire).paymentVoucher ?: return false
        val amount = message.totalAmountClaimedMicroUsdc ?: return false
        val suppliedChannel = message.channelId ?: return false
        val suppliedKey = message.viewerPublicKey ?: return false
        // A heartbeat bypasses payment authorization, not the transport's identity boundary.
        if (message.sessionId != billing.sessionId ||
            message.viewerAddress != billing.viewerAddress ||
            !suppliedKey.contentEquals(billing.signerPublicKey) ||
            suppliedChannel.size != 32 ||
            channel?.let { !it.contentEquals(suppliedChannel) } == true ||
            amount < 0
        ) {
            return false
        }
        try {
            if (Base64.decode(message.signatureBase64 ?: return false).isEmpty()) return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        val previous = acceptedAmount
        if (previous != null && amount < previous) return false
        if (gift && (previous == null || amount < previous || amount - previous != DebugBotAccounting.GIFT_MICRO_USDC)) {
            return false
        }
        // Only the exact known on-chain baseline is a no-op. In particular, a higher
        // persisted amount still needs billing acceptance, and a heartbeat never buys a gift.
        if (!gift && amount == onChainBaseline) {
            giftCredit = false
            return true
        }
        if (!billing.acceptVoucher(message)) return false
        channel = suppliedChannel.copyOf()
        acceptedAmount = amount
        giftCredit = gift
        return true
    }

    suspend fun acceptChat(wire: String) {
        val chat = parseDebugBotChat(wire, billing.viewerAddress)
        if (chat.amount != null) {
            check(giftCredit && chat.amount == "0.001" && chat.asset == "USDC") { "Gift has no accepted voucher" }
            giftCredit = false
        }
        currentCoroutineContext().ensureActive()
        billing.recordChatMessage(chat).join()
        currentCoroutineContext().ensureActive()
        onChat(chat)
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal suspend fun runProducer(
    signer: MppWalletSigner,
    creator: String,
    appId: Long,
    channel: ByteArray,
    sessionId: String,
    accounting: DebugBotAccounting,
    rounds: StateFlow<Long?>,
    configuration: StateFlow<LiquidStreamDebugConfiguration>,
    receiveVoucher: suspend (String, Boolean) -> Boolean,
    receiveChat: suspend (String) -> Unit,
    observeBlock: suspend (Long, LiquidStreamDebugConfiguration) -> Unit,
    timeSource: TimeSource = TimeSource.Monotonic,
    signingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    refreshSnapshot: suspend () -> HostViewerVaultReader.Snapshot? = { null },
) {
    var cachedAmount: Long? = null
    var cachedWire: String? = null

    suspend fun sendVoucher(
        amount: Long,
        gift: Boolean = false,
    ): Boolean {
        if (cachedAmount != amount) {
            val signature =
                withContext(signingDispatcher) {
                    signer.signMessage(debugSettlementVoucher(appId, channel, amount, creator))
                }
            currentCoroutineContext().ensureActive()
            cachedWire =
                buildJsonObject {
                    put("type", DCMessageType.SEGMENT_VOUCHER.value)
                    put("id", sessionId)
                    put("viewer", signer.address)
                    put("viewerPublicKey", Base64.encode(signer.authorizedSignerPublicKey))
                    put("channelId", Base64.encode(channel))
                    put("signature", Base64.encode(signature))
                    put("totalAmountClaimedMicroUsdc", amount)
                }.toString()
            cachedAmount = amount
        }
        return receiveVoucher(requireNotNull(cachedWire), gift)
    }
    var textMark = timeSource.markNow()
    var giftMark = timeSource.markNow()
    var observedRound: Long? = null
    var observedConfiguration: LiquidStreamDebugConfiguration? = null
    while (currentCoroutineContext().isActive) {
        val tick = timeSource.markNow()
        withTimeout(60_000.milliseconds) {
            val config = configuration.value
            rounds.value?.let {
                if (observedRound != it) {
                    refreshSnapshot()?.let(accounting::refresh)
                }
                accounting.observeRound(it, config)
                if (observedRound != it || observedConfiguration != config) {
                    observeBlock(it, config)
                    observedRound = it
                    observedConfiguration = config
                }
            }
            // Equal cumulative vouchers reuse their signature and the host's duplicate fast path.
            check(sendVoucher(accounting.cumulative)) { "Host rejected voucher; bot stopped" }
            if (textMark.elapsedNow().inWholeMilliseconds >= CHAT_INTERVAL_MS) {
                receiveChat(
                    serializeDebugBotChat(
                        ChatMessage(
                            sender = signer.address,
                            text = DEBUG_CHAT_MESSAGES.random(),
                            timestamp = Clock.System.now().toEpochMilliseconds(),
                        ),
                    ),
                )
                textMark = timeSource.markNow()
            }
            if (giftMark.elapsedNow().inWholeMilliseconds >= GIFT_INTERVAL_MS) {
                accounting.giftCandidate()?.let { candidate ->
                    // Never publish a gift merely because signing or transport succeeded.
                    check(sendVoucher(candidate, gift = true)) { "Host rejected gift; bot stopped" }
                    accounting.commitGift(candidate)
                    receiveChat(
                        serializeDebugBotChat(
                            ChatMessage(
                                sender = signer.address,
                                text = DEBUG_GIFT_MESSAGES.random(),
                                timestamp = Clock.System.now().toEpochMilliseconds(),
                                amount = "0.001",
                                asset = "USDC",
                            ),
                        ),
                    )
                }
                giftMark = timeSource.markNow()
            }
        }
        // Target one second, not one second PLUS validation latency. Never queue catch-up work.
        delay((VOUCHER_INTERVAL_MS - tick.elapsedNow().inWholeMilliseconds).coerceAtLeast(1).milliseconds)
    }
}
