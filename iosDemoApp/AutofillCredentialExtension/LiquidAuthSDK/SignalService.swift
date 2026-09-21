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

import Foundation
import WebRTC
import sharedDemoApp

// MARK: - SignalServiceDelegate

protocol SignalServiceDelegate: AnyObject {
    func signalService(_ service: SignalService, didReceiveStatusUpdate title: String, message: String)
}

// MARK: - SignalService

public class SignalService {
    public static let shared = SignalService()

    weak var delegate: SignalServiceDelegate?
    private var signalClient: SignalClient?
    private var peerClient: PeerApi?
    final class HostViewer {
        let requestId: String
        let signal: SignalClient
        var peer: PeerApi? { signal.peerClient }
        var channel: RTCDataChannel?
        var connected = false
        var pendingExpiry: Timer?
        var connectionTypeTimer: Timer?
        var connectionTypeReadInFlight = false
        var messages: [String] = []
        var additionalChannels: [RTCDataChannel: ScopedDataChannelDelegate] = [:]
        let onStateChange: (String?) -> Void

        init(requestId: String, signal: SignalClient, onStateChange: @escaping (String?) -> Void) {
            self.requestId = requestId
            self.signal = signal
            self.onStateChange = onStateChange
        }
    }

    private(set) var hostViewers: [String: HostViewer] = [:]
    private(set) var primaryHostRequestId: String?
    private var retiredHostRequestIds: Set<String> = []
    private(set) var sharedHostMedia: SharedBroadcastMedia?
    var onHostMediaCreated: ((SharedBroadcastMedia) -> Void)?
    var onHostStopped: (() -> Void)?
    var onHostViewerConnectionType: ((String, String) -> Void)?
    private var hostKeepAliveTimer: Timer?
    var hasConnectedHostViewers: Bool { hostViewers.values.contains { $0.connected } }
    static let maximumPendingHostViewers = 8
    static let pendingHostViewerLifetime: TimeInterval = 5 * 60
    var pendingHostViewerCount: Int { hostViewers.values.filter { !$0.connected }.count }
    var dataChannel: RTCDataChannel?

    var paymentDataChannel: RTCDataChannel?

    var onPaymentDataChannelReady: ((RTCDataChannel) -> Void)?
    /// App-provided hook for attaching local tracks. Kept camera-free for extension compatibility.
    var onPeerCreated: ((PeerApi) -> Void)?
    var onRemoteVideoTrack: ((RTCVideoTrack) -> Void)?
    var remoteVideoTrack: RTCVideoTrack?

    private var peerConnection: RTCPeerConnection?
    private var dataChannelDelegates: [RTCDataChannel: ScopedDataChannelDelegate] = [:]

    private var messageQueue: [String] = []
    private var keepAliveTimer: Timer?

    private var lastKnownReferer: String?
    private var isDeepLink: Bool = true

    var currentPeerType: String? // "offer" or "answer"

    private init() { }

    // MARK: - Public Methods

    /// Starts the signaling service
    ///
    /// - Parameters:
    ///   - url: The signaling server URL
    ///   - httpClient: URLSession for HTTP communications
    public func start(url: String, httpClient _: URLSession) {
        guard sharedHostMedia == nil else {
            Logger.error("Stop hosting before starting the legacy viewer service.")
            return
        }
        // Initialize the SignalClient
        signalClient?.disconnectSocket()
        signalClient = SignalClient(url: url, service: self)
        signalClient?.connectSocket()
        delegate?.signalService(
            self,
            didReceiveStatusUpdate: "Signal Service",
            message: "Service started successfully."
        )
    }

    /// Stops the signaling service and cleans up resources
    func stop() {
        stopHosting()
        stopKeepAlive()
        signalClient?.disconnectSocket()
        signalClient = nil
        peerClient = nil
        dataChannel = nil
        paymentDataChannel = nil
        onPaymentDataChannelReady = nil
        remoteVideoTrack = nil
        onRemoteVideoTrack = nil
        peerConnection = nil
        messageQueue.removeAll()
        closeAdditionalDataChannels()
        delegate?.signalService(self, didReceiveStatusUpdate: "Signal Service", message: "Service stopped.")
    }

    /// Disconnects from the signaling service
    func disconnect() {
        stopKeepAlive()
        // Explicit legacy disconnect, unlike a transient Socket.IO disconnect.
        // Viewer dismissal must not stop a separately retained host camera.
        signalClient?.disconnectSocket()
        signalClient = nil
        remoteVideoTrack = nil
        onRemoteVideoTrack = nil
        if primaryHostRequestId == nil {
            closeAdditionalDataChannels()
            peerClient = nil
            peerConnection = nil
            dataChannel = nil
            paymentDataChannel = nil
            onPaymentDataChannelReady = nil
            messageQueue.removeAll()
        }
        delegate?.signalService(
            self,
            didReceiveStatusUpdate: "Signal Service",
            message: "Disconnected from the signaling server."
        )
    }

    // MARK: - Check if the signaling service is initialized

    var isPeerClientInitialized: Bool {
        peerClient != nil
    }

    /// Add one unique invitation without replacing the primary/paid legacy slot.
    /// Main-thread API; the Kotlin bridge dispatches here before invoking it.
    func connectHostViewer(
        requestId: String,
        origin: String,
        iceServers: [RTCIceServer],
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void
    ) {
        guard !requestId.isEmpty, hostViewers[requestId] == nil,
              !retiredHostRequestIds.contains(requestId) else { return }
        guard pendingHostViewerCount < Self.maximumPendingHostViewers else {
            retiredHostRequestIds.insert(requestId)
            onStateChange("pending-limit")
            return
        }
        if sharedHostMedia == nil {
            let media = SharedBroadcastMedia()
            sharedHostMedia = media
            onHostMediaCreated?(media)
        }
        let client = SignalClient(url: origin, service: self)
        let viewer = HostViewer(requestId: requestId, signal: client, onStateChange: onStateChange)
        hostViewers[requestId] = viewer
        viewer.pendingExpiry = Timer.scheduledTimer(
            withTimeInterval: Self.pendingHostViewerLifetime, repeats: false
        ) { [weak self, weak viewer] _ in
            guard let self, let viewer, self.hostViewers[requestId] === viewer,
                  !viewer.connected else { return }
            self.disconnectHostViewer(requestId: requestId, terminalState: "expired")
        }
        client.onSocketConnected = { [weak self, weak viewer, weak client] in
            guard let self, let viewer, let client,
                  self.hostViewers[requestId] === viewer else { return }
            _ = client.connectToPeer(
                requestId: requestId,
                type: "offer",
                iceServers: iceServers,
                enableMedia: true,
                sharedMedia: self.sharedHostMedia,
                onConnectionStateChange: { [weak self, weak viewer] state in
                    guard let self, let viewer, self.hostViewers[requestId] === viewer else { return }
                    // A transient ICE disconnect may recover; terminal failure cannot.
                    if state == .failed || state == .closed {
                        self.disconnectHostViewer(
                            requestId: requestId, terminalState: state == .failed ? "failed" : "closed"
                        )
                    }
                },
                onDataChannelOpen: { [weak self, weak viewer] channel in
                    guard let self, let viewer, self.hostViewers[requestId] === viewer,
                          channel.label == "liquid" else { return }
                    // SignalClient invokes this before its open-state callback. Reserve
                    // the first CONNECTED invitation and publish all legacy slots together,
                    // before any app/Kotlin callback can create a payment channel or send.
                    if self.primaryHostRequestId == nil { self.primaryHostRequestId = requestId }
                    viewer.channel = channel
                    viewer.pendingExpiry?.invalidate()
                    viewer.pendingExpiry = nil
                    if requestId == self.primaryHostRequestId {
                        self.peerClient = viewer.peer
                        self.peerConnection = viewer.peer?.peerConnection
                        self.dataChannel = channel
                    }
                    for message in viewer.messages {
                        channel.sendData(RTCDataBuffer(data: Data(message.utf8), isBinary: false))
                    }
                    viewer.messages.removeAll()
                    if requestId == self.primaryHostRequestId {
                        self.flushMessageQueue()
                    }
                },
                onRemoteVideoTrack: { _ in },
                onMessage: { [weak self, weak viewer] message in
                    guard let self, let viewer, self.hostViewers[requestId] === viewer else { return }
                    if message == "ping" {
                        self.sendHostMessage("pong", requestId: requestId)
                    } else if message != "pong" {
                        onMessage(message)
                    }
                },
                onStateChange: { [weak self, weak viewer] state in
                    guard let self, let viewer, self.hostViewers[requestId] === viewer else { return }
                    if state == "open", !viewer.connected {
                        viewer.connected = true
                        onStateChange(state)
                        self.startHostViewerConnectionTypePolling(viewer)
                    } else if state == "closed" || state == "failed" {
                        self.disconnectHostViewer(requestId: requestId, terminalState: state ?? "closed")
                    }
                }
            )
        }
        client.connectSocket()
        if hostKeepAliveTimer == nil {
            hostKeepAliveTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
                guard let self else { return }
                for viewer in self.hostViewers.values where viewer.connected {
                    self.sendHostMessage("ping", requestId: viewer.requestId)
                }
            }
        }
    }

    func sendHostMessage(_ message: String, requestId: String) {
        guard let viewer = hostViewers[requestId] else { return }
        if let channel = viewer.channel, channel.readyState == .open {
            channel.sendData(RTCDataBuffer(data: Data(message.utf8), isBinary: false))
        } else if viewer.messages.count < 256 {
            viewer.messages.append(message)
        }
    }

    private func startHostViewerConnectionTypePolling(_ viewer: HostViewer) {
        guard hostViewers[viewer.requestId] === viewer, viewer.connected else { return }
        viewer.connectionTypeTimer?.invalidate()
        viewer.connectionTypeTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) {
            [weak self, weak viewer] _ in
            guard let self, let viewer else { return }
            self.readHostViewerConnectionType(viewer)
        }
        readHostViewerConnectionType(viewer)
    }

    private func readHostViewerConnectionType(_ viewer: HostViewer) {
        guard hostViewers[viewer.requestId] === viewer, viewer.connected,
              !viewer.connectionTypeReadInFlight,
              let connection = viewer.peer?.peerConnection else { return }
        viewer.connectionTypeReadInFlight = true
        connection.statistics { [weak self, weak viewer, weak connection] report in
            DispatchQueue.main.async {
                guard let self, let viewer, let connection,
                      self.hostViewers[viewer.requestId] === viewer,
                      viewer.peer?.peerConnection === connection, viewer.connected else { return }
                viewer.connectionTypeReadInFlight = false
                self.onHostViewerConnectionType?(
                    viewer.requestId, Self.selectedConnectionType(report)
                )
            }
        }
    }

    /// Classifies the selected pair by delegating to the single shared implementation in
    /// `IceConnectionTypeClassifier.kt` (wallet-sdk-core), so Android and iOS can never disagree
    /// on the quality (and therefore x402-style billing tier) of the same connection. This only
    /// adapts WebRTC's native `RTCStatisticsReport` into the shared, engine-agnostic model.
    private static func selectedConnectionType(_ report: RTCStatisticsReport) -> String {
        let stats = report.statistics

        let transports = stats.values
            .filter { $0.type == "transport" }
            .map { IceTransportStat(selectedCandidatePairId: $0.values["selectedCandidatePairId"] as? String) }

        func candidateType(_ candidateId: Any?) -> String? {
            guard let candidateId = candidateId as? String else { return nil }
            return stats[candidateId]?.values["candidateType"] as? String
        }

        let candidatePairs = stats.compactMap { id, entry -> IceCandidatePairStat? in
            guard entry.type == "candidate-pair" else { return nil }
            return IceCandidatePairStat(
                id: id,
                state: entry.values["state"] as? String,
                isSelectedOrNominated: (entry.values["selected"] as? NSNumber)?.boolValue == true ||
                    (entry.values["nominated"] as? NSNumber)?.boolValue == true,
                localCandidateType: candidateType(entry.values["localCandidateId"]),
                remoteCandidateType: candidateType(entry.values["remoteCandidateId"])
            )
        }

        return App_iosKt.classifyIceConnectionType(transports: transports, candidatePairs: candidatePairs)
    }

    func disconnectHostViewer(requestId: String, terminalState: String = "closed") {
        guard let viewer = hostViewers.removeValue(forKey: requestId) else { return }
        viewer.pendingExpiry?.invalidate()
        viewer.pendingExpiry = nil
        viewer.connectionTypeTimer?.invalidate()
        viewer.connectionTypeTimer = nil
        viewer.connected = false
        retiredHostRequestIds.insert(requestId)
        for (channel, delegate) in viewer.additionalChannels {
            delegate.invalidate()
            channel.delegate = nil
            channel.close()
        }
        viewer.additionalChannels.removeAll()
        if requestId == primaryHostRequestId {
            // Never promote a free mesh peer into the legacy paid slot.
            closeAdditionalDataChannels()
            peerClient = nil
            peerConnection = nil
            dataChannel = nil
            paymentDataChannel = nil
            messageQueue.removeAll()
        }
        viewer.signal.disconnectSocket()
        viewer.messages.removeAll()
        viewer.channel = nil
        viewer.onStateChange(terminalState)
        // Deliberately retain sharedHostMedia and the camera even at zero viewers.
    }

    func stopHosting() {
        hostKeepAliveTimer?.invalidate()
        hostKeepAliveTimer = nil
        for requestId in Array(hostViewers.keys) {
            disconnectHostViewer(requestId: requestId)
        }
        primaryHostRequestId = nil
        retiredHostRequestIds.removeAll()
        onHostStopped?()
        sharedHostMedia = nil
    }

    /// Connects to a peer using WebRTC signaling
    public func connectToPeer(
        requestId: String,
        type: String,
        origin: String,
        iceServers: [RTCIceServer],
        enableMedia: Bool = false,
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void
    ) {
        guard sharedHostMedia == nil else {
            Logger.error("Stop hosting before entering the legacy single-viewer path.")
            onStateChange("failed")
            return
        }
        stopKeepAlive()
        closeAdditionalDataChannels()
        dataChannel = nil
        paymentDataChannel = nil
        remoteVideoTrack = nil
        peerClient = nil
        peerConnection = nil
        currentPeerType = type

        signalClient?.disconnectSocket()
        signalClient = nil

        Logger.debug("Attempting to connect to peer with requestId: \(requestId), type: \(type)")

        // Ensure the socket is connected
        signalClient = SignalClient(url: origin, service: self)

        // Wait for socket connection before starting signaling
        signalClient?.onSocketConnected = { [weak self] in
            guard let self else { return }
            Logger.debug("Socket connected, now starting WebRTC signaling.")
            _ = signalClient?.connectToPeer(
                requestId: requestId,
                type: type,
                iceServers: iceServers,
                enableMedia: enableMedia,
                onPeerCreated: { [weak self] peer in
                    if enableMedia, type == "offer" { self?.onPeerCreated?(peer) }
                },
                onDataChannelOpen: { [weak self] dataChannel in
                    guard let self else { return }
                    Logger.debug("SignalService: onDataChannelOpen called with: \(dataChannel.label)")

                    // ── Payment DC (created by Android host) ─────────────────────────
                    // Keep it separate so the main "liquid" DC reference and keep-alive
                    // timer are never overwritten.
                    if dataChannel.label == "x402-payment-channel" {
                        Logger.info("SignalService: 💳 payment DC '\(dataChannel.label)' open — wiring viewer payment handler")
                        self.paymentDataChannel = dataChannel
                        self.onPaymentDataChannelReady?(dataChannel)
                        return
                    }

                    // ── Main "liquid" DC ─────────────────────────────────────────────
                    self.dataChannel = dataChannel
                    Logger.debug("Data channel is open and ready: \(dataChannel.label)")
                    if dataChannel.readyState == .open {
                        self.flushMessageQueue()
                        // Start continuous keep-alive mechanism
                        self.startKeepAlive()
                    }
                },
                onRemoteVideoTrack: { [weak self] track in
                    self?.remoteVideoTrack = track
                    self?.onRemoteVideoTrack?(track)
                },
                onMessage: { message in
                    onMessage(message)
                },
                onStateChange: onStateChange
            )

            peerClient = signalClient?.peerClient
            peerConnection = peerClient?.peerConnection

            if let peerConnection {
                Logger.debug("Peer connection state: \(peerConnection.connectionState.rawValue)")
            } else {
                Logger.error("Peer connection is nil.")
            }

            delegate?.signalService(
                self,
                didReceiveStatusUpdate: "Peer Connection",
                message: "Connected to peer with request ID: \(requestId)."
            )
        }

        signalClient?.connectSocket()
        Logger.debug("ICE servers: \(iceServers)")
        Logger.debug("Waiting for socket to connect before signaling.")
    }

    var localPeerApi: PeerApi? { peerClient }

    func setLocalAudioEnabled(_ enabled: Bool) {
        sharedHostMedia?.audioEnabled = enabled
        for viewer in hostViewers.values { viewer.peer?.setAudioEnabled(enabled) }
        peerClient?.setAudioEnabled(enabled)
    }

    func setLocalVideoEnabled(_ enabled: Bool) {
        sharedHostMedia?.videoEnabled = enabled
        sharedHostMedia?.previewTrack.isEnabled = enabled
        for viewer in hostViewers.values { viewer.peer?.setVideoEnabled(enabled) }
        peerClient?.setVideoEnabled(enabled)
    }

    func makeLocalVideoRenderer() -> RTCMTLVideoView? {
        sharedHostMedia?.makeRenderer() ?? peerClient?.makeLocalVideoRenderer()
    }

    /// Sends a message through the data channel
    ///
    /// - Parameter message: The message to send
    public func sendMessage(_ message: String) {
        if let requestId = primaryHostRequestId {
            sendHostMessage(message, requestId: requestId)
            return
        }
        if let dataChannel, dataChannel.readyState == .open {
            Logger
                .debug(
                    "SignalService: Sending on channel to \(ObjectIdentifier(dataChannel)) label: \(dataChannel.label)"
                )
            let buffer = RTCDataBuffer(data: message.data(using: .utf8)!, isBinary: false)
            dataChannel.sendData(buffer)

        } else {
            Logger.error("sendMessage: Data channel is not available. Queuing message.")
            messageQueue.append(message)
        }
    }

    /// Sends a message on the dedicated "x402-payment-channel" DataChannel.
    public func sendPaymentMessage(_ message: String) {
        if let paymentDataChannel, paymentDataChannel.readyState == .open {
            Logger.debug("SignalService: Sending on payment DC (id=\(paymentDataChannel.channelId)): \(message.prefix(80))")
            let buffer = RTCDataBuffer(data: message.data(using: .utf8)!, isBinary: false)
            paymentDataChannel.sendData(buffer)
        } else {
            Logger.error("sendPaymentMessage: payment DC not available (label=\(paymentDataChannel?.label ?? "nil") state=\(paymentDataChannel?.readyState.description ?? "nil"))")
        }
    }

    /// Flushes queued messages when the data channel becomes available
    private func flushMessageQueue() {
        guard let dataChannel else { return }
        for message in messageQueue {
            let buffer = RTCDataBuffer(data: message.data(using: .utf8)!, isBinary: false)
            dataChannel.sendData(buffer)
            Logger.info("Flushed queued message: \(message)")
        }
        messageQueue.removeAll()
    }
    
    // MARK: - Keep-Alive Management
    
    /// Starts a continuous keep-alive ping mechanism to prevent WebRTC connection timeout
    private func startKeepAlive() {
        stopKeepAlive() // Clean up any existing timer
        
        Logger.info("Starting continuous keep-alive (ping every 15 seconds)")
        
        // Send initial ping immediately
        sendMessage("ping")
        
        // Schedule repeating timer to send ping every 15 seconds
        keepAliveTimer = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if self.dataChannel?.readyState == .open {
                self.sendMessage("ping")
                Logger.info("Keep-alive ping sent")
            } else {
                Logger.info("Data channel not open, stopping keep-alive")
                self.stopKeepAlive()
            }
        }
    }
    
    /// Stops the keep-alive timer
    private func stopKeepAlive() {
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        Logger.debug("Keep-alive timer stopped")
    }

    // MARK: - Additional DataChannels

    private func closeAdditionalDataChannels() {
        for (channel, delegate) in dataChannelDelegates {
            delegate.invalidate()
            channel.delegate = nil
            channel.close()
        }
        dataChannelDelegates.removeAll()
    }

    /// Creates a secondary DataChannel on the existing peer connection.
    public func createAdditionalDataChannel(
        label: String,
        requestId: String? = nil,
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void,
        onOpen: @escaping (RTCDataChannel) -> Void = { _ in }
    ) -> RTCDataChannel? {
        let connection: RTCPeerConnection?
        if let requestId {
            connection = hostViewers[requestId]?.peer?.peerConnection
        } else {
            connection = peerConnection
        }
        guard let peerConnection = connection else {
            Logger.error("createAdditionalDataChannel: peerConnection is nil — call connectToPeer first")
            return nil
        }
        let config = RTCDataChannelConfiguration()
        config.isNegotiated = false
        guard let dc = peerConnection.dataChannel(forLabel: label, configuration: config) else {
            Logger.error("createAdditionalDataChannel: failed to create DC with label '\(label)'")
            return nil
        }
        // This observer cannot overwrite the primary "liquid" channel.
        let delegate = ScopedDataChannelDelegate(
            onMessage: onMessage,
            onState: { channel, state in
                if state == "open" { onOpen(channel) }
                onStateChange(state)
            }
        )
        dc.delegate = delegate
        if let requestId, let viewer = hostViewers[requestId] {
            viewer.additionalChannels[dc] = delegate
        } else {
            dataChannelDelegates[dc] = delegate
        }
        delegate.dataChannelDidChangeState(dc)
        Logger.info("createAdditionalDataChannel: created '\(label)' (id=\(dc.channelId))")
        return dc
    }
}
