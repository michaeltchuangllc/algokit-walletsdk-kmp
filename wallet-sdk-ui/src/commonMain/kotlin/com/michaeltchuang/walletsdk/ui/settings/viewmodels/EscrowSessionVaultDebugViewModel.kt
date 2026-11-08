package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.DebugAddressSelections
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.DebugAddressSelectionsUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetAlgorandSessionSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient.SessionAuthorizationStatus
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.PaymentError
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SignAlgorandSeedVaultTransaction
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.DebugAddressHolder
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EscrowSessionVaultDebugViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
    private val getLocalAccounts: GetLocalAccounts,
    private val getSessionVaultContextUseCase: GetSessionVaultContextUseCase,
    private val debugAddressSelectionsUseCase: DebugAddressSelectionsUseCase,
    private val getAlgorandSessionSigner: GetAlgorandSessionSigner,
    private val getCurrentNetwork: GetCurrentNetworkUseCase,
) : ViewModel(),
    StateViewModel<EscrowSessionVaultDebugViewModel.ViewState> by stateDelegate,
    EventViewModel<EscrowSessionVaultDebugViewModel.ViewEvent> by eventDelegate {
    companion object Companion {
        private const val TAG = "EscrowSessionVaultDebugViewModel"
        private const val MICRO_USDC_MULTIPLIER = 1_000_000L

        // Fresh-session settlement needs the settlement LogicSig (103,030 µALGO), the
        // padding LogicSig (100,000 µALGO), and the payer-side funding/payment fees.
        private const val CREATOR_FUNDING_TARGET_MICRO_ALGO = 211_030L
    }

    data class PendingVoucher(
        val cumulativeAmountMicroUsdc: Long,
        val signature: ByteArray,
        val authorizedSignerPublicKey: ByteArray,
    )

    private var statusRefreshJob: Job? = null

    init {
        stateDelegate.setDefaultState(ViewState.Content())
        loadAccountAddresses()
        viewModelScope.launch {
            getCurrentNetwork().distinctUntilChanged().collect {
                statusRefreshJob?.cancel()
                updateContent { it.copy(authorization = null, pendingVoucher = null, remainingBalance = null, resultMessage = null) }
                refreshSignerStatus()
            }
        }
    }

    fun onViewerAddressChanged(address: String) {
        if (contentState().isLoading) return
        statusRefreshJob?.cancel()
        updateContent {
            it.copy(
                viewerAddress = address,
                pendingVoucher = null,
                remainingBalance = null,
                creatorPayeeSigner = "",
                authorization = null,
                resultMessage = null,
            )
        }
        DebugAddressHolder.setViewerAddress(0, address)
        viewModelScope.launch { saveDebugAddressSelections() }
        refreshSignerStatus()
    }

    fun onViewerAddress2Changed(address: String) {
        if (contentState().isLoading) return
        updateContent { it.copy(viewerAddress2 = address) }
        DebugAddressHolder.setViewerAddress(1, address)
        viewModelScope.launch { saveDebugAddressSelections() }
    }

    fun onViewerAddress3Changed(address: String) {
        if (contentState().isLoading) return
        updateContent { it.copy(viewerAddress3 = address) }
        DebugAddressHolder.setViewerAddress(2, address)
        viewModelScope.launch { saveDebugAddressSelections() }
    }

    fun onCreatorAddressChanged(address: String) {
        if (contentState().isLoading) return
        statusRefreshJob?.cancel()
        updateContent {
            it.copy(
                creatorAddress = address,
                pendingVoucher = null,
                remainingBalance = null,
                creatorPayeeSigner = "",
                authorization = null,
                resultMessage = null,
            )
        }
        DebugAddressHolder.creatorAddress = address
        viewModelScope.launch { saveDebugAddressSelections() }
        refreshSignerStatus()
    }

    fun onDepositAmountChanged(amount: String) {
        if (contentState().isLoading) return
        updateContent { it.copy(depositAmountUsdc = amount) }
    }

    fun onRefreshSignerStatus() = refreshSignerStatus(reportResult = true)

    fun refreshSignerStatus() = refreshSignerStatus(reportResult = false)

    private fun refreshSignerStatus(reportResult: Boolean) {
        if (contentState().isLoading) return
        val selected = contentState()
        if (reportResult && !validateViewerAndCreator(selected)) return
        statusRefreshJob?.cancel()
        updateContent { it.startingStatusRefresh(reportResult) }
        statusRefreshJob =
            viewModelScope.launch {
                try {
                    if (!selected.canRunVaultActions) return@launch
                    val network = getSessionVaultContextUseCase().network
                    val viewerSigner = getHotSigner(selected.viewerAddress)
                    val creatorSigner = getHotSigner(selected.creatorAddress)
                    currentCoroutineContext().ensureActive()
                    check(getSessionVaultContextUseCase().network == network) { "Network changed. Refresh signer status." }
                    updateContent {
                        it.copy(viewerSessionSigner = viewerSigner.address, creatorPayeeSigner = creatorSigner.address)
                    }
                    EscrowSessionVaultHybridManagerClient.configureForNetwork(network)
                    configureDebugSessionContext(selected, viewerSigner.authorizedSignerPublicKey)
                    val channelId = checkNotNull(EscrowSessionVaultHybridManagerClient.channelId).copyOf()
                    val authorization =
                        EscrowSessionVaultHybridManagerClient
                            .getSessionAuthorizationStatus(channelId, viewerSigner.authorizedSignerPublicKey, selected.creatorAddress)
                            .getOrThrow()
                    currentCoroutineContext().ensureActive()
                    check(getSessionVaultContextUseCase().network == network) { "Network changed. Refresh signer status." }
                    check(
                        contentState().viewerAddress == selected.viewerAddress && contentState().creatorAddress == selected.creatorAddress,
                    ) {
                        "Accounts changed. Refresh signer status."
                    }
                    updateContent {
                        it.withRefreshedStatus(authorization, reportResult)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(PaymentError.from(e), "SIGNER_STATUS_ERR", e)
                } finally {
                    if (currentCoroutineContext().isActive) {
                        updateContent { it.copy(isRefreshingStatus = false) }
                    }
                }
            }
    }

    fun authorizeSigner(sign: SignAlgorandSeedVaultTransaction) {
        runSignerAction("Authorize viewer voucher signer", false, sign) { signer, channelId ->
            updateContent { it.copy(pendingVoucher = null) }
            EscrowSessionVaultHybridManagerClient
                .setAuthorizedPayerSigner(
                    signer,
                    channelId,
                    signer.authorizedSignerPublicKey,
                ).getOrThrow()
        }
    }

    fun revokeSigner(sign: SignAlgorandSeedVaultTransaction) {
        runSignerAction("Revoke settlement LogicSig", false, sign) { signer, channelId ->
            val txId = EscrowSessionVaultHybridManagerClient.revokeSettlementLogicSig(signer, channelId).getOrThrow()
            updateContent { it.copy(pendingVoucher = null) }
            txId
        }
    }

    /**
     * Registers the creator hot wallet as payee signer. [accountLevel] true covers every channel where the
     * creator is the payee (one approval); false covers only the current channel and overrides the account level.
     * The box MBR is paid in the same group, so no separate funding step is needed.
     */
    fun authorizePayeeSigner(
        sign: SignAlgorandSeedVaultTransaction,
        accountLevel: Boolean,
    ) {
        val scope = if (accountLevel) "account-level" else "channel-level"
        runSignerAction("Authorize creator payee signer ($scope)", true, sign) { signer, channelId ->
            val hotSigner = getHotSigner(contentState().creatorAddress)
            updateContent { it.copy(creatorPayeeSigner = hotSigner.address) }
            EscrowSessionVaultHybridManagerClient
                .setAuthorizedPayeeSigner(signer, hotSigner.address, if (accountLevel) null else channelId)
                .getOrThrow()
        }
    }

    fun revokePayeeSigner(
        sign: SignAlgorandSeedVaultTransaction,
        accountLevel: Boolean,
    ) {
        val scope = if (accountLevel) "account-level" else "channel-level"
        runSignerAction("Revoke creator payee signer ($scope)", true, sign) { signer, channelId ->
            EscrowSessionVaultHybridManagerClient
                .revokeAuthorizedPayeeSigner(signer, if (accountLevel) null else channelId)
                .getOrThrow()
        }
    }

    fun fundCreatorSigner(sign: SignAlgorandSeedVaultTransaction) {
        val creatorAddress = contentState().creatorAddress.trim()
        if (creatorAddress.isBlank()) {
            sendStatus("❌ Creator address is required before funding the hot wallet.")
            return
        }
        viewModelScope.launch {
            val hotSigner = runCatching { getHotSigner(creatorAddress) }.getOrNull()
            if (hotSigner == null || hotSigner.address == creatorAddress) {
                sendStatus(
                    "❌ Creator funding is only valid for a Seed Vault session wallet. " +
                        "Use a separate account to fund the creator hot wallet instead of funding itself.",
                )
                return@launch
            }
            runSignerAction("Fund creator hot wallet", true, sign) { signer, _ ->
                val fundedSigner = getHotSigner(contentState().creatorAddress)
                updateContent { it.copy(creatorPayeeSigner = fundedSigner.address) }
                EscrowSessionVaultHybridManagerClient
                    .ensureAccountMinimumBalance(
                        signer,
                        fundedSigner.address,
                        CREATOR_FUNDING_TARGET_MICRO_ALGO,
                    ).getOrThrow()
            }
        }
    }

    fun closeWithPayeeSigner() {
        runSignerAction("Close & refund via creator payee signer", true, null, hotWallet = true) { signer, channelId ->
            // Effective signer for this channel: channel-level override, else the creator's account-level default.
            val registered =
                EscrowSessionVaultHybridManagerClient
                    .getAuthorizedPayeeSigner(contentState().creatorAddress, channelId)
                    .getOrThrow()
            check(registered == signer.address) { "Authorize the creator hot wallet as payee signer first." }
            val txId = EscrowSessionVaultHybridManagerClient.close(signer, channelId).getOrThrow()
            EscrowSessionVaultHybridManagerClient.awaitTransactionConfirmed(txId).getOrThrow()
            updateContent { it.copy(pendingVoucher = null, remainingBalance = 0, creatorPayeeSigner = "") }
            txId
        }
    }

    private fun runSignerAction(
        label: String,
        creatorSigns: Boolean,
        sign: SignAlgorandSeedVaultTransaction?,
        hotWallet: Boolean = false,
        action: suspend (MppWalletSigner, ByteArray) -> String?,
    ) {
        val content = contentState()
        if (!validateViewerAndCreator(content)) return
        setLoading(true)
        viewModelScope.launch {
            try {
                sendStatus("$label...")
                val network = getSessionVaultContextUseCase().network
                check(refreshDebugSessionContext()) { "Session context is unavailable." }
                val channelId = checkNotNull(EscrowSessionVaultHybridManagerClient.channelId) { "Channel is unavailable." }
                val address = if (creatorSigns) content.creatorAddress else content.viewerAddress
                val signer =
                    if (hotWallet) {
                        getHotSigner(address)
                    } else {
                        checkNotNull(getDebugSigner(address, sign)) { "Account signer is unavailable." }
                    }
                val txId =
                    withContext(Dispatchers.Default) {
                        val guardedSigner =
                            object : MppWalletSigner by signer {
                                private suspend fun checkContext() {
                                    currentCoroutineContext().ensureActive()
                                    check(getSessionVaultContextUseCase().network == network) { "Network changed. Retry the action." }
                                    check(EscrowSessionVaultHybridManagerClient.channelId?.contentEquals(channelId) == true) {
                                        "Session changed. Retry the action."
                                    }
                                }

                                override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray {
                                    checkContext()
                                    return signer.signTransactionBytes(txnMsgpack).also { checkContext() }
                                }

                                override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> {
                                    checkContext()
                                    return signer.signTransactionsBytes(txnsMsgpack).also { checkContext() }
                                }
                            }
                        check(getSessionVaultContextUseCase().network == network) { "Network changed. Retry the action." }
                        val result = action(guardedSigner, channelId)
                        if (result != null) EscrowSessionVaultHybridManagerClient.awaitTransactionConfirmed(result).getOrThrow()
                        result
                    }
                sendStatus("$label complete${txId?.let { "\nTxId: $it" } ?: ": already funded"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(PaymentError.from(e), "SIGNER_ACTION_ERR", e)
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun getHotSigner(address: String): MppWalletSigner {
        val account = getLocalAccounts().firstOrNull { it.address == address }
        return if (account is LocalAccount.SeedVault && account.chainId == Chain.ALGORAND.id) {
            checkNotNull(getAlgorandSessionSigner(address)) { "Seed Vault index-1 session signer is unavailable." }
        } else {
            checkNotNull(mppWalletSignerUseCase(address)) { "Local account signer is unavailable." }
        }
    }

    private suspend fun getDebugSigner(
        address: String,
        sign: SignAlgorandSeedVaultTransaction? = null,
    ): MppWalletSigner? {
        val account = getLocalAccounts().firstOrNull { it.address == address }
        if (account !is LocalAccount.SeedVault) return mppWalletSignerUseCase(address)
        require(account.chainId == Chain.ALGORAND.id) { "An Algorand Seed Vault account is required." }
        val linked = getHotSigner(address)
        val network = getSessionVaultContextUseCase().network
        return object : MppWalletSigner {
            override val address = account.address
            override val authorizedSignerPublicKey = linked.authorizedSignerPublicKey
            override val signerType = MppWalletSignerType.FALCON_NATIVE

            override suspend fun signMessage(message: ByteArray): ByteArray = linked.signMessage(message)

            override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray {
                currentCoroutineContext().ensureActive()
                check(getSessionVaultContextUseCase().network == network) { "Network changed. Retry the action." }
                val result = checkNotNull(sign) { "Seed Vault approval is required for this action." }(address, txnMsgpack)
                currentCoroutineContext().ensureActive()
                check(getSessionVaultContextUseCase().network == network) { "Network changed while approving the transaction." }
                check(result.isNotEmpty()) { "Seed Vault returned an empty signed transaction." }
                return result
            }

            // A group is approved once in Seed Vault, not once per transaction
            override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> {
                currentCoroutineContext().ensureActive()
                check(getSessionVaultContextUseCase().network == network) { "Network changed. Retry the action." }
                val results = checkNotNull(sign) { "Seed Vault approval is required for this action." }.signAll(address, txnsMsgpack)
                currentCoroutineContext().ensureActive()
                check(getSessionVaultContextUseCase().network == network) { "Network changed while approving the transactions." }
                check(results.size == txnsMsgpack.size && results.none { it.isEmpty() }) {
                    "Seed Vault returned an empty signed transaction."
                }
                return results
            }
        }
    }

    private fun loadAccountAddresses() {
        viewModelScope.launch {
            runCatching {
                val accounts = getLocalAccounts()
                updateContent {
                    it.copy(
                        seedVaultAddresses =
                            accounts
                                .filterIsInstance<LocalAccount.SeedVault>()
                                .map { account ->
                                    account.address
                                }.toSet(),
                    )
                }
                val addresses =
                    accounts
                        .filter {
                            it is LocalAccount.HdKey ||
                                it is LocalAccount.Algo25 ||
                                it is LocalAccount.Falcon24 ||
                                it is LocalAccount.Falcon25 ||
                                (
                                    it is LocalAccount.SeedVault &&
                                        it.chainId == Chain.ALGORAND.id
                                )
                        }.map { it.address }
                addresses to debugAddressSelectionsUseCase.get()
            }.onSuccess { (addresses, savedSelections) ->
                updateContent { current ->
                    val used = mutableSetOf<String>()
                    val viewer =
                        savedSelections.viewerAddress
                            .takeIf { it in addresses && it !in used }
                            ?: addresses.firstOrNull { it !in used }.orEmpty()
                    if (viewer.isNotBlank()) used.add(viewer)

                    val creator =
                        savedSelections.creatorAddress
                            .takeIf { it in addresses && it !in used }
                            ?: addresses.firstOrNull { it !in used }.orEmpty()
                    if (creator.isNotBlank()) used.add(creator)

                    val viewer2 =
                        savedSelections.viewerAddress2
                            .takeIf { it in addresses && it !in used }
                            .orEmpty()
                    if (viewer2.isNotBlank()) used.add(viewer2)

                    val viewer3 =
                        savedSelections.viewerAddress3
                            .takeIf { it in addresses && it !in used }
                            .orEmpty()
                    if (viewer3.isNotBlank()) used.add(viewer3)

                    DebugAddressHolder.setViewerAddress(0, viewer)
                    DebugAddressHolder.setViewerAddress(1, viewer2)
                    DebugAddressHolder.setViewerAddress(2, viewer3)
                    DebugAddressHolder.creatorAddress = creator
                    current.copy(
                        accountAddresses = addresses,
                        viewerAddress = viewer,
                        viewerAddress2 = viewer2,
                        viewerAddress3 = viewer3,
                        creatorAddress = creator,
                    )
                }
                saveDebugAddressSelections()
                refreshSignerStatus()
                if (addresses.size < 2) {
                    sendStatus("❌ At least two signable local Algorand accounts are required.")
                }
            }.onFailure { err ->
                Napier.e("[LOAD_ACCOUNTS_ERR]", err, tag = TAG)
                sendStatus("❌ Failed to load local accounts: ${err.message.orEmpty()}")
            }
        }
    }

    fun addAmountToSessionVault(sign: SignAlgorandSeedVaultTransaction? = null) {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return

        setLoading(true)
        viewModelScope.launch {
            try {
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultHybridManagerClient.configureForNetwork(vaultContext.network)
                sendStatus("Depositing $amountUsdc USDC from viewer to ${vaultContext.networkLabel} Session Vault…")
                Napier.d(
                    "[ADD_TO_VAULT_CONTEXT] viewer=$viewer creator=$creator appId=${vaultContext.appId} usdcAssetId=${vaultContext.usdcAssetId}",
                    tag = TAG,
                )
                val signer = getDebugSigner(viewer, sign)
                if (signer != null) {
                    if (!refreshDebugSessionContext(signer)) return@launch

                    val result =
                        withContext(Dispatchers.Default) {
                            if (content.authorization?.channelExists == true) {
                                MppPayments.topUpSessionVault(signer, depositMicroUsdc)
                            } else {
                                MppPayments.openSessionAndDeposit(
                                    signer = signer,
                                    viewerAddress = viewer,
                                    depositAmountMicroUsdc = depositMicroUsdc,
                                )
                            }
                        }
                    val txId =
                        result.getOrElse { err ->
                            showError(PaymentError.from(err), "ADD_TO_VAULT_ERR", err)
                            return@launch
                        }
                    awaitConfirmed(txId)
                    sendStatus("✅ Deposited $amountUsdc USDC to Session Vault!\nTxId: $txId")
                    Napier.d("[ADD_TO_VAULT_OK] txId=$txId", tag = TAG)
                } else {
                    showError(PaymentError.SignerNotFound(viewer), "ADD_TO_VAULT_NO_SIGNER")
                }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "ADD_TO_VAULT_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * The viewer's whole setup in one atomic group and one Seed Vault approval: USDC deposit, open the channel,
     * authorize the session signing key and register the settlement LogicSig. The single-step actions stay for
     * debugging each step.
     */
    fun openAuthorizeAndRegisterSession(sign: SignAlgorandSeedVaultTransaction? = null) {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return
        if (content.authorization?.channelExists == true) {
            sendStatus("❌ The channel already exists. Use Top up and the single steps instead.")
            return
        }

        setLoading(true)
        viewModelScope.launch {
            try {
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultHybridManagerClient.configureForNetwork(vaultContext.network)
                sendStatus("Opening, authorizing and registering the session in one approval…")
                Napier.d(
                    "[OPEN_AUTHORIZE_REGISTER_CONTEXT] viewer=$viewer creator=$creator appId=${vaultContext.appId}",
                    tag = TAG,
                )
                val signer = getDebugSigner(viewer, sign)
                if (signer == null) {
                    showError(PaymentError.SignerNotFound(viewer), "OPEN_AUTHORIZE_REGISTER_NO_SIGNER")
                    return@launch
                }
                if (!refreshDebugSessionContext(signer)) return@launch
                val result =
                    withContext(Dispatchers.Default) {
                        MppPayments.openAndAuthorizeSession(
                            signer = signer,
                            viewerAddress = viewer,
                            depositAmountMicroUsdc = depositMicroUsdc,
                        )
                    }
                val txId =
                    result.getOrElse { err ->
                        showError(PaymentError.from(err), "OPEN_AUTHORIZE_REGISTER_ERR", err)
                        return@launch
                    }
                awaitConfirmed(txId)
                sendStatus("✅ Session opened, signer authorized and LogicSig registered\nTxId: $txId")
                Napier.d("[OPEN_AUTHORIZE_REGISTER_OK] txId=$txId", tag = TAG)
                refreshSignerStatus()
            } catch (e: Exception) {
                showError(PaymentError.from(e), "OPEN_AUTHORIZE_REGISTER_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun fetchSessionVaultRemainingBalance() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Fetching Session Vault balance…")
            try {
                val viewerSigner =
                    getDebugSigner(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "FETCH_BALANCE_NO_VIEWER_SIGNER")
                        return@launch
                    }
                refreshDebugSessionContext(viewerSigner)
                val remaining =
                    withContext(Dispatchers.Default) {
                        //  val vaultContext = getSessionVaultContextUseCase()
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewer,
                        )
                    }
                updateContent { it.copy(remainingBalance = remaining) }
                val usdc = remaining / MICRO_USDC_MULTIPLIER.toDouble()
                sendStatus("✅ Remaining balance: $usdc USDC\n($remaining microUSDC)")
                Napier.d("[FETCH_BALANCE_OK] remaining=$remaining", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.from(e), "FETCH_BALANCE_ERR", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /** Signs a higher cumulative voucher locally; no on-chain transaction is sent. */
    fun updateVoucher() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val incrementMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                val viewerSigner =
                    getDebugSigner(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "LOCAL_VOUCHER_NO_VIEWER_SIGNER")
                        return@launch
                    }
                if (!refreshDebugSessionContext(viewerSigner)) return@launch
                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault()
                    } ?: run {
                        showError(PaymentError.SessionNotFound, "LOCAL_VOUCHER_NO_SNAPSHOT")
                        return@launch
                    }
                val previousAmount = contentState().pendingVoucher?.cumulativeAmountMicroUsdc ?: snapshot.lastSettledMicroUsdc
                val cumulativeAmount = previousAmount + incrementMicroUsdc
                if (cumulativeAmount > snapshot.totalDepositMicroUsdc) {
                    sendStatus("❌ ${PaymentError.VoucherExceedsDeposit.userMessage}")
                    return@launch
                }
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "LOCAL_VOUCHER_NO_CHANNEL_ID")
                        return@launch
                    }
                val signature =
                    viewerSigner.signMessage(
                        MppPayments.buildLogicSigSettlementVoucher(
                            channelId = channelId,
                            cumulativeAmountMicroUsdc = cumulativeAmount,
                            payeeAddress = creator,
                        ),
                    )
                updateContent {
                    it.copy(
                        pendingVoucher =
                            PendingVoucher(
                                cumulativeAmountMicroUsdc = cumulativeAmount,
                                signature = signature,
                                authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                            ),
                    )
                }
                sendStatus(
                    "✅ Local voucher updated to ${cumulativeAmount / MICRO_USDC_MULTIPLIER.toDouble()} USDC\nNo on-chain transaction sent.",
                )
            } catch (e: Exception) {
                showError(PaymentError.from(e), "LOCAL_VOUCHER_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Compiles and registers the viewer's channel-specific settlement LogicSig on-chain, using
     * the viewer's own ephemeral session key. Must run before [settleAmount] will succeed.
     */
    fun registerSettlementLogicSig(sign: SignAlgorandSeedVaultTransaction? = null) {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                val viewerSigner =
                    getDebugSigner(viewer, sign) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "REGISTER_LOGIC_SIG_NO_VIEWER_SIGNER")
                        return@launch
                    }
                if (!refreshDebugSessionContext(viewerSigner)) return@launch
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "REGISTER_LOGIC_SIG_NO_CHANNEL_ID")
                        return@launch
                    }
                sendStatus("Registering settlement LogicSig on-chain…")
                val result =
                    withContext(Dispatchers.Default) {
                        MppPayments.registerSettlementLogicSig(
                            signer = viewerSigner,
                            payeeAddress = creator,
                            channelId = channelId,
                        )
                    }
                val txId =
                    result.getOrElse { err ->
                        showError(PaymentError.from(err), "REGISTER_LOGIC_SIG_ERR", err)
                        return@launch
                    }
                awaitConfirmed(txId)
                sendStatus("✅ Settlement LogicSig registered\n\nTxId:\n$txId")
                Napier.d("[REGISTER_LOGIC_SIG_OK] txId=$txId", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.from(e), "REGISTER_LOGIC_SIG_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /** Submits the latest locally signed voucher through the channel LogicSig. */
    fun settleAmount() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                val viewerSigner =
                    getDebugSigner(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "SETTLE_NO_VIEWER_SIGNER")
                        return@launch
                    }
                if (!refreshDebugSessionContext(viewerSigner)) return@launch

                val pendingVoucher =
                    contentState().pendingVoucher ?: run {
                        sendStatus("❌ Update Voucher first to create an off-chain signed voucher.")
                        return@launch
                    }
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "SETTLE_NO_CHANNEL_ID")
                        return@launch
                    }
                sendStatus("Settling signed voucher to creator…")
                val creatorSigner = getHotSigner(creator)
                val settleResult =
                    withContext(Dispatchers.Default) {
                        MppPayments.settleFromLogicSig(
                            funderSigner = creatorSigner,
                            cumulativeAmountMicroUsdc = pendingVoucher.cumulativeAmountMicroUsdc,
                            voucherSignature = pendingVoucher.signature,
                            authorizedSignerPublicKey = pendingVoucher.authorizedSignerPublicKey,
                            payeeAddress = creator,
                            channelId = channelId,
                        )
                    }
                val txId =
                    settleResult.getOrElse { err ->
                        showError(PaymentError.from(err), "LSIG_SETTLE_ERR", err)
                        return@launch
                    }
                awaitConfirmed(txId)
                updateContent { it.copy(pendingVoucher = null) }
                sendStatus("✅ Settlement successful\n\nTxId:\n$txId")
                Napier.d(
                    "[LSIG_SETTLE_OK] txId=$txId cumulativeAmount=${pendingVoucher.cumulativeAmountMicroUsdc}",
                    tag = TAG,
                )
            } catch (e: Exception) {
                showError(PaymentError.from(e), "LSIG_SETTLE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun closeSessionVault(sign: SignAlgorandSeedVaultTransaction? = null) {
        if (!validateViewerAndCreator(contentState())) return
        val creator = contentState().creatorAddress.trim()

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Closing session vault…")
            try {
                val creatorSigner =
                    getDebugSigner(creator, sign) ?: run {
                        showError(PaymentError.SignerNotFound(creator), "CLOSE_NO_CREATOR_SIGNER")
                        return@launch
                    }

                if (!refreshDebugSessionContext()) return@launch
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultHybridManagerClient.close(
                            signer = creatorSigner,
                            channelId = channelId,
                        )
                    }

                val txId =
                    result.getOrElse { err ->
                        showError(PaymentError.from(err), "CLOSE_ERR", err)
                        return@launch
                    }
                awaitConfirmed(txId)
                updateContent { it.copy(pendingVoucher = null, remainingBalance = 0, creatorPayeeSigner = "") }
                sendStatus("✅ Session vault closed\n\nTxId:\n$txId")
                Napier.d("[CLOSE_OK] txId=$txId", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.from(e), "CLOSE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun requestCloseSessionVault(sign: SignAlgorandSeedVaultTransaction? = null) {
        if (!validateViewerAndCreator(contentState())) return
        val viewer = contentState().viewerAddress.trim()

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Requesting close of Session Vault…")
            try {
                val viewerSigner =
                    getDebugSigner(viewer, sign) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "REQUEST_CLOSE_NO_VIEWER_SIGNER",
                        )
                        return@launch
                    }

                if (!refreshDebugSessionContext(viewerSigner)) return@launch
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultHybridManagerClient.requestClose(
                            signer = viewerSigner,
                            channelId = channelId,
                        )
                    }

                val txId =
                    result.getOrElse { err ->
                        showError(PaymentError.from(err), "REQUEST_CLOSE_ERR", err)
                        return@launch
                    }
                awaitConfirmed(txId)
                sendStatus("✅ Requested for close of Session Vault\n\nTxId:\n$txId")
                Napier.d("[REQUEST_CLOSE_OK] txId=$txId", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.from(e), "REQUEST_CLOSE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun requestWithdraw(sign: SignAlgorandSeedVaultTransaction? = null) {
        if (!validateViewerAndCreator(contentState())) return
        val viewer = contentState().viewerAddress.trim()

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Requesting withdraw from Session Vault…")
            try {
                val viewerSigner =
                    getDebugSigner(viewer, sign) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "REQUEST_WITHDRAW_NO_VIEWER_SIGNER",
                        )
                        return@launch
                    }

                if (!refreshDebugSessionContext(viewerSigner)) return@launch
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "REQUEST_WITHDRAW_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultHybridManagerClient.withdraw(
                            signer = viewerSigner,
                            channelId = channelId,
                        )
                    }

                val txId =
                    result.getOrElse { err ->
                        val parsed = PaymentError.from(err)
                        if (parsed is PaymentError.BroadcastFailed || parsed is PaymentError.Unknown) {
                            sendStatus(
                                "❌ Withdraw was rejected by the contract.\n\n" +
                                    "Withdraw is the viewer's forced-close path. Make sure you:\n" +
                                    "1. Tapped 'Request Close' first (the viewer must be the payer).\n" +
                                    "2. Waited for the ~15-minute close grace period to elapse.\n\n" +
                                    "Then try 'Request Withdraw' again.",
                            )
                            Napier.e(
                                "[REQUEST_WITHDRAW_ERR] ${parsed::class.simpleName}",
                                err,
                                tag = TAG,
                            )
                            return@launch
                        }
                        showError(parsed, "REQUEST_WITHDRAW_ERR", err)
                        return@launch
                    }
                awaitConfirmed(txId)
                sendStatus("✅ Requested withdraw from Session Vault\n\nTxId:\n$txId")
                Napier.d("[REQUEST_WITHDRAW_OK] txId=$txId", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.from(e), "REQUEST_WITHDRAW_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun saveDebugAddressSelections() {
        debugAddressSelectionsUseCase.save(
            DebugAddressSelections(
                creatorAddress = DebugAddressHolder.creatorAddress,
                viewerAddress = DebugAddressHolder.getViewerAddress(0),
                viewerAddress2 = DebugAddressHolder.getViewerAddress(1),
                viewerAddress3 = DebugAddressHolder.getViewerAddress(2),
            ),
        )
    }

    private suspend fun refreshDebugSessionContext(signer: MppWalletSigner? = null): Boolean {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        if (viewer.isBlank() || viewer == content.creatorAddress.trim()) return false
        val viewerSigner = signer ?: getDebugSigner(viewer) ?: return false
        EscrowSessionVaultHybridManagerClient.configureForNetwork(getSessionVaultContextUseCase().network)
        configureDebugSessionContext(content, viewerSigner.authorizedSignerPublicKey)

        return true
    }

    private fun configureDebugSessionContext(
        content: ViewState.Content,
        authorizedSignerPublicKey: ByteArray,
    ) {
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        if (viewer.isBlank() || creator.isBlank() || viewer == creator) return

        val previousChannelId = EscrowSessionVaultHybridManagerClient.channelId
        EscrowSessionVaultHybridManagerClient.hostAddress = creator
        EscrowSessionVaultHybridManagerClient.salt = EscrowSessionVaultHybridManagerClient.defaultSalt
        EscrowSessionVaultHybridManagerClient.initializeChannelId(
            payerAddress = viewer,
            payeeAddress = creator,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
        )
        if (previousChannelId?.contentEquals(EscrowSessionVaultHybridManagerClient.channelId) != true) {
            updateContent { it.copy(pendingVoucher = null, remainingBalance = null) }
        }
        Napier.d(
            "[DEBUG_SESSION_CONTEXT_REFRESHED] viewer=$viewer creator=$creator " +
                "channelIdLength=${EscrowSessionVaultHybridManagerClient.channelId?.size} " +
                "saltLength=${EscrowSessionVaultHybridManagerClient.salt?.size}",
            tag = TAG,
        )
    }

    private fun validateViewerAndCreator(content: ViewState.Content): Boolean {
        if (content.isLoading || content.isRefreshingStatus) return false
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val errorMessage =
            when {
                content.accountAddresses.size < 2 -> "❌ At least two signable local Algorand accounts are required."
                viewer.isBlank() || creator.isBlank() -> "❌ Viewer and Creator addresses are required."
                viewer == creator -> "❌ Viewer and Creator addresses must be different accounts."
                else -> null
            }
        if (errorMessage != null) {
            sendStatus(errorMessage)
        }
        return errorMessage == null
    }

    private fun showError(
        error: PaymentError,
        logTag: String,
        cause: Throwable? = null,
    ) {
        sendStatus("❌ ${error.userMessage}")
        Napier.e(
            "[$logTag] ${error::class.simpleName}",
            cause ?: Throwable(error.userMessage),
            tag = TAG,
        )
    }

    private fun contentState(): ViewState.Content = state.value as ViewState.Content

    private suspend fun awaitConfirmed(txId: String) {
        withContext(Dispatchers.Default) {
            EscrowSessionVaultHybridManagerClient.awaitTransactionConfirmed(txId).getOrThrow()
        }
    }

    private fun updateContent(block: (ViewState.Content) -> ViewState.Content) {
        stateDelegate.updateState { current -> block(current as ViewState.Content) }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) statusRefreshJob?.cancel()
        updateContent { it.copy(isLoading = isLoading, isRefreshingStatus = false) }
        if (!isLoading) refreshSignerStatus()
    }

    private fun sendStatus(message: String) {
        updateContent { it.copy(resultMessage = ResultMessage.Text(message)) }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowStatusMessage(message))
    }

    sealed interface ResultMessage {
        data class Text(
            val value: String,
        ) : ResultMessage

        data object Refreshing : ResultMessage

        data object Refreshed : ResultMessage

        data object NoChannel : ResultMessage
    }

    sealed interface ViewState {
        data class Content(
            val accountAddresses: List<String> = emptyList(),
            val viewerAddress: String = "",
            val viewerAddress2: String = "",
            val viewerAddress3: String = "",
            val creatorAddress: String = "",
            val depositAmountUsdc: String = "0.1",
            val remainingBalance: Long? = null,
            val pendingVoucher: PendingVoucher? = null,
            val isLoading: Boolean = false,
            val creatorPayeeSigner: String = "",
            val viewerSessionSigner: String = "",
            val seedVaultAddresses: Set<String> = emptySet(),
            val authorization: SessionAuthorizationStatus? = null,
            val isRefreshingStatus: Boolean = false,
            val resultMessage: ResultMessage? = null,
        ) : ViewState {
            internal fun startingStatusRefresh(reportResult: Boolean): Content =
                copy(
                    authorization = null,
                    viewerSessionSigner = "",
                    creatorPayeeSigner = "",
                    remainingBalance = null,
                    isRefreshingStatus = true,
                    resultMessage = if (reportResult) ResultMessage.Refreshing else resultMessage,
                )

            internal fun withRefreshedStatus(
                status: SessionAuthorizationStatus,
                reportResult: Boolean,
            ): Content =
                copy(
                    authorization = status,
                    remainingBalance = status.remainingBalance,
                    pendingVoucher = pendingVoucher.takeIf { status.viewerSignerAuthorized },
                    resultMessage =
                        if (reportResult) {
                            if (status.channelExists) ResultMessage.Refreshed else ResultMessage.NoChannel
                        } else {
                            resultMessage
                        },
                )

            val hasChannel: Boolean get() = authorization?.channelExists == true
            val showAuthorizeViewer: Boolean get() = hasChannel && authorization?.viewerSignerAuthorized == false
            val showRegisterLogicSig: Boolean get() = hasChannel && authorization?.settlementRegistered == false
            val showRevokeViewer: Boolean get() = hasChannel && authorization?.hasSettlementLogicSig == true
            val creatorSignerAuthorized: Boolean
                get() = hasChannel && creatorPayeeSigner.isNotBlank() && authorization?.authorizedPayeeSigner == creatorPayeeSigner
            val showAuthorizeCreator: Boolean get() = hasChannel && !creatorSignerAuthorized
            val showRevokeCreator: Boolean get() = hasChannel && authorization?.authorizedPayeeSigner != null
            val canSettle: Boolean
                get() =
                    hasChannel &&
                        authorization?.viewerSignerAuthorized == true &&
                        authorization.settlementRegistered &&
                        pendingVoucher != null

            val canRunVaultActions: Boolean
                get() =
                    creatorAddress.isNotBlank() &&
                        viewerAddress.isNotBlank() &&
                        viewerAddress != creatorAddress
        }
    }

    sealed interface ViewEvent {
        data class ShowStatusMessage(
            val message: String,
        ) : ViewEvent
    }
}
