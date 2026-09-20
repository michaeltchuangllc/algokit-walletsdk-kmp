package com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels

import androidx.lifecycle.ViewModelStore
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiquidAuthViewerChatTest {
    private val store = ViewModelStore()
    private val events = mockk<EventDelegate<LiquidAuthViewerViewModel.ViewEvent>>()
    private lateinit var viewer: LiquidAuthViewerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val network = mockk<GetCurrentNetworkUseCase>()
        every { network() } returns MutableStateFlow(AlgorandNetwork.TESTNET)
        every { events.sendEvent(any(), any<LiquidAuthViewerViewModel.ViewEvent>()) } returns Unit
        viewer = LiquidAuthViewerViewModel(mockk(), StateDelegate(), events, network)
        store.put("viewer", viewer)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun incomingAndOutgoingChatUseTheSameScreenState() {
        val screen = store["viewer"] as LiquidAuthViewerViewModel
        viewer.receivedChatMessage(ChatMessage(sender = "host", text = "Hello iOS", timestamp = 1L))
        assertEquals("Hello iOS", screen.state.value.chatMessages.single().text)

        screen.onMessageChanged("Hello host")
        screen.onSendClicked()
        verify(exactly = 1) {
            events.sendEvent(any(), LiquidAuthViewerViewModel.ViewEvent.SendMessage("Hello host", null, null))
        }
        assertEquals("", viewer.state.value.message)
        assertEquals(1, viewer.state.value.chatMessages.size)
    }

    @Test
    fun restoringScreenUsesBufferedMessagesWithoutAnotherViewModel() {
        viewer.receivedChatMessage(ChatMessage(sender = "host", text = "Before minimize", timestamp = 1L))
        viewer.receivedChatMessage(ChatMessage(sender = "host", text = "While minimized", timestamp = 2L))
        val restoredScreen = store["viewer"] as LiquidAuthViewerViewModel
        assertTrue(restoredScreen === viewer)
        assertEquals(listOf("Before minimize", "While minimized"), restoredScreen.state.value.chatMessages.map { it.text })
    }
}
