package com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.solana.domain.EnsureSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaLinkedSignerUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.GetSolanaSignerBalanceUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.RegenerateSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaSignerBalance
import com.michaeltchuang.walletsdk.core.solana.domain.SweepSolanaLinkedSignerUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shows the device-generated session key of a Seed Vault account. Seed Vault keys never leave
 * secure hardware, so this key signs vouchers when the account pays (no SOL needed) and pays the
 * settle fees when the account receives (needs a little SOL). It never needs USDC. Every Seed
 * Vault account always has one: it's created on import, or here on first visit as a fallback.
 * The key can be regenerated but never removed.
 */
class SolanaSessionKeyViewModel(
    private val getLinkedSigner: GetSolanaLinkedSignerUseCase,
    private val ensureSessionKey: EnsureSolanaSessionKeyUseCase,
    private val regenerateSessionKey: RegenerateSolanaSessionKeyUseCase,
    private val getSignerBalance: GetSolanaSignerBalanceUseCase,
    private val sweepSigner: SweepSolanaLinkedSignerUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SolanaSessionKeyViewModel.ViewState> by stateDelegate,
    EventViewModel<SolanaSessionKeyViewModel.ViewEvent> by eventDelegate {
    private var ownerAddress: String? = null
    private var observeJob: Job? = null

    init {
        stateDelegate.setDefaultState(ViewState())
    }

    fun load(address: String) {
        if (ownerAddress == address && observeJob != null) return
        ownerAddress = address
        observeJob?.cancel()
        observeJob =
            viewModelScope.launch {
                runCatching { ensureSessionKey(address) }
                    .onFailure { sendError(it, "Failed to create session key") }
                getLinkedSigner.observe(address).collect { signer ->
                    val changed = signer?.signerAddress != state.value.linkedSigner?.signerAddress
                    stateDelegate.updateState {
                        it.copy(
                            isLoading = false,
                            linkedSigner = signer,
                            balance = if (changed) null else it.balance,
                        )
                    }
                    if (signer != null && changed) refreshBalance()
                }
            }
    }

    fun refreshBalance() {
        val signer = state.value.linkedSigner ?: return
        viewModelScope.launch {
            stateDelegate.updateState { it.copy(isBalanceLoading = true) }
            runCatching { getSignerBalance(signer.signerAddress) }
                .onSuccess { balance -> stateDelegate.updateState { it.copy(balance = balance) } }
            // Keep the last known balance if RPC is unreachable; don't nag the user with an error.
            stateDelegate.updateState { it.copy(isBalanceLoading = false) }
        }
    }

    fun regenerate() =
        runBusy("Failed to regenerate session key") { owner ->
            regenerateSessionKey(owner)
            eventDelegate.sendEvent(ViewEvent.Message("New session key generated. If you receive payments, add a little SOL for settlement fees."))
        }

    fun sweep() =
        runBusy("Failed to return funds") { owner ->
            val signature = sweepSigner(owner)
            eventDelegate.sendEvent(ViewEvent.Message("Funds returned to Seed Vault (${signature.take(8)}…)"))
            refreshBalance()
        }

    private fun runBusy(
        fallbackError: String,
        block: suspend (owner: String) -> Unit,
    ) {
        val owner = ownerAddress ?: return
        if (state.value.isBusy) return
        viewModelScope.launch {
            stateDelegate.updateState { it.copy(isBusy = true) }
            try {
                block(owner)
            } catch (e: Exception) {
                sendError(e, fallbackError)
            } finally {
                stateDelegate.updateState { it.copy(isBusy = false) }
            }
        }
    }

    private suspend fun sendError(
        throwable: Throwable,
        fallback: String,
    ) {
        eventDelegate.sendEvent(ViewEvent.Message(throwable.message ?: fallback))
    }

    data class ViewState(
        val isLoading: Boolean = true,
        val isBusy: Boolean = false,
        val linkedSigner: SolanaLinkedSigner? = null,
        val balance: SolanaSignerBalance? = null,
        val isBalanceLoading: Boolean = false,
    )

    sealed interface ViewEvent {
        data class Message(
            val text: String,
        ) : ViewEvent
    }
}
