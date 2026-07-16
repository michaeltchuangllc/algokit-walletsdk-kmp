import CoreImage
import Foundation
import SocketIO
import WebRTC

// MARK: - SignalClient

public class SignalClient {
    private let manager: SocketManager
    private let socket: SocketIOClient
    weak var service: SignalService?
    private var sdpHandler: ((String) -> Void)?
    var peerClient: PeerApi?
    private var candidatesBuffer: [RTCIceCandidate] = []
    private var eventQueue: [(String, QueuedEventData)] = []
    private var dataChannelDelegates: [RTCDataChannel: DataChannelDelegate] = [:]
    var onSocketConnected: (() -> Void)?

    init(url: String, service: SignalService) {
        self.service = service

        // Strip protocol if present to avoid double https://
        let cleanUrl = url
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
        
        // Get session cookies for Socket.IO connection
        var socketConfig: SocketIOClientConfiguration = [.log(false), .compress]
        
        // Add cookies from HTTPCookieStorage to Socket.IO connection
        if let url = URL(string: "https://\(cleanUrl)"),
           let cookies = HTTPCookieStorage.shared.cookies(for: url) {
            Logger.debug("SignalClient: Configuring Socket.IO with \(cookies.count) cookie(s)")
            for cookie in cookies {
                Logger.debug("  - \(cookie.name)=\(cookie.value.prefix(10))...")
            }
            
            // Convert cookies to headers format for Socket.IO
            if !cookies.isEmpty {
                let cookieHeader = cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
                socketConfig.insert(.extraHeaders(["Cookie": cookieHeader]))
                Logger.info("SignalClient: Cookie header configured: \(cookieHeader.prefix(50))...")
            }
        } else {
            Logger.info("SignalClient: No cookies found for \(cleanUrl) - WebRTC connection may fail")
        }
        
        // Initialize the Socket.IO manager and client with cookie support
        manager = SocketManager(socketURL: URL(string: "https://\(cleanUrl)")!, config: socketConfig)
        socket = manager.defaultSocket

        // Set up event listeners
        setupSocketListeners()
    }

    // swiftlint:disable:next function_body_length
    public func connectToPeer(
        requestId: String,
        type: String,
        iceServers: [RTCIceServer],
        enableMedia: Bool,
        onDataChannelOpen: @escaping (RTCDataChannel) -> Void,
        onRemoteVideoTrack: @escaping (RTCVideoTrack) -> Void,
        onMessage: @escaping (String) -> Void,
        onStateChange: @escaping (String?) -> Void
    ) -> RTCDataChannel? {
        // Clean up any existing peer connection
        peerClient?.close()
        peerClient = nil

        Logger.debug("SignalClient: Attempting to connect to peer with requestId: \(requestId), type: \(type)")

        peerClient = PeerApi(
            iceServers: iceServers,
            poolSize: 10,
            signalService: service,
            enableMedia: enableMedia,
            onDataChannel: { [weak self] dataChannel in
                Logger.debug("SignalClient: onDataChannel called with: \(dataChannel.label)")
                let isPaymentChannel = dataChannel.label == "x402-payment-channel"

                // ── Payment DC (created by Android host) ─────────────────────────────
                // Use a stripped-down delegate: forward messages but NEVER overwrite
                // service.dataChannel (signalService: nil) and NEVER propagate state
                // changes upward (would retrigger sendCredentialMessage in the host path).
                if isPaymentChannel {
                    Logger.info("SignalClient: 💳 payment DC '\(dataChannel.label)' received — setting up isolated delegate")
                    let paymentDelegate = DataChannelDelegate(
                        signalService: nil,   // ← prevents service.dataChannel overwrite
                        onMessage: { message in
                            Logger.debug("💬 SignalClient [payment DC]: \(message.prefix(80))")
                            onMessage(message)  // forward to messageForwardingHandler
                        },
                        onStateChange: { state in
                            Logger.debug("SignalClient [payment DC]: state=\(state ?? "nil")")
                            if state == "open" {
                                // Route through onDataChannelOpen which checks the label
                                // and saves to SignalService.paymentDataChannel.
                                onDataChannelOpen(dataChannel)
                            }
                            // Do NOT call outer onStateChange — that would retrigger
                            // sendCredentialMessage and re-set iosViewerSendMessageHandler.
                        }
                    )
                    dataChannel.delegate = paymentDelegate
                    self?.dataChannelDelegates[dataChannel] = paymentDelegate
                    if dataChannel.readyState == .open {
                        onDataChannelOpen(dataChannel)
                    }
                    return
                }

                // ── Main "liquid" DC (or any other remote-created DC) ────────────────
                Logger.debug("Received data channel from remote peer: \(dataChannel.label)")
                let delegate = DataChannelDelegate(
                    signalService: self?.service,
                    onMessage: { message in
                        onMessage(message)
                    },
                    onStateChange: { state in
                        Logger.debug("SignalClient: Data channel state changed: \(state ?? "unknown")")
                        onStateChange(state)
                        if state == "open" {
                            Logger.info("✅ SignalClient: Open and ready: \(dataChannel.label)")
                            onDataChannelOpen(dataChannel)
                        }
                    },
                    onChannelAvailable: { [weak self] channel in
                        if self?.service?.dataChannel !== channel {
                            Logger
                                .debug(
                                    "SignalClient: Setting dataChannel from " +
                                        "didReceiveMessageWith: \(ObjectIdentifier(channel))"
                                )
                            self?.service?.dataChannel = channel
                        }
                    }
                )
                dataChannel.delegate = delegate
                self?.dataChannelDelegates[dataChannel] = delegate
                Logger.debug("SignalClient: DataChannelDelegate assigned to remote data channel: \(dataChannel.label)")

                if dataChannel.readyState == .open {
                    Logger.info("✅ SignalClient: Open and ready (immediate): \(dataChannel.label)")
                    onDataChannelOpen(dataChannel)
                    onStateChange("open")
                }
            },
            onRemoteVideoTrack: onRemoteVideoTrack,
            onIceCandidate: { [weak self] candidate in
                guard let self else { return }
                Logger.debug("Generated ICE candidate: \(candidate)")
                let candidateEvent = (type == "offer") ? "answer-candidate" : "offer-candidate"
                send(event: candidateEvent, data: [
                    "candidate": candidate.sdp,
                    "sdpMid": candidate.sdpMid ?? "",
                    "sdpMLineIndex": candidate.sdpMLineIndex,
                ])
            }
        )

        if peerClient?.peerConnection != nil {
            Logger.info("SignalClient: Peer connection created successfully.")
        } else {
            Logger.error("SignalClient: Failed to create peer connection!")
        }

        if type == "answer" {
            // Initiator logic (creates and sends offer)
            Logger.info("Answer (initiator): sending link request")
            send(event: "link", data: ["requestId": requestId])

            guard let peerClient, peerClient.peerConnection != nil else {
                Logger.error("PeerClient or its peerConnection is nil!")
                return nil
            }

            var createdDataChannel: RTCDataChannel?
            let wrappedOnStateChange: (String?) -> Void = { [weak self] state in
                if state == "open", let dc = createdDataChannel {
                    Logger.info("Answer (initiator): self-created 'liquid' DC open — calling onDataChannelOpen to set SignalService.dataChannel")
                    onDataChannelOpen(dc)
                }
                onStateChange(state)
            }

            let dataChannel = peerClient.createDataChannel(
                label: "liquid",
                onMessage: onMessage,
                onStateChange: wrappedOnStateChange
            )
            createdDataChannel = dataChannel

            peerClient.createOffer { offer in
                guard let offer else {
                    Logger.error("Failed to create offer: Offer is nil")
                    return
                }
                Logger.info("Answer (initiator): Setting local description")
                peerClient.setLocalDescription(offer) { error in
                    if let error {
                        Logger.error("Failed to set local description: \(error)")
                    } else {
                        Logger.debug("Answer (initiator): Sending offer description")
                        self.send(event: "offer-description", sdp: offer.sdp)
                    }
                }
            }
            return dataChannel
        } else if type == "offer" {
            // Responder logic (waits for offer, then sends answer)
            Logger.info("Offer (responder): Waiting for remote offer")
            send(event: "link", data: ["requestId": requestId])

            socket.off("offer-description")
            socket.on("offer-description") { [weak self] data, _ in
                guard let self else { return }
                let sessionDescription: RTCSessionDescription
                if let eventData = data.first as? [String: Any],
                   let sdp = eventData["sdp"] as? String,
                   let sdpType = sdpType(from: eventData["type"] as? String) {
                    Logger.info("Offer (responder): Received SDP (dict) type: \(sdpType) : \(sdp.prefix(80))")
                    sessionDescription = RTCSessionDescription(type: sdpType, sdp: sdp)
                } else if let rawSdp = data.first as? String, !rawSdp.isEmpty {
                    // iOS wallet sends the raw SDP string with no dict wrapper
                    Logger.info("Offer (responder): Received SDP (string) : \(rawSdp.prefix(80))")
                    sessionDescription = RTCSessionDescription(type: .offer, sdp: rawSdp)
                } else {
                    Logger.error("Offer (responder): unrecognised offer-description payload: \(data)")
                    return
                }

                peerClient?.setRemoteDescription(sessionDescription, completion: { error in
                    if let error {
                        Logger.error("Failed to set remote description: \(error)")
                    } else {
                        Logger.info("Offer (responder): Remote description set successfully.")

                        self.peerClient?.createAnswer { answer in
                            guard let answer else {
                                Logger.error("Failed to create answer: Answer is nil")
                                return
                            }
                            Logger.info("Offer (responder): Setting local description")
                            self.peerClient?.setLocalDescription(answer) { error in
                                if let error {
                                    Logger.error("Failed to set local description: \(error)")
                                } else {
                                    Logger.info("Offer (responder): Sending answer description")
                                    self
                                        .send(event: "answer-description",
                                              sdp: answer
                                                  .sdp) // ["type": stringFromSdpType(answer.type), "sdp": answer.sdp])
                                }
                            }
                        }
                    }
                })
            }
            return nil
        }
        return nil
    }

    // MARK: - Connect to the Socket.IO Server

    func connectSocket() {
        if socket.status != .connected {
            Logger.debug("Socket is not connected. Attempting to connect...")
            socket.connect()
        } else {
            Logger.debug("Socket is already connected.")
        }
    }

    func disconnectSocket() {
        socket.disconnect()
        handleDisconnect()
    }

    private func handleDisconnect() {
        Logger.debug("Handling Socket.IO disconnection...")
        peerClient?.close()
        peerClient = nil
    }

    // MARK: - Set Up Socket.IO Listeners

    private func setupSocketListeners() {
        socket.on(clientEvent: .connect) { _, _ in
            Logger.debug("Socket.IO connected")
            self.onSocketConnected?()
            self.processEventQueue()
        }

        socket.on(clientEvent: .disconnect) { _, _ in
            Logger.debug("Socket.IO disconnected")
            self.handleDisconnect()
        }

        // FIXED: When we are "answer", we listen for "offer-description" from the browser
        // When we are "offer", we listen for "answer-description" from the browser
        if service?.currentPeerType == "answer" {
            socket.on("offer-description") { [weak self] data, _ in
                guard let self, let eventData = data.first as? [String: Any] else { return }
                Logger.debug("Received SDP offer: \(eventData)")
                handleOfferDescription(eventData)
            }
        }

        socket.on("answer-description") { [weak self] data, _ in
            guard let self else { return }
            // Try to handle as dictionary first, then as string
            if let eventData = data.first as? [String: Any] {
                Logger.debug("Received SDP answer as dictionary: \(eventData)")
                handleAnswerDescription(eventData)
            } else if let sdp = data.first as? String {
                Logger.debug("Received SDP answer as string: \(sdp)")
                handleAnswerDescription(sdp)
            } else {
                Logger.error("Received SDP answer in unknown format: \(data)")
            }
        }

        socket.on("candidate") { [weak self] data, _ in
            guard let self, let eventData = data.first as? [String: Any] else { return }
            Logger.debug("Received ICE candidate: \(eventData)")
            handleIceCandidate(eventData)
        }

        socket.on("offer-candidate") { [weak self] data, _ in
            guard let self, let eventData = data.first as? [String: Any] else { return }
            Logger.debug("Received offer ICE candidate: \(eventData)")
            handleIceCandidate(eventData)
        }
        socket.on("answer-candidate") { [weak self] data, _ in
            guard let self, let eventData = data.first as? [String: Any] else { return }
            Logger.debug("Received answer ICE candidate: \(eventData)")
            handleIceCandidate(eventData)
        }

        socket.on("link-response") { data, _ in
            Logger.debug("Received link response: \(data)")
        }

        socket.on("error") { data, _ in
            Logger.error("Socket.IO error: \(data)")
        }
    }

    // MARK: - Handle WebSocket Messages

    private func handleOfferDescription(_ data: [String: Any]) {
        guard let sdp = data["sdp"] as? String,
              let type = sdpType(from: data["type"] as? String)
        else {
            Logger.error("Received SDP is missing or invalid.")
            return
        }

        Logger.debug("handleOfferDescription: Received SDP: \(type) :  \(sdp)")
        let sessionDescription = RTCSessionDescription(type: type, sdp: sdp)

        if peerClient?.peerConnection?.signalingState == .haveLocalOffer {
            Logger.error("HandleOfferDescription: cannot set remote offer while in have-local-offer state")
            return
        }

        Logger.debug("Setting remote description with session description: \(sessionDescription)")

        peerClient?.setRemoteDescription(sessionDescription, completion: { error in
            if let error {
                Logger.error("Failed to set remote description: \(error)")
            } else {
                Logger.debug("Remote description set successfully.")
                self.processBufferedCandidates()
                self.peerClient?.createAnswer { answer in
                    guard let answer else {
                        Logger.error("Failed to create answer: Answer is nil")
                        return
                    }
                    self.peerClient?.setLocalDescription(answer) { error in
                        if let error {
                            Logger.error("Failed to set local description: \(error)")
                        } else {
                            Logger.debug("Local description set successfully.")
                            self.socket.emit("answer-description", ["sdp": answer.sdp])
                        }
                    }
                }
            }
        })
    }

    private func handleAnswerDescription(_ data: [String: Any]) {
        guard let sdp = data["sdp"] as? String,
              let type = sdpType(from: data["type"] as? String)
        else {
            Logger.error("Received SDP is missing or invalid.")
            return
        }
        Logger.debug("handleAnswerDescription: Received SDP: \(type) : \(sdp)")
        let sessionDescription = RTCSessionDescription(type: type, sdp: sdp)

        if peerClient?.peerConnection?.signalingState != .haveLocalOffer {
            Logger.error("Cannot set remote answer unless in have-local-offer state")
            return
        }

        peerClient?.setRemoteDescription(sessionDescription, completion: { error in
            if let error {
                Logger.error("Failed to set remote description: \(error)")
            } else {
                self.processBufferedCandidates()
            }
        })
    }

    private func handleAnswerDescription(_ sdp: String) {
        // If you know this is always an answer, you can hardcode the type
        let sessionDescription = RTCSessionDescription(type: .answer, sdp: sdp)

        if peerClient?.peerConnection?.signalingState != .haveLocalOffer {
            Logger.error("Cannot set remote answer unless in have-local-offer state")
            return
        }

        Logger.debug("handleAnswerDescription SDP: Setting remote description with session description.")
        peerClient?.setRemoteDescription(sessionDescription, completion: { error in
            if let error {
                Logger.error("Failed to set remote description: \(error)")
            } else {
                self.processBufferedCandidates()
            }
        })
    }

    private func handleIceCandidate(_ data: [String: Any]) {
        guard let candidate = data["candidate"] as? String,
              let sdpMid = data["sdpMid"] as? String,
              let sdpMLineIndex = data["sdpMLineIndex"] as? Int else { return }
        let iceCandidate = RTCIceCandidate(sdp: candidate, sdpMLineIndex: Int32(sdpMLineIndex), sdpMid: sdpMid)
        Logger.debug("Adding ICE candidate: \(iceCandidate)")

        if let peerConnection = peerClient?.peerConnection {
            // Only add if remote description is set
            if peerConnection.remoteDescription != nil {
                peerConnection.add(iceCandidate, completionHandler: { error in
                    if let error {
                        Logger.error("handleIceCandidate: Failed to add ICE candidate: \(error)")
                    } else {
                        Logger.debug("handleIceCandidate: ICE candidate added successfully.")
                    }
                })
            } else {
                Logger.debug("Remote description not set yet, buffering ICE candidate.")
                candidatesBuffer.append(iceCandidate)
            }
        } else {
            candidatesBuffer.append(iceCandidate)
        }
    }

    // Process buffered ICE candidates once the peer connection is ready
    private func processBufferedCandidates() {
        guard let peerConnection = peerClient?.peerConnection else { return }
        for iceCandidate in candidatesBuffer {
            peerConnection.add(iceCandidate, completionHandler: { error in
                if let error {
                    Logger.error("processBufferedCandidates: Failed to add ICE candidate: \(error)")
                } else {
                    Logger.debug("processBufferedCandidates: ICE candidate added successfully.")
                }
            })
        }
        candidatesBuffer.removeAll()
    }

    // MARK: - Send Events to the Server, wth Swift Dictionary/JSON Encoding

    func send(event: String, data: [String: Any]) {
        if socket.status == .connected {
            Logger.debug("Emitting event immediately: \(event) with data: \(data)")
            socket.emit(event, data)
        } else {
            Logger.debug("Socket not connected. Queuing event: \(event)")
            eventQueue.append((event, .dictionary(data)))
        }
    }

    // Send event with data as a pure string
    func send(event: String, sdp: String) {
        if socket.status == .connected {
            Logger.debug("Emitting event immediately: \(event) with SDP string")
            socket.emit(event, sdp)
        } else {
            Logger.debug("Socket not connected. Queuing event: \(event)")
            eventQueue.append((event, .string(sdp)))
        }
    }

    private func processEventQueue() {
        guard socket.status == .connected else { return }
        Logger.debug("Processing event queue. Number of queued events: \(eventQueue.count)")
        for (event, data) in eventQueue {
            switch data {
            case let .dictionary(dict):
                Logger.debug("Emitting queued event: \(event) with data: \(dict)")
                socket.emit(event, dict)
            case let .string(sdp):
                Logger.debug("Emitting queued event: \(event) with SDP string")
                socket.emit(event, sdp)
            }
        }
        eventQueue.removeAll()
    }
}

private func sdpType(from typeString: String?) -> RTCSdpType? {
    switch typeString {
    case "offer": .offer
    case "answer": .answer
    case "pranswer": .prAnswer
    case "rollback": .rollback
    default: nil
    }
}

private func stringFromSdpType(_ type: RTCSdpType) -> String {
    switch type {
    case .offer: return "offer"
    case .answer: return "answer"
    case .prAnswer: return "pranswer"
    case .rollback: return "rollback"
    @unknown default: return ""
    }
}

// MARK: - QueuedEventData

private enum QueuedEventData {
    case dictionary([String: Any])
    case string(String)
}
