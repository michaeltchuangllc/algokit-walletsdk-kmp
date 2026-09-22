/*
 * Copyright 2025 Algorand Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import CoreMedia
import Foundation
import WebRTC

/// One source/factory per broadcast, retained independently of the viewer registry.
/// Camera ownership stays in the app (this SDK is also built into the extension).
final class SharedBroadcastMedia {
    let factory: RTCPeerConnectionFactory
    let videoSource: RTCVideoSource
    let audioSource: RTCAudioSource
    let previewTrack: RTCVideoTrack
    var audioEnabled = true
    var videoEnabled = true

    init() {
        RTCPeerConnectionFactory.initialize()
        factory = RTCPeerConnectionFactory()
        videoSource = factory.videoSource()
        audioSource = factory.audioSource(
            with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        )
        previewTrack = factory.videoTrack(with: videoSource, trackId: "broadcast_preview")
    }

    func attach(to peer: PeerApi) {
        guard peer.localVideoTrack == nil, let connection = peer.peerConnection else { return }
        // Separate tracks allow a future per-peer gate without muting other viewers.
        let video = factory.videoTrack(with: videoSource, trackId: "local_video")
        let audio = factory.audioTrack(with: audioSource, trackId: "local_audio")
        video.isEnabled = videoEnabled
        audio.isEnabled = audioEnabled
        connection.add(video, streamIds: ["liquid_stream"])
        connection.add(audio, streamIds: ["liquid_stream"])
        peer.videoSource = videoSource
        peer.audioSource = audioSource
        peer.localVideoTrack = video
        peer.localAudioTrack = audio
    }

    func makeRenderer() -> RTCMTLVideoView {
        let renderer = RTCMTLVideoView(frame: .zero)
        renderer.videoContentMode = .scaleAspectFill
        previewTrack.add(renderer)
        return renderer
    }
}

/// Reports the actual channel, not a captured mutable "current channel" slot.
/// All callbacks are serialized with signaling/registry operations on main.
final class ScopedDataChannelDelegate: NSObject, RTCDataChannelDelegate {
    private var active = true
    private var lastState: RTCDataChannelState?
    private let onMessage: (String) -> Void
    private let onState: (RTCDataChannel, String) -> Void

    init(onMessage: @escaping (String) -> Void,
         onState: @escaping (RTCDataChannel, String) -> Void) {
        self.onMessage = onMessage
        self.onState = onState
    }

    func invalidate() { active = false }

    func dataChannelDidChangeState(_ channel: RTCDataChannel) {
        let state = channel.readyState
        DispatchQueue.main.async { [weak self, weak channel] in
            guard let self, self.active, let channel, self.lastState != state else { return }
            self.lastState = state
            self.onState(channel, state.description)
        }
    }

    func dataChannel(_ channel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        guard let message = String(data: buffer.data, encoding: .utf8) else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.active else { return }
            self.onMessage(message)
        }
    }

    func dataChannel(_ channel: RTCDataChannel, didChangeBufferedAmount amount: UInt64) {}
}

// MARK: - PeerApi

class PeerApi {
    let peerConnectionFactory: RTCPeerConnectionFactory
    var peerConnection: RTCPeerConnection?
    private var peerConnectionDelegate: PeerConnectionDelegate?
    private var dataChannel: RTCDataChannel?
    private let onDataChannel: (RTCDataChannel) -> Void
    private var dataChannelDelegates: [RTCDataChannel: ScopedDataChannelDelegate] = [:]
    private weak var signalService: SignalService?
    private let enableMedia: Bool
    private let onRemoteVideoTrack: (RTCVideoTrack) -> Void
    var videoSource: RTCVideoSource?
    var audioSource: RTCAudioSource?
    var localVideoTrack: RTCVideoTrack?
    var localAudioTrack: RTCAudioTrack?

    init(
        iceServers: [RTCIceServer],
        poolSize: Int,
        signalService: SignalService?,
        enableMedia: Bool,
        factory: RTCPeerConnectionFactory? = nil,
        onConnectionStateChange: @escaping (RTCPeerConnectionState) -> Void = { _ in },
        onDataChannel: @escaping (RTCDataChannel) -> Void,
        onRemoteVideoTrack: @escaping (RTCVideoTrack) -> Void,
        onIceCandidate: @escaping (RTCIceCandidate) -> Void
    ) {
        self.signalService = signalService
        self.enableMedia = enableMedia
        self.onDataChannel = onDataChannel
        self.onRemoteVideoTrack = onRemoteVideoTrack
        // Initialize the PeerConnectionFactory
        RTCPeerConnectionFactory.initialize()
        peerConnectionFactory = factory ?? RTCPeerConnectionFactory()

        // Create the PeerConnection configuration
        let configuration = RTCConfiguration()
        configuration.iceServers = iceServers
        configuration.iceCandidatePoolSize = Int32(poolSize)
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherContinually

        let delegate = PeerConnectionDelegate(
            onIceCandidate: onIceCandidate,
            onDataChannel: onDataChannel,
            onRemoteVideoTrack: onRemoteVideoTrack,
            onConnectionStateChange: { state in
                Logger.debug("PeerAPI: Peer connection state changed: \(state.rawValue)")
                onConnectionStateChange(state)
            }
        )

        // Create the PeerConnection
        let constraints = mediaConstraints()

        peerConnectionDelegate = delegate
        peerConnection = peerConnectionFactory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: delegate
        )
    }

    // Create a new Peer Connection
    func createPeerConnection(
        onIceCandidate: @escaping (RTCIceCandidate) -> Void,
        onDataChannel: @escaping (RTCDataChannel) -> Void,
        onConnectionStateChange: @escaping (RTCPeerConnectionState) -> Void,
        iceServers: [RTCIceServer]
    ) {
        let configuration = RTCConfiguration()
        configuration.iceServers = iceServers
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherContinually

        let constraints = mediaConstraints()
        peerConnection?.delegate = nil
        peerConnection?.close()
        let delegate = PeerConnectionDelegate(
            onIceCandidate: onIceCandidate,
            onDataChannel: onDataChannel,
            onRemoteVideoTrack: onRemoteVideoTrack,
            onConnectionStateChange: onConnectionStateChange
        )
        peerConnectionDelegate = delegate
        peerConnection = peerConnectionFactory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: delegate
        )
    }

    // Add an ICE Candidate
    func addIceCandidate(_ candidate: RTCIceCandidate) throws {
        guard let peerConnection else {
            throw NSError(
                domain: "PeerApi",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "PeerConnection is null, ensure you are connected"]
            )
        }
        peerConnection.add(candidate, completionHandler: { error in
            if let error {
                Logger.error("PeerAPI: addIceCandidate: Failed to add ICE candidate: \(error)")
            } else {
                Logger.debug("PeerAPI: addIceCandidate: ICE candidate added successfully.")
            }
        })
    }

    // Set the Local Description
    func setLocalDescription(_ description: RTCSessionDescription, completion: @escaping (Error?) -> Void) {
        guard let peerConnection else {
            Logger.error("PeerAPI: PeerConnection is null, ensure you are connected")
            completion(NSError(domain: "PeerApi", code: -1))
            return
        }
        Logger.debug("PeerAPI: Setting local description: \(description.type.rawValue)")
        peerConnection.setLocalDescription(description) { error in
            DispatchQueue.main.async { completion(error) }
        }
    }

    func setRemoteDescription(_ description: RTCSessionDescription, completion: @escaping (Error?) -> Void) {
        guard let peerConnection else {
            Logger.error("PeerAPI: PeerConnection is null, ensure you are connected")
            completion(NSError(domain: "PeerApi", code: -1))
            return
        }

        if peerConnection.signalingState == .haveLocalOffer && description.type == .offer {
            Logger
                .error("PeerAPI: PeerAPI setRemoteDescription: Cannot set remote offer while in have-local-offer state")
            completion(NSError(domain: "PeerApi", code: -2))
            return
        }

        Logger.debug("PeerAPI: Setting remote description: \(description.type.rawValue)")
        peerConnection.setRemoteDescription(description) { error in
            DispatchQueue.main.async { completion(error) }
        }
    }

    // Create an Offer
    func createOffer(completion: @escaping (RTCSessionDescription?) -> Void) {
        guard let peerConnection else {
            Logger.error("PeerAPI: PeerConnection is null, ensure you are connected")
            completion(nil)
            return
        }
        peerConnection.offer(for: mediaConstraints()) { sdp, error in
            if let error {
                Logger.error("PeerAPI: Failed to create offer: \(error)")
                DispatchQueue.main.async { completion(nil) }
            } else {
                DispatchQueue.main.async { completion(sdp) }
            }
        }
    }

    // Create an Answer
    func createAnswer(completion: @escaping (RTCSessionDescription?) -> Void) {
        guard let peerConnection else {
            Logger.error("PeerAPI: PeerConnection is null, ensure you are connected")
            completion(nil)
            return
        }
        peerConnection.answer(for: mediaConstraints()) { sdp, error in
            if let error {
                Logger.error("PeerAPI: Failed to create answer: \(error)")
                DispatchQueue.main.async { completion(nil) }
            } else {
                DispatchQueue.main.async { completion(sdp) }
            }
        }
    }

    private func mediaConstraints() -> RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": enableMedia ? "true" : "false",
                "OfferToReceiveVideo": enableMedia ? "true" : "false",
            ],
            optionalConstraints: ["DtlsSrtpKeyAgreement": "true"]
        )
    }

    func setAudioEnabled(_ enabled: Bool) {
        localAudioTrack?.isEnabled = enabled
    }

    func setVideoEnabled(_ enabled: Bool) {
        localVideoTrack?.isEnabled = enabled
    }

    func makeLocalVideoRenderer() -> RTCMTLVideoView? {
        guard let localVideoTrack else { return nil }
        let renderer = RTCMTLVideoView(frame: .zero)
        renderer.videoContentMode = .scaleAspectFill
        localVideoTrack.add(renderer)
        return renderer
    }

    // Create a Data Channel
    func createDataChannel(
        label: String,
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void,
        onOpen: @escaping (RTCDataChannel) -> Void = { _ in }
    ) -> RTCDataChannel? {
        let config = RTCDataChannelConfiguration()
        Logger.debug("PeerAPI: Creating data channel with label: \(label)")
        dataChannel = peerConnection?.dataChannel(forLabel: label, configuration: config)

        if let dataChannel {
            let delegate = ScopedDataChannelDelegate(
                onMessage: onMessage,
                onState: { channel, state in
                    if state == "open" { onOpen(channel) }
                    onStateChange(state)
                }
            )
            dataChannel.delegate = delegate
            dataChannelDelegates[dataChannel] = delegate
            delegate.dataChannelDidChangeState(dataChannel)
            Logger.debug("PeerApi: DataChannelDelegate assigned to data channel: \(dataChannel.label)")
        }

        return dataChannel
    }

    // Send a message through the Data Channel
    func send(_ message: String) {
        guard let dataChannel else {
            Logger.error("PeerAPI: peerApi: Data channel is not available.")
            return
        }

        let buffer = RTCDataBuffer(data: message.data(using: .utf8)!, isBinary: false)
        dataChannel.sendData(buffer)
    }

    // Close the Peer Connection
    func close() {
        for (channel, delegate) in dataChannelDelegates {
            delegate.invalidate()
            channel.delegate = nil
            channel.close()
        }
        dataChannelDelegates.removeAll()
        dataChannel?.close()
        peerConnection?.delegate = nil
        peerConnection?.close()
        dataChannel = nil
        peerConnection = nil
        peerConnectionDelegate = nil
        localVideoTrack = nil
        localAudioTrack = nil
        videoSource = nil
        audioSource = nil
    }
}

// MARK: - PeerConnectionDelegate

// Delegate to handle PeerConnection events
class PeerConnectionDelegate: NSObject, RTCPeerConnectionDelegate {
    private let onIceCandidate: (RTCIceCandidate) -> Void
    private let onDataChannel: (RTCDataChannel) -> Void
    private let onRemoteVideoTrack: (RTCVideoTrack) -> Void
    private let onConnectionStateChange: (RTCPeerConnectionState) -> Void

    init(
        onIceCandidate: @escaping (RTCIceCandidate) -> Void,
        onDataChannel: @escaping (RTCDataChannel) -> Void,
        onRemoteVideoTrack: @escaping (RTCVideoTrack) -> Void,
        onConnectionStateChange: @escaping (RTCPeerConnectionState) -> Void
    ) {
        Logger.debug("PeerAPI: PeerConnectionDelegate initialized")
        self.onIceCandidate = onIceCandidate
        self.onDataChannel = onDataChannel
        self.onRemoteVideoTrack = onRemoteVideoTrack
        self.onConnectionStateChange = onConnectionStateChange
    }

    func peerConnection(_: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        Logger.debug("PeerAPI: Data channel opened: \(dataChannel.label)")
        DispatchQueue.main.async { [weak self] in self?.onDataChannel(dataChannel) }
    }

    func peerConnection(_: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        Logger.debug("PeerAPI: Media stream added: \(stream)")
    }

    func peerConnection(_: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        Logger.debug("PeerAPI: Media stream removed: \(stream)")
    }

    func peerConnection(_: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        guard let videoTrack = transceiver.receiver.track as? RTCVideoTrack else { return }
        videoTrack.isEnabled = true
        Logger.info("PeerAPI: remote video track received: \(videoTrack.trackId)")
        DispatchQueue.main.async { [weak self] in self?.onRemoteVideoTrack(videoTrack) }
    }

    func peerConnectionShouldNegotiate(_: RTCPeerConnection) {
        Logger.debug("PeerAPI: Renegotiation needed")
    }

    func peerConnection(_: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Logger.debug("PeerAPI: ICE connection state changed: \(newState)")
    }

    func peerConnection(_: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        Logger.debug("PeerAPI: ICE gathering state changed: \(newState)")
    }

    func peerConnection(_: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        Logger.debug("PeerAPI: ICE signaling state changed: \(stateChanged)")
    }

    func peerConnection(_: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Logger.debug("PeerAPI: ICE candidate: \(candidate)")
        DispatchQueue.main.async { [weak self] in self?.onIceCandidate(candidate) }
    }

    func peerConnection(_: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {
        Logger.debug("PeerAPI: ICE candidates removed: \(candidates)")
    }

    func peerConnection(_: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Logger.debug("PeerAPI: Peer connection state changed: \(newState.rawValue)")
        DispatchQueue.main.async { [weak self] in self?.onConnectionStateChange(newState) }
    }
}
