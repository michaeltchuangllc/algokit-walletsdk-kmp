package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.algosdk.createAlgo25Account
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

class PasskeysViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val passkeyRepository: PasskeyRepository,
) : ViewModel(),
    StateViewModel<PasskeysViewModel.ViewState> by stateDelegate,
    EventViewModel<PasskeysViewModel.ViewEvent> by eventDelegate {
    init {
        createAlgo25Account()
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun fetchPasskeys() {
        viewModelScope.launch {
            stateDelegate.updateState {
                ViewState.Loading
            }
            passkeyRepository.getAllPasskeysAsFlow().collect { passkeys ->
                val now = Clock.System.now().toEpochMilliseconds()
                val uiPasskeys =
                    passkeys.map { domainPasskey ->
                        Passkey(
                            credId = domainPasskey.credId,
                            title = domainPasskey.displayName.toShortenedAddress(),
                            domain = domainPasskey.site.url,
                            lastUsed = formatLastUsedLabel(domainPasskey.lastUsed, now),
                            username = domainPasskey.username.toShortenedAddress(),
                        )
                    }
                stateDelegate.updateState {
                    ViewState.Content(uiPasskeys)
                }
            }
        }
    }

    fun deletePasskey(credId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                passkeyRepository.removePasskeyByCredentialId(credId)
                fetchPasskeys()
            }
        }
    }

    data class Passkey(
        val credId: String,
        val title: String,
        val domain: String,
        val lastUsed: String,
        val username: String,
    )

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val passkeys: List<Passkey>,
        ) : ViewState
    }

    sealed interface ViewEvent {
        // Add view events as needed (e.g., for delete, error etc.)
    }

    companion object {
        private fun formatLastUsedLabel(
            lastUsedEpochMillis: Long?,
            now: Long,
        ): String {
            if (lastUsedEpochMillis == null || lastUsedEpochMillis == 0L) return "-"
            val diff = abs(now - lastUsedEpochMillis)
            return when {
                diff < 60_000L -> "Just now"
                diff < 60 * 60_000L -> "${diff / 60_000} min ago"
                diff < 24 * 60 * 60_000L -> "${diff / (60 * 60_000)} hr ago"
                diff < 7 * 24 * 60 * 60_000L -> "${diff / (24 * 60 * 60_000)} days ago"
                else -> {
                    val date = Instant.fromEpochMilliseconds(lastUsedEpochMillis)
                    val localDate = date.toLocalDateTime(TimeZone.currentSystemDefault()).date
                    localDate.toString()
                }
            }
        }
    }
}
