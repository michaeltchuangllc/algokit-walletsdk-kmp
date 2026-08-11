package com.michaeltchuang.walletsdk.core.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface StateViewModel<State> {
    val state: StateFlow<State>
}

interface EventViewModel<ViewEvent> {
    val viewEvent: Flow<ViewEvent>
}

class StateDelegate<State> : StateViewModel<State> {
    private lateinit var _state: MutableStateFlow<State>
    override val state: StateFlow<State>
        get() {
            return _state.asStateFlow()
        }

    fun setDefaultState(state: State) {
        _state = MutableStateFlow(state)
    }

    fun updateState(block: (State) -> State) {
        _state.update {
            block(it)
        }
    }

    inline fun <reified SubState : State> onState(block: (SubState) -> Unit) {
        val currentState = state.value
        if (currentState is SubState) {
            block(currentState)
        }
    }
}

class EventDelegate<ViewEvent> : EventViewModel<ViewEvent> {
    // Use replay=0 to ensure events are only processed once and not re-emitted on back navigation
    private val _viewEvent = MutableSharedFlow<ViewEvent>(replay = 0, extraBufferCapacity = 10)
    override val viewEvent: Flow<ViewEvent> = _viewEvent.asSharedFlow()

    fun sendEvent(
        scope: CoroutineScope,
        newEvent: ViewEvent,
    ) {
        scope.launch {
            sendEvent(newEvent)
        }
    }

    suspend fun sendEvent(newEvent: ViewEvent) {
        _viewEvent.emit(newEvent)
    }
}
