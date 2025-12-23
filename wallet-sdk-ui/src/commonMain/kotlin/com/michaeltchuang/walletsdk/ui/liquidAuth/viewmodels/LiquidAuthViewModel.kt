package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.fromUri
import kotlinx.coroutines.launch

data class AuthMessage(
    val origin: String,
    val requestId: String,
)

class LiquidAuthViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidAuthViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidAuthViewModel.ViewEvent> by eventDelegate {
    lateinit var authMessage: AuthMessage

    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun initialize(uri: String?) {
        viewModelScope.launch {
            if (uri.isNullOrEmpty()) {
                stateDelegate.updateState {
                    ViewState.Error("No URI provided")
                }
                return@launch
            }

            stateDelegate.updateState {
                ViewState.Loading
            }

            try {
                authMessage = fromUri(uri)
                stateDelegate.updateState {
                    ViewState.Content(
                        origin = authMessage.origin,
                        requestId = authMessage.requestId,
                        rawUri = uri,
                    )
                }
            } catch (e: Exception) {
                stateDelegate.updateState {
                    ViewState.Error("Failed to parse URI: ${e.message}")
                }
            }
        }
    }

    fun processAuthentication() {
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val origin: String,
            val requestId: String,
            val rawUri: String,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object AuthenticationSuccess : ViewEvent

        data class AuthenticationError(
            val message: String,
        ) : ViewEvent
    }
}
