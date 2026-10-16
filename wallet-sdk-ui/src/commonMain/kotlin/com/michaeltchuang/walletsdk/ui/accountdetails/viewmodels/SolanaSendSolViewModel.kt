package com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.solana.domain.EnsureSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SendSolanaSolUseCase
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends SOL from a Seed Vault account to another Solana address. Each send needs one Seed Vault
 * approval. The common use is topping up the account's own session key so it can pay
 * Liquid Stream settlement fees, so that address is offered as a one-tap recipient.
 */
class SolanaSendSolViewModel(
    private val sendSol: SendSolanaSolUseCase,
    private val ensureSessionKey: EnsureSolanaSessionKeyUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SolanaSendSolViewModel.ViewState> by stateDelegate,
    EventViewModel<SolanaSendSolViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState())
    }

    fun load(
        sender: String,
        prefillReceiver: String?,
    ) {
        if (state.value.sender == sender) return
        stateDelegate.updateState {
            it.copy(sender = sender, receiver = prefillReceiver?.takeIf(String::isNotBlank) ?: it.receiver)
        }
        viewModelScope.launch {
            runCatching { ensureSessionKey(sender).signerAddress }
                .onSuccess { key -> stateDelegate.updateState { it.copy(sessionKeyAddress = key) } }
        }
        refreshBalance()
    }

    fun refreshBalance() {
        val sender = state.value.sender.ifBlank { return }
        viewModelScope.launch {
            stateDelegate.updateState { it.copy(isBalanceLoading = true) }
            runCatching { sendSol.getBalance(sender) }
                .onSuccess { lamports -> stateDelegate.updateState { it.copy(balanceLamports = lamports) } }
                .onFailure { sendMessage(it.message ?: "Couldn't load SOL balance") }
            stateDelegate.updateState { it.copy(isBalanceLoading = false) }
        }
    }

    fun setReceiver(value: String) = stateDelegate.updateState { it.copy(receiver = value.trim()) }

    fun setAmount(value: String) {
        if (value.isNotEmpty() && !Regex("""\d*\.?\d{0,9}""").matches(value)) return
        stateDelegate.updateState { it.copy(amount = value) }
    }

    fun useSessionKey() {
        val key = state.value.sessionKeyAddress ?: return
        stateDelegate.updateState {
            it.copy(receiver = key, amount = it.amount.ifBlank { SendSolanaSolUseCase.formatSol(SUGGESTED_SESSION_KEY_TOP_UP) })
        }
    }

    fun setMax() {
        val balance = state.value.balanceLamports ?: return
        val max = (balance - SendSolanaSolUseCase.FEE_LAMPORTS).coerceAtLeast(0)
        stateDelegate.updateState { it.copy(amount = SendSolanaSolUseCase.formatSol(max)) }
    }

    /** [sign] is the platform Seed Vault prompt; it returns a 64-byte signature over the message. */
    fun send(sign: suspend (address: String, message: ByteArray) -> ByteArray) {
        val current = state.value
        if (current.isSending) return
        val lamports = SendSolanaSolUseCase.parseSol(current.amount)
        if (lamports == null || lamports <= 0) {
            viewModelScope.launch { sendMessage("Enter a valid SOL amount") }
            return
        }
        viewModelScope.launch {
            stateDelegate.updateState { it.copy(isSending = true) }
            try {
                val signer =
                    object : SolanaTransactionSigner {
                        override val address: String = current.sender

                        override suspend fun signMessage(message: ByteArray): ByteArray = sign(current.sender, message)
                    }
                val signature = sendSol(signer, current.receiver, lamports)
                stateDelegate.updateState { it.copy(amount = "", lastSignature = signature) }
                eventDelegate.sendEvent(
                    ViewEvent.Sent(
                        "Sent ${SendSolanaSolUseCase.formatSol(lamports)} SOL (${signature.take(8)}…)",
                    ),
                )
                refreshBalance()
            } catch (e: CancellationException) {
                // A dismissed Seed Vault prompt surfaces as cancellation; keep the screen usable.
                // Real scope cancellation (ViewModel cleared) must still propagate.
                if (!currentCoroutineContext().isActive) throw e
                sendMessage("Seed Vault approval cancelled")
            } catch (e: Exception) {
                sendMessage(e.message ?: "Failed to send SOL")
            } finally {
                stateDelegate.updateState { it.copy(isSending = false) }
            }
        }
    }

    private suspend fun sendMessage(text: String) = eventDelegate.sendEvent(ViewEvent.Message(text))

    data class ViewState(
        val sender: String = "",
        val receiver: String = "",
        val amount: String = "",
        val sessionKeyAddress: String? = null,
        val balanceLamports: Long? = null,
        val isBalanceLoading: Boolean = false,
        val isSending: Boolean = false,
        val lastSignature: String? = null,
    )

    sealed interface ViewEvent {
        data class Message(
            val text: String,
        ) : ViewEvent

        data class Sent(
            val text: String,
        ) : ViewEvent
    }

    companion object {
        /** Covers the session key's rent-exempt minimum plus thousands of settlement fees. */
        const val SUGGESTED_SESSION_KEY_TOP_UP = 10_000_000L // 0.01 SOL
    }
}
