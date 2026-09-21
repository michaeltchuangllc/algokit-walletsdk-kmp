package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.content.Context
import android.util.Log
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.VideoTrack

/**
 * Tests real PeerApi instances borrowing mocked media; no camera, EGL, or JNI is initialized.
 *
 * Do not use spies/callOriginal on WebRTC objects: their constructors and methods require JNI.
 * In stream-webrtc 1.3.10 these mocked classes do not load JNI in static initializers.
 * PeerConnection.dispose() closes the real connection internally; a mocked dispose() does not.
 * These tests verify the disposal request, not native close/ref-count behavior or private ownership.
 */
class PeerApiSharedMediaTest {
    private val context: Context = mockk()
    private val sharedMedia: SharedBroadcastMedia = mockk(relaxed = true)
    private val factory: PeerConnectionFactory = mockk()
    private val eglContext: EglBase.Context = mockk()
    private val videoTrack: VideoTrack = mockk(relaxed = true)
    private val firstConnection: PeerConnection = mockk(relaxed = true)
    private val secondConnection: PeerConnection = mockk(relaxed = true)
    private val observers = mutableListOf<PeerConnection.Observer>()

    @Before
    fun setup() {
        // Android host tests use android.jar stubs, not Robolectric.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any(), any<Throwable>()) } returns 0

        every { sharedMedia.factory } returns factory
        every { sharedMedia.eglBaseContext } returns eglContext
        every { sharedMedia.localVideoTrack } returns videoTrack
        every { sharedMedia.startCapture(any(), any(), any()) } returns videoTrack

        val connections = listOf(firstConnection, secondConnection).iterator()
        every {
            factory.createPeerConnection(
                any<PeerConnection.RTCConfiguration>(),
                any<PeerConnection.Observer>(),
            )
        } answers {
            observers.add(secondArg())
            connections.next()
        }
    }

    @After
    fun teardown() {
        // Restore only the static mock installed here; no global unmockkAll side effects.
        unmockkStatic(Log::class)
    }

    @Test
    fun `EXPECT two peers to borrow the same EGL track and factory WHEN neither has started capture yet`() {
        val first = PeerApi(context, sharedMedia)
        assertSame(eglContext, first.eglBaseContext)
        assertSame(videoTrack, first.localVideoTrack)

        val second = PeerApi(context, sharedMedia)
        assertSame(first.eglBaseContext, second.eglBaseContext)
        assertSame(first.localVideoTrack, second.localVideoTrack)

        verify(exactly = 0) {
            sharedMedia.startCapture(any(), any(), any())
            sharedMedia.dispose()
        }
        // An injected owner avoids even accessing the Android context to create private media.
        verify { context wasNot Called }

        connect(first)
        connect(second)

        assertSame(firstConnection, first.peerConnection)
        assertSame(secondConnection, second.peerConnection)
        verify(exactly = 2) { sharedMedia.factory }
        verify(exactly = 2) {
            factory.createPeerConnection(
                match<PeerConnection.RTCConfiguration> {
                    it.sdpSemantics == PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                any<PeerConnection.Observer>(),
            )
        }

        assertSame(videoTrack, first.startLocalCapture())
        assertSame(videoTrack, second.startLocalCapture())
        verify(exactly = 2) { sharedMedia.startCapture(1280, 720, 30) }
        verify(exactly = 1) { sharedMedia.attachTracks(firstConnection) }
        verify(exactly = 1) { sharedMedia.attachTracks(secondConnection) }
        // Owner is mocked: the two delegate calls above do not prove native capture starts once.
    }

    @Test
    fun `EXPECT only its own connection to be disposed WHEN one of two peers is destroyed`() {
        val first = PeerApi(context, sharedMedia)
        val second = PeerApi(context, sharedMedia)
        connect(first)
        connect(second)
        first.startLocalCapture()
        second.startLocalCapture()

        first.destroy()

        assertNull(first.peerConnection)
        assertNull(first.localVideoTrack)
        assertSame(secondConnection, second.peerConnection)
        assertSame(eglContext, second.eglBaseContext)
        assertSame(videoTrack, second.localVideoTrack)
        verify(exactly = 1) { firstConnection.dispose() }
        verify(exactly = 0) {
            secondConnection.close()
            secondConnection.dispose()
            sharedMedia.dispose()
            factory.dispose()
            videoTrack.dispose()
            sharedMedia.setAudioEnabled(any())
            sharedMedia.setVideoEnabled(any())
        }

        assertSame(videoTrack, second.startLocalCapture(width = 640, height = 480, fps = 15))
        second.switchCamera()
        second.setAudioEnabled(false)
        second.setVideoEnabled(false)
        second.createOffer(onSessionDescription = {})

        verify(exactly = 1) { sharedMedia.startCapture(640, 480, 15) }
        verify(exactly = 2) { sharedMedia.attachTracks(secondConnection) }
        verify(exactly = 1) { sharedMedia.switchCamera() }
        verify(exactly = 1) { sharedMedia.setAudioEnabled(false) }
        verify(exactly = 1) { sharedMedia.setVideoEnabled(false) }
        verify(exactly = 1) {
            secondConnection.createOffer(any<SdpObserver>(), any<MediaConstraints>())
        }
        verify(exactly = 0) { sharedMedia.dispose() }
    }

    @Test
    fun `EXPECT peer and channel resources to release exactly once WHEN destroy is called twice`() {
        val first = PeerApi(context, sharedMedia)
        val second = PeerApi(context, sharedMedia)
        connect(first)
        connect(second)
        val channel = mockk<DataChannel>(relaxed = true)
        val additionalChannel = mockk<DataChannel>(relaxed = true)
        every { firstConnection.createDataChannel("liquid", any()) } returns channel
        every { firstConnection.createDataChannel("payments", any()) } returns additionalChannel
        first.createDataChannel("liquid")
        first.createAdditionalDataChannel("payments")

        first.destroy()
        first.destroy()

        assertNull(first.peerConnection)
        assertNull(first.getAdditionalDataChannel("payments"))
        assertSame(secondConnection, second.peerConnection)
        verify(exactly = 1) {
            channel.unregisterObserver()
            channel.close()
            channel.dispose()
            additionalChannel.unregisterObserver()
            additionalChannel.close()
            additionalChannel.dispose()
            firstConnection.dispose()
        }
        verifyOrder {
            channel.unregisterObserver()
            channel.close()
            channel.dispose()
            firstConnection.dispose()
        }
        verify(exactly = 0) {
            secondConnection.close()
            secondConnection.dispose()
            sharedMedia.dispose()
            factory.dispose()
            videoTrack.dispose()
        }

        second.destroy()
        second.destroy()
        verify(exactly = 1) { secondConnection.dispose() }
        verify(exactly = 0) { sharedMedia.dispose() }
    }

    @Test
    fun `EXPECT ICE failure to notify only its own peer WHEN stale callbacks fire after destroy`() {
        val first = PeerApi(context, sharedMedia)
        val second = PeerApi(context, sharedMedia)
        val firstStates = mutableListOf<PeerConnection.IceConnectionState>()
        val secondStates = mutableListOf<PeerConnection.IceConnectionState>()
        first.onIceConnectionStateChange = { firstStates.add(it) }
        second.onIceConnectionStateChange = { secondStates.add(it) }
        connect(first)
        connect(second)

        observers[0].onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)

        assertEquals(listOf(PeerConnection.IceConnectionState.FAILED), firstStates)
        assertEquals(emptyList<PeerConnection.IceConnectionState>(), secondStates)
        verify(exactly = 0) {
            firstConnection.close()
            firstConnection.dispose()
            secondConnection.close()
            secondConnection.dispose()
            sharedMedia.dispose()
        }

        // Teardown happens after the observer returns, never from within the native callback.
        first.destroy()
        assertNull(first.onIceConnectionStateChange)
        // Even a newly assigned listener must not receive events from the destroyed connection.
        first.onIceConnectionStateChange = { firstStates.add(it) }
        observers[0].onIceConnectionChange(PeerConnection.IceConnectionState.CLOSED)
        observers[1].onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)

        assertEquals(listOf(PeerConnection.IceConnectionState.FAILED), firstStates)
        assertEquals(listOf(PeerConnection.IceConnectionState.CONNECTED), secondStates)
        assertSame(secondConnection, second.peerConnection)
        verify(exactly = 0) {
            secondConnection.close()
            secondConnection.dispose()
            sharedMedia.dispose()
        }
    }

    private fun connect(peer: PeerApi) {
        peer.createPeerConnection(
            onIceCandidate = {},
            onDataChannel = {},
            iceServers = emptyList(),
        )
    }
}
