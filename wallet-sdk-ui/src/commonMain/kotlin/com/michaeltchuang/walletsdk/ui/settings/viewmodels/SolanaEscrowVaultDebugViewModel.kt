package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaEscrowChannel
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaEscrowSessionVaultManager
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaSettlementVoucher
import com.michaeltchuang.walletsdk.core.solana.domain.EnsureSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSessionSignerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSettlementFeePayerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaClusters
import com.michaeltchuang.walletsdk.core.solana.domain.SubmitSolanaSettlementVoucherUseCase
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionSigner
import com.michaeltchuang.walletsdk.core.solana.transaction.asSolanaTransactionSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SolanaEscrowVaultDebugViewModel(
    private val manager: SolanaEscrowSessionVaultManager,
    private val getLocalAccounts: GetLocalAccounts,
    private val ensureSessionKey: EnsureSolanaSessionKeyUseCase,
    private val getSessionSigner: GetSolanaSessionSignerUseCase,
    private val getFeePayer: GetSolanaSettlementFeePayerUseCase,
    private val submitVoucher: SubmitSolanaSettlementVoucherUseCase,
    private val getCurrentNetwork: GetCurrentNetworkUseCase,
) : ViewModel() {
    data class State(
        val accounts: List<String> = emptyList(),
        val payer: String = "",
        val payee: String = "",
        val cluster: String = "",
        val channels: List<SolanaEscrowChannel> = emptyList(),
        val channel: SolanaEscrowChannel? = null,
        val payerSessionKey: String = "",
        val payeeFeePayer: String = "",
        /** SOL balance (lamports) of [payeeFeePayer]; null until read. */
        val payeeFeePayerLamports: Long? = null,
        val amount: String = "0.1",
        val pendingVoucher: SolanaSettlementVoucher? = null,
        val isLoading: Boolean = false,
        val status: String = "Loading Solana network…",
    ) {
        val canRun: Boolean
            get() =
                cluster == "devnet" &&
                    payer.isNotBlank() &&
                    payee.isNotBlank() &&
                    payer in accounts &&
                    payee in accounts &&
                    payer != payee &&
                    !isLoading

        // The core channel's equality is identity-only. Include its changing on-chain fields
        // here so StateFlow doesn't suppress an updated balance or authorization.
        override fun equals(other: Any?): Boolean =
            other is State &&
                accounts == other.accounts &&
                payer == other.payer &&
                payee == other.payee &&
                cluster == other.cluster &&
                payerSessionKey == other.payerSessionKey &&
                payeeFeePayer == other.payeeFeePayer &&
                payeeFeePayerLamports == other.payeeFeePayerLamports &&
                amount == other.amount &&
                pendingVoucher == other.pendingVoucher &&
                isLoading == other.isLoading &&
                status == other.status &&
                sameChannel(channel, other.channel) &&
                channels.size == other.channels.size &&
                channels.indices.all { sameChannel(channels[it], other.channels[it]) }

        override fun hashCode(): Int = listOf(accounts, payer, payee, cluster, channel, pendingVoucher, status).hashCode()
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    val canRun: Boolean get() = state.value.canRun

    private var generation = 0L
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            try {
                getCurrentNetwork()
                    .map(SolanaClusters::clusterFor)
                    .distinctUntilChanged()
                    .collect { cluster ->
                        invalidate()
                        mutableState.update {
                            State(
                                accounts = it.accounts,
                                payer = it.payer,
                                payee = it.payee,
                                amount = it.amount,
                                cluster = cluster,
                                status = if (cluster == "devnet") "Loading devnet…" else MAINNET_UNSUPPORTED,
                            )
                        }
                        refresh()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                invalidate()
                mutableState.update {
                    State(accounts = it.accounts, amount = it.amount, status = "Network unavailable: ${errorMessage(e)}")
                }
            }
        }
    }

    fun selectPayer(address: String) = selectAccount(address, isPayer = true)

    fun selectPayee(address: String) = selectAccount(address, isPayer = false)

    private fun selectAccount(
        address: String,
        isPayer: Boolean,
    ) {
        val current = state.value
        if (current.isLoading) return
        if (address !in current.accounts) {
            mutableState.update { it.copy(status = "Select a local Seed Vault account.") }
            return
        }
        if (address == (if (isPayer) current.payer else current.payee)) return
        invalidate()
        mutableState.value =
            current.copy(
                payer = if (isPayer) address else current.payer,
                payee = if (isPayer) current.payee else address,
                channels = emptyList(),
                channel = null,
                payerSessionKey = "",
                payeeFeePayer = "",
                payeeFeePayerLamports = null,
                pendingVoucher = null,
            )
        refresh()
    }

    fun selectChannel(address: String) {
        val current = state.value
        if (current.isLoading) return
        val selected = current.channels.firstOrNull { it.address == address }
        if (selected == null || !matchesRoles(selected, current)) {
            mutableState.update { it.copy(status = "Select a recovered channel for the current payer and payee.") }
            return
        }
        if (current.channel?.address == address) return
        invalidate()
        mutableState.value = current.copy(channel = selected, pendingVoucher = null)
        refresh()
    }

    fun setAmount(amount: String) {
        if (state.value.isLoading) return
        if (amount.length > MAX_AMOUNT_LENGTH) {
            mutableState.update { it.copy(status = "USDC amount is too long.") }
            return
        }
        // Editing an increment must not alter the already-signed pending voucher.
        mutableState.update { it.copy(amount = amount) }
    }

    fun refresh() {
        runOperation("Refreshing…", requireAccounts = false) { token, _ ->
            refreshContext(token, loadAccounts = true)
        }
    }

    fun deposit(sign: suspend (String, ByteArray) -> ByteArray) {
        runOperation("Depositing…") { token, initial ->
            val amount = parseSolanaDebugUsdcAmount(initial.amount)
            // Recover existing channels before deriving a new one, including channels whose
            // authority belongs to an older linked key. Top-up never rotates that authority.
            refreshContext(token)
            val current = state.value
            val payer = ownerSigner(token, current.cluster, current.payer, sign)
            val selected = current.channel
            check(initial.channel == null || selected?.address == initial.channel.address) {
                "The selected channel closed or changed. Refresh and explicitly open a new channel instead of topping up."
            }
            val address: String
            val signature: String
            if (selected != null) {
                validateChannel(selected, current)
                require(amount <= Long.MAX_VALUE - selected.totalDeposit) { "Total deposit would overflow." }
                checkNetwork(token, current.cluster)
                signature = manager.topUp(current.cluster, payer, selected.address, amount).getOrThrow()
                address = selected.address
            } else {
                check(current.channels.isEmpty()) { "Select an existing channel before topping up." }
                val salt = SolanaEscrowSessionVaultManager.channelSalt(DEBUG_DOMAIN.encodeToByteArray(), current.payerSessionKey)
                address = manager.deriveChannelAddress(current.cluster, current.payer, current.payee, salt)
                val recovered = manager.getChannel(current.cluster, address)
                checkCurrent(token)
                checkNetwork(token, current.cluster)
                if (recovered != null) {
                    validateChannel(recovered, current)
                    require(amount <= Long.MAX_VALUE - recovered.totalDeposit) { "Total deposit would overflow." }
                    signature = manager.topUp(current.cluster, payer, address, amount).getOrThrow()
                } else {
                    signature =
                        manager
                            .open(
                                current.cluster,
                                payer,
                                current.payee,
                                amount,
                                current.payerSessionKey,
                                salt,
                            ).getOrThrow()
                            .signature
                }
            }
            afterWrite(token, "Deposit confirmed: $signature", preferredChannel = address)
        }
    }

    fun updateVoucher() {
        runOperation("Signing voucher…") { token, initial ->
            val increment = parseSolanaDebugUsdcAmount(initial.amount)
            refreshContext(token)
            val current = state.value
            val channel = requireSelected(current)
            val previous = maxOf(channel.lastSettled, current.pendingVoucher?.cumulativeAmount ?: 0L)
            check(previous < channel.totalDeposit) { "No remaining deposit for a higher voucher. Top up first." }
            // Check before adding to avoid overflow and preserve the exact requested increment.
            require(increment <= channel.totalDeposit - previous) {
                "Voucher increment exceeds the remaining deposit. Reduce the amount or top up first."
            }
            val amount = previous + increment
            buildVoucher(token, current, channel, amount)
        }
    }

    fun refreshVoucher() {
        runOperation("Refreshing voucher blockhash…") { token, initial ->
            val pending = initial.pendingVoucher ?: error("Update Voucher first.")
            refreshContext(token)
            val current = state.value
            check(current.pendingVoucher === pending) { "Voucher context or authority changed. Update Voucher first." }
            buildVoucher(token, current, requireSelected(current), pending.cumulativeAmount, pending.ensurePayeeTokenAccount)
        }
    }

    fun settle() {
        runOperation("Settling signed voucher…", settlement = true) { token, initial ->
            val pending = initial.pendingVoucher ?: error("Update Voucher first.")
            refreshContext(token)
            val current = state.value
            val channel = requireSelected(current)
            check(current.pendingVoucher === pending) { "Voucher context or authority changed. Update Voucher first." }
            validateVoucher(pending, current, channel)
            checkNetwork(token, current.cluster)
            // Submit exactly the displayed voucher; never rebuild or increment here.
            val signature = submitVoucher(current.payee, pending).getOrThrow()
            checkCurrent(token)
            mutableState.update { it.copy(pendingVoucher = null) }
            afterWrite(token, "Settlement confirmed: $signature")
        }
    }

    fun authorizeSigner(sign: suspend (String, ByteArray) -> ByteArray) {
        ownerAction("Authorize signer", sign, clearsPending = true) { current, channel, signer ->
            manager.setAuthorizedSigner(current.cluster, signer, channel.address, current.payerSessionKey).getOrThrow()
        }
    }

    fun revokeSigner(sign: suspend (String, ByteArray) -> ByteArray) {
        ownerAction("Revoke signer", sign, clearsPending = true) { current, channel, signer ->
            manager.revokeAuthorizedSigner(current.cluster, signer, channel.address).getOrThrow()
        }
    }

    /**
     * Sends [FEE_PAYER_FUNDING_LAMPORTS] SOL to the creator's fee-paying linked key (the voucher
     * `feePayer`), signed by the payee (creator) Seed Vault account — the creator always pays its own
     * settle fees. Settling is never paid by the payer's voucher signer, so this is what unblocks Settle.
     */
    fun fundFeePayer(sign: suspend (String, ByteArray) -> ByteArray) {
        val label = "Fund creator fee payer"
        runOperation("$label…") { token, _ ->
            refreshContext(token)
            val current = state.value
            requireSelected(current)
            val feePayer = current.payeeFeePayer
            check(feePayer.isNotBlank()) { "Creator fee-payer key is not available yet. Refresh first." }
            val source = current.payee
            val sourceLamports = manager.getSolBalance(current.cluster, source)
            checkCurrent(token)
            check(sourceLamports >= FEE_PAYER_FUNDING_LAMPORTS + FUNDING_FEE_BUFFER_LAMPORTS) {
                "${source.take(6)}… has ${formatSolanaDebugSol(sourceLamports)} SOL; needs at least " +
                    "${formatSolanaDebugSol(FEE_PAYER_FUNDING_LAMPORTS + FUNDING_FEE_BUFFER_LAMPORTS)} SOL. Airdrop devnet SOL first."
            }
            checkNetwork(token, current.cluster)
            val signature =
                manager
                    .fundFeePayer(
                        current.cluster,
                        ownerSigner(token, current.cluster, source, sign),
                        feePayer,
                        FEE_PAYER_FUNDING_LAMPORTS,
                    ).getOrThrow()
            afterWrite(token, "$label confirmed (${formatSolanaDebugSol(FEE_PAYER_FUNDING_LAMPORTS)} SOL): $signature")
        }
    }

    fun close(sign: suspend (String, ByteArray) -> ByteArray) {
        ownerAction("Close", sign, payeeSigns = true, closesChannel = true) { current, channel, signer ->
            manager.close(current.cluster, signer, channel.address).getOrThrow()
        }
    }

    fun requestClose(sign: suspend (String, ByteArray) -> ByteArray) {
        ownerAction("Request close", sign) { current, channel, signer ->
            manager.requestClose(current.cluster, signer, channel.address).getOrThrow()
        }
    }

    fun withdraw(sign: suspend (String, ByteArray) -> ByteArray) {
        ownerAction("Withdraw", sign, closesChannel = true) { current, channel, signer ->
            manager.withdraw(current.cluster, signer, channel.address).getOrThrow()
        }
    }

    private fun ownerAction(
        label: String,
        sign: suspend (String, ByteArray) -> ByteArray,
        payeeSigns: Boolean = false,
        clearsPending: Boolean = false,
        closesChannel: Boolean = false,
        action: suspend (State, SolanaEscrowChannel, SolanaTransactionSigner) -> String,
    ) {
        runOperation("$label…") { token, _ ->
            refreshContext(token)
            val current = state.value
            val channel = requireSelected(current)
            val owner = if (payeeSigns) current.payee else current.payer
            checkNetwork(token, current.cluster)
            val signature = action(current, channel, ownerSigner(token, current.cluster, owner, sign))
            checkCurrent(token)
            if (clearsPending || closesChannel) {
                mutableState.update { it.copy(pendingVoucher = null) }
            }
            if (closesChannel) {
                mutableState.update {
                    it.copy(channel = null, channels = it.channels.filterNot { candidate -> candidate.address == channel.address })
                }
            }
            afterWrite(token, "$label confirmed: $signature")
        }
    }

    private suspend fun buildVoucher(
        token: Long,
        current: State,
        channel: SolanaEscrowChannel,
        amount: Long,
        ensurePayeeTokenAccount: Boolean = true,
    ) {
        require(amount > channel.lastSettled && amount <= channel.totalDeposit) {
            "Voucher amount must exceed last settled and must not exceed the total deposit."
        }
        val linked = getSessionSigner(current.payer)?.asSolanaTransactionSigner() ?: error("No session key linked to ${current.payer}.")
        checkCurrent(token)
        check(!channel.isSignerRevoked && channel.authorizedSigner == linked.address) {
            "The linked payer session key is not authorized. Use Authorize Signer explicitly."
        }
        check(linked.address == current.payerSessionKey) { "Linked session key changed. Refresh and try again." }
        checkNetwork(token, current.cluster)
        val voucher =
            manager.createSettlementVoucher(
                network = current.cluster,
                channel = channel.address,
                cumulativeAmount = amount,
                authorizedSigner =
                    object : SolanaTransactionSigner {
                        override val address: String = linked.address

                        override suspend fun signMessage(message: ByteArray): ByteArray {
                            checkNetwork(token, current.cluster)
                            val signature = linked.signMessage(message)
                            checkNetwork(token, current.cluster)
                            return signature
                        }
                    },
                feePayer = current.payeeFeePayer,
                ensurePayeeTokenAccount = ensurePayeeTokenAccount,
            )
        checkCurrent(token)
        validateVoucher(voucher, current, channel)
        mutableState.update {
            it.copy(
                pendingVoucher = voucher,
                status = "Voucher signed for $amount raw USDC units. Submit promptly; if expired, Refresh Voucher keeps the same amount.",
            )
        }
    }

    private suspend fun refreshContext(
        token: Long,
        loadAccounts: Boolean = false,
        preferredChannel: String? = null,
    ) {
        checkCurrent(token)
        if (loadAccounts) {
            val addresses = getLocalAccounts().filterIsInstance<LocalAccount.SeedVault>().map { it.address }.distinct()
            checkCurrent(token)
            mutableState.update {
                val payer = it.payer.takeIf { address -> address in addresses } ?: addresses.firstOrNull().orEmpty()
                val payee =
                    it.payee.takeIf { address -> address in addresses }
                        ?: addresses.firstOrNull { address -> address != payer }.orEmpty()
                if (payer != it.payer || payee != it.payee) {
                    it.copy(
                        accounts = addresses,
                        payer = payer,
                        payee = payee,
                        channels = emptyList(),
                        channel = null,
                        payerSessionKey = "",
                        payeeFeePayer = "",
                        payeeFeePayerLamports = null,
                        pendingVoucher = null,
                    )
                } else {
                    it.copy(accounts = addresses)
                }
            }
        }
        val current = state.value
        val unavailable = unavailableReason(current)
        if (unavailable != null) {
            mutableState.update { it.copy(status = unavailable) }
            return
        }
        checkNetwork(token, current.cluster)
        val payerKey = ensureSessionKey(current.payer).signerAddress
        checkCurrent(token)
        val feePayer = getFeePayer(current.payee)
        checkCurrent(token)
        val feePayerLamports = manager.getSolBalance(current.cluster, feePayer)
        checkCurrent(token)
        mutableState.update {
            it.copy(
                payerSessionKey = payerKey,
                payeeFeePayer = feePayer,
                payeeFeePayerLamports = feePayerLamports,
                pendingVoucher =
                    it.pendingVoucher?.takeIf { voucher ->
                        voucher.authorizedSigner == payerKey && voucher.feePayer == feePayer
                    },
            )
        }
        val selectedAddress = preferredChannel ?: current.channel?.address
        val selected = selectedAddress?.let { manager.getChannel(current.cluster, it) }
        checkCurrent(token)
        if (selected != null) validateChannel(selected, current)
        // Invalidate promptly, even if the subsequent recovery read fails.
        if (selectedAddress != null) publishChannel(selected)
        val recovered = manager.findChannels(current.cluster, current.payer, current.payee)
        checkCurrent(token)
        recovered.forEach { validateChannel(it, current) }
        val channel = if (selectedAddress != null) selected else recovered.firstOrNull()
        val channels = if (selected != null) listOf(selected) + recovered.filterNot { it.address == selected.address } else recovered
        mutableState.update { it.copy(channels = channels) }
        publishChannel(channel)
        mutableState.update {
            it.copy(
                status =
                    when {
                        channel == null -> "No selected open channel. Deposit to open one, or select a recovered channel."
                        channel.isSignerRevoked -> "Channel signer revoked. Use Authorize Signer to enable vouchers."
                        channel.authorizedSigner != payerKey ->
                            "Recovered channel uses another authority. Top-up preserves it; use Authorize Signer to change it."
                        else -> "Channel refreshed. Fund the payee fee-payer key with devnet SOL before settling."
                    },
            )
        }
    }

    private fun publishChannel(channel: SolanaEscrowChannel?) {
        mutableState.update {
            it.copy(
                channel = channel,
                pendingVoucher =
                    it.pendingVoucher?.takeIf { voucher ->
                        channel != null &&
                            !channel.isSignerRevoked &&
                            voucher.channel == channel.address &&
                            voucher.authorizedSigner == channel.authorizedSigner &&
                            // A deterministic address can be reopened after closure; old vouchers
                            // must not be reused against a different incarnation of that account.
                            it.channel?.startSlot == channel.startSlot &&
                            it.channel.startTimestamp == channel.startTimestamp &&
                            voucher.cumulativeAmount > channel.lastSettled &&
                            voucher.cumulativeAmount <= channel.totalDeposit
                    },
            )
        }
    }

    private suspend fun afterWrite(
        token: Long,
        message: String,
        preferredChannel: String? = null,
    ) {
        checkCurrent(token)
        mutableState.update { it.copy(status = message) }
        try {
            refreshContext(token, preferredChannel = preferredChannel)
            checkCurrent(token)
            mutableState.update { it.copy(status = message) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkCurrent(token)
            mutableState.update { it.copy(status = "$message\nRefresh failed: ${errorMessage(e)}. Refresh before another action.") }
        }
    }

    private fun runOperation(
        label: String,
        requireAccounts: Boolean = true,
        settlement: Boolean = false,
        block: suspend (Long, State) -> Unit,
    ) {
        val initial = state.value
        if (initial.isLoading) return
        if (initial.cluster.isBlank()) return
        if (requireAccounts) {
            unavailableReason(initial)?.let { reason ->
                mutableState.update { it.copy(status = reason) }
                return
            }
        }
        // Acquire busy synchronously, before launch can be scheduled or a second tap arrives.
        if (!mutableState.compareAndSet(initial, initial.copy(isLoading = true, status = label))) return
        val token = generation
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    checkNetwork(token, initial.cluster)
                    block(token, initial)
                } catch (e: CancellationException) {
                    if (generation == token) {
                        mutableState.update { it.copy(status = "Operation cancelled: ${errorMessage(e)}") }
                    }
                    throw e
                } catch (e: Exception) {
                    checkCurrent(token)
                    val guidance =
                        if (settlement) {
                            "\nIf the voucher blockhash expired, use Refresh Voucher, then Settle again; its amount will not change."
                        } else {
                            ""
                        }
                    mutableState.update { it.copy(status = "$label failed: ${errorMessage(e)}$guidance") }
                } finally {
                    // An obsolete operation must never clear a newer operation's busy flag.
                    if (generation == token) mutableState.update { it.copy(isLoading = false) }
                }
            }
        operation?.start()
    }

    private fun invalidate() {
        generation++
        operation?.cancel()
        operation = null
    }

    private suspend fun checkCurrent(token: Long) {
        currentCoroutineContext().ensureActive()
        if (token != generation) throw CancellationException("Solana debug context changed")
    }

    private suspend fun checkNetwork(
        token: Long,
        cluster: String,
    ) {
        checkCurrent(token)
        val actual = SolanaClusters.clusterFor(getCurrentNetwork().first())
        checkCurrent(token)
        if (actual != cluster || state.value.cluster != cluster) throw CancellationException("Solana cluster changed")
    }

    private fun ownerSigner(
        token: Long,
        cluster: String,
        owner: String,
        sign: suspend (String, ByteArray) -> ByteArray,
    ): SolanaTransactionSigner =
        object : SolanaTransactionSigner {
            override val address: String = owner

            override suspend fun signMessage(message: ByteArray): ByteArray {
                checkNetwork(token, cluster)
                val signature = sign(owner, message)
                // A platform approval may finish after a network change or cancellation.
                checkNetwork(token, cluster)
                require(signature.size == 64) { "Solana requires a 64-byte Ed25519 signature." }
                return signature
            }
        }

    private fun requireSelected(current: State): SolanaEscrowChannel =
        (current.channel ?: error("Select an open channel or deposit first.")).also { validateChannel(it, current) }

    private fun matchesRoles(
        channel: SolanaEscrowChannel,
        current: State,
    ): Boolean = channel.payer == current.payer && channel.payee == current.payee

    private fun validateChannel(
        channel: SolanaEscrowChannel,
        current: State,
    ) {
        require(matchesRoles(channel, current)) { "Channel payer/payee do not match the selected Seed Vault accounts." }
        require(channel.mint == manager.configFor(current.cluster).usdcMint) { "Channel is not using the configured USDC mint." }
        require(channel.lastSettled >= 0 && channel.totalDeposit >= channel.lastSettled) { "Invalid on-chain channel amounts." }
    }

    private fun validateVoucher(
        voucher: SolanaSettlementVoucher,
        current: State,
        channel: SolanaEscrowChannel,
    ) {
        require(voucher.channel == channel.address) { "Voucher belongs to another channel." }
        require(!channel.isSignerRevoked && voucher.authorizedSigner == channel.authorizedSigner) { "Voucher authority changed." }
        require(voucher.authorizedSigner == current.payerSessionKey && voucher.feePayer == current.payeeFeePayer) {
            "Voucher linked keys changed. Update Voucher first."
        }
        require(voucher.cumulativeAmount > channel.lastSettled && voucher.cumulativeAmount <= channel.totalDeposit) {
            "Voucher amount must exceed last settled and must not exceed the total deposit."
        }
    }

    private fun unavailableReason(current: State): String? =
        when {
            current.cluster != "devnet" -> MAINNET_UNSUPPORTED
            current.accounts.size < 2 || current.payer !in current.accounts || current.payee !in current.accounts ->
                "At least two local Seed Vault accounts are required."
            current.payer.isBlank() || current.payee.isBlank() -> "Select payer and payee Seed Vault accounts."
            current.payer == current.payee -> "Payer and payee must be different Seed Vault accounts."
            else -> null
        }

    private fun errorMessage(error: Throwable): String {
        val messages = mutableListOf<String>()
        var cause: Throwable? = error
        repeat(8) {
            val current = cause ?: return@repeat
            current.message?.takeIf { it.isNotBlank() && it !in messages }?.let(messages::add)
            cause = current.cause.takeUnless { it === current }
        }
        return messages.joinToString("\n").ifBlank { error.toString() }
    }

    private companion object {
        const val MAINNET_UNSUPPORTED = "Solana escrow debug is unsupported on mainnet. Switch the app to TestNet (Solana devnet)."
        const val DEBUG_DOMAIN = "wallet-sdk-solana-escrow-debug-v1"
        const val MAX_AMOUNT_LENGTH = 32

        /** 0.01 SOL: covers first-settle USDC account rent (~0.00204 SOL) plus many settle fees. */
        const val FEE_PAYER_FUNDING_LAMPORTS = 10_000_000L
        const val FUNDING_FEE_BUFFER_LAMPORTS = 10_000L

        fun sameChannel(
            left: SolanaEscrowChannel?,
            right: SolanaEscrowChannel?,
        ): Boolean {
            if (left === right) return true
            if (left == null || right == null) return false
            return left == right &&
                left.payer == right.payer &&
                left.payee == right.payee &&
                left.mint == right.mint &&
                left.authorizedSigner == right.authorizedSigner &&
                left.totalDeposit == right.totalDeposit &&
                left.lastSettled == right.lastSettled &&
                left.latestVoucherAmount == right.latestVoucherAmount &&
                left.startSlot == right.startSlot &&
                left.startTimestamp == right.startTimestamp &&
                left.closeRequestedAt == right.closeRequestedAt &&
                left.bump == right.bump
        }
    }
}

internal fun formatSolanaDebugSol(lamports: Long): String {
    val raw = lamports.coerceAtLeast(0).toString().padStart(10, '0')
    return "${raw.dropLast(9)}.${raw.takeLast(9)}".trimEnd('0').trimEnd('.')
}

/** Strict, positive six-decimal USDC parsing; never rounds or passes through floating point. */
internal fun parseSolanaDebugUsdcAmount(text: String): Long {
    require(text.isNotEmpty()) { "Enter a positive USDC amount." }
    val dot = text.indexOf('.')
    val whole = if (dot < 0) text else text.substring(0, dot)
    val fraction = if (dot < 0) "" else text.substring(dot + 1)
    require(whole.isNotEmpty() && whole.all { it in '0'..'9' }) { "Invalid USDC amount." }
    require(dot < 0 || (fraction.isNotEmpty() && fraction.length <= 6 && fraction.all { it in '0'..'9' })) {
        "USDC must have at most six decimal places and contain only decimal digits."
    }
    var raw = 0L
    for (digit in whole + fraction.padEnd(6, '0')) {
        val value = digit - '0'
        require(raw <= (Long.MAX_VALUE - value) / 10) { "USDC amount exceeds the supported range." }
        raw = raw * 10 + value
    }
    require(raw > 0) { "USDC amount must be greater than zero." }
    return raw
}
