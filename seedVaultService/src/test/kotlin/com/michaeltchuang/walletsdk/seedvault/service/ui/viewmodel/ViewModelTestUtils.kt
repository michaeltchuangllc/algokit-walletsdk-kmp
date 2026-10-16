package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * Runs viewModelScope on a [StandardTestDispatcher], which `runTest` picks up, so ViewModel init
 * work only runs on `advanceUntilIdle()` and view events can be collected before they are sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {
    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    protected fun <T> TestScope.collectEvents(viewEvent: Flow<T>): List<T> {
        val events = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewEvent.toList(events) }
        return events
    }

    protected fun <T> stateDelegate() = StateDelegate<T>()

    protected fun <T> eventDelegate() = EventDelegate<T>()
}
