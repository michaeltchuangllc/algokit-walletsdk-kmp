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
    var dataChannel: RTCDataChannel?

    // MARK: - Viewer-side payment DataChannel
    // When Android is the HOST it creates a separate "x402-payment-channel" DC (just like
    // iOS hosts do for Android viewers).  We keep it separate from `dataChannel` so that
    // `sendMessage` / keep-alive always use the main "liquid" DC while payment messages
    // are routed through `sendPaymentMessage` → this property.
    var paymentDataChannel: RTCDataChannel?

    /// Called once when the remote peer's "x402-payment-channel" DataChannel becomes open.
    /// Set from `LiquidAuthService` to wire up `App_iosKt.setViewerPaymentSendMessageHandler`.
    var onPaymentDataChannelReady: ((RTCDataChannel) -> Void)?

    private var peerConnection: RTCPeerConnection?
    private var dataChannelDelegates: [RTCDataChannel: DataChannelDelegate] = [:]

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
        // Initialize the SignalClient
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
        stopKeepAlive()
        signalClient?.disconnectSocket()
        signalClient = nil
        peerClient = nil
        dataChannel = nil
        paymentDataChannel = nil
        onPaymentDataChannelReady = nil
        peerConnection = nil
        delegate?.signalService(self, didReceiveStatusUpdate: "Signal Service", message: "Service stopped.")
    }

    /// Disconnects from the signaling service
    func disconnect() {
        stopKeepAlive()
        paymentDataChannel = nil
        onPaymentDataChannelReady = nil
        signalClient?.disconnectSocket()
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

    /// Connects to a peer using WebRTC signaling
    ///
    /// - Parameters:
    ///   - requestId: Unique identifier for the peer connection
    ///   - type: Connection type ("offer" or "answer")
    ///   - origin: Origin domain for the connection
    ///   - iceServers: ICE servers for NAT traversal
    ///   - onMessage: Callback for received messages
    ///   - onStateChange: Callback for connection state changes
    public func connectToPeer(
        requestId: String,
        type: String,
        origin: String,
        iceServers: [RTCIceServer],
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void
    ) {
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

    /// Sends a message through the data channel
    ///
    /// - Parameter message: The message to send
    public func sendMessage(_ message: String) {
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
    ///
    /// Used by the Kotlin viewer side (`iosViewerPaymentDCSendMessageHandler`) to deliver
    /// `segment:payment` responses on the correct channel when Android is the host.
    /// Falls back silently (no queue) — payment messages are only meaningful while the
    /// payment DC is open.
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

    /// Creates a secondary DataChannel on the existing peer connection.
    ///
    /// The iOS host (offerer) calls this after the primary "liquid" DC opens to
    /// create the "x402-payment-channel" DC that Android viewers expect.  WebRTC
    /// automatically negotiates the new channel with the remote peer via the
    /// established SCTP association — no extra signalling required.
    ///
    /// - Parameters:
    ///   - label: The DataChannel label (e.g. "x402-payment-channel").
    ///   - onMessage: Called for every text message received on this channel.
    ///   - onStateChange: Called when the channel's ready-state changes.
    /// - Returns: The created `RTCDataChannel`, or `nil` if the peer connection
    ///   is not yet available.
    public func createAdditionalDataChannel(
        label: String,
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void
    ) -> RTCDataChannel? {
        guard let peerConnection = peerConnection else {
            Logger.error("createAdditionalDataChannel: peerConnection is nil — call connectToPeer first")
            return nil
        }
        let config = RTCDataChannelConfiguration()
        config.isNegotiated = false
        guard let dc = peerConnection.dataChannel(forLabel: label, configuration: config) else {
            Logger.error("createAdditionalDataChannel: failed to create DC with label '\(label)'")
            return nil
        }
        // Pass signalService: nil so the delegate does NOT overwrite self.dataChannel
        // (which should always point to the primary "liquid" DC).
        let delegate = DataChannelDelegate(
            signalService: nil,
            onMessage: onMessage,
            onStateChange: onStateChange
        )
        dc.delegate = delegate
        dataChannelDelegates[dc] = delegate
        Logger.info("createAdditionalDataChannel: created '\(label)' (id=\(dc.channelId))")
        return dc
    }
}
