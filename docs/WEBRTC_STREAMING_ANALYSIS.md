## WebRTC Host–Viewer Streaming: Analysis and Feature Implementation Guide

**Reviewed:** September 17, 2026  
**Branch at review:** `walletsdk-412-multiviewer`  
**Purpose:** Preserve an understanding of the current implementation before adding streaming features.

This is a static source review, focused on Android with an additional iOS integration review. No device streaming, network, or payment tests were executed. Findings describe the inspected source, not compiled build artifacts; line numbers may drift.

The requested new feature has not yet been specified. Multi-viewer guidance below is a proposed extension, not an assertion that it is already implemented or an approved requirement.

See also [Liquid Stream connection and payment sequence](LIQUID_STREAM_SEQUENCE.md). That guide provides broader protocol context; this document emphasizes implementation constraints and risks.

## 1. Executive understanding

- **Transport:** Camera video and microphone audio use native WebRTC media tracks, not the string-message data channel.
- **Topology:** The inspected Android flow represents one host and one viewer per active service session. There is no per-viewer peer registry in `SignalService` or `SignalClient`.
- **Signaling:** Socket.IO exchanges SDP descriptions and ICE candidates. Media flows through the negotiated WebRTC path, potentially using TURN, rather than through the signaling socket.
- **Roles:** The viewer creates the SDP offer; the host creates the SDP answer. The public `type` string names the remote description being awaited.
- **UI:** Compose hosts platform video renderers. Android shares an EGL context between WebRTC encoding/decoding and rendering.
- **Control:** The main `liquid` data channel handles application messages; additional channels support payment messaging.
- **Readiness:** A data-channel connection is not proof that the viewer has received or rendered a video frame.

## 2. Source map

All paths below are relative to the repository root.

| Area | File | Responsibility |
| --- | --- | --- |
| Android service | `wallet-sdk-core/src/androidMain/kotlin/com/michaeltchuang/walletsdk/core/liquidAuth/auth/connect/SignalService.kt` | Service lifecycle, one active signaling/peer client, media accessors, message forwarding, ICE classification |
| Android signaling | `wallet-sdk-core/src/androidMain/kotlin/com/michaeltchuang/walletsdk/core/liquidAuth/auth/connect/SignalClient.kt` | Socket.IO connection, link request, SDP/ICE exchange, role-dependent media setup |
| Android peer/media | `wallet-sdk-core/src/androidMain/kotlin/com/michaeltchuang/walletsdk/core/liquidAuth/auth/connect/PeerApi.kt` | Peer factory, Unified Plan connection, tracks, camera/microphone capture, channels, cleanup |
| Host orchestration | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidAuth/service/LiquidAuthConnectionManager.android.kt` | Binds service, starts host connection, payment integration, UI connection state |
| Viewer setup | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidAuth/screens/AnswerScreenOverlay.android.kt` | Starts viewer peer with media enabled for `LIQUID_AUTH_STREAM` |
| Viewer rendering | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidAuth/screens/AnswerScreen.kt` | Remote-track observation/polling, rendering and frame heartbeat |
| Host preview | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidStream/components/CameraStreamingPreview.kt` | Local-track preview and camera controls |
| Android renderer | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidStream/components/WebRtcVideoRenderer.kt` | Compose lifecycle around native renderer and track sinks |
| Android rendering surface | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidStream/components/WebRtcTextureViewRenderer.kt` | TextureView-based EGL rendering |
| ICE configuration | `wallet-sdk-ui/src/androidMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidAuth/configuration/IceServerConfig.kt` | ICE servers supplied by application integration |
| Payment gating | `wallet-sdk-core/src/commonMain/kotlin/com/michaeltchuang/walletsdk/core/railmpp/core/PaywalledRTCServer.kt` | Gates supplied RTP sender abstractions |
| iOS signaling/media | `iosDemoApp/AutofillCredentialExtension/LiquidAuthSDK/SignalService.swift`, `SignalClient.swift`, `PeerApi.swift` | Native iOS connection and track integration |
| iOS host capture | `iosDemoApp/iosApp/iosApp.swift` | Creates native camera/audio tracks and retains broadcast capturer |
| iOS KMP bridge | `wallet-sdk-ui/src/iosMain/kotlin/com/michaeltchuang/walletsdk/ui/liquidAuth/service/LiquidAuthConnectionManager.ios.kt` | Shared UI to native broadcast/viewer integration |

## 3. Connection and media lifecycle

### 3.1 Role mapping: important naming trap

| Application role | Argument to `peer()` | Remote SDP awaited | Local SDP produced | Media behavior |
| --- | --- | --- | --- | --- |
| Host / creator | `"offer"` | Offer | Answer | Captures and sends camera/microphone |
| Viewer | `"answer"` | Answer | Offer | Adds receive-only audio/video transceivers |

Evidence: `SignalClient.kt:110–118, 172–233`.

Some comments describe these roles ambiguously. Follow executable branches rather than assuming `"offer"` means the local SDP offerer. A future typed role API should preserve existing wire event semantics.

### 3.2 Host startup

1. `LiquidAuthConnectionManager.startListening()` starts and binds `SignalService`.
2. `setupSignalService()` calls `service.start()` with the signaling origin.
3. It calls `service.peer(requestId, "offer", iceServers, enableMedia = true)`.
4. `SignalClient` connects Socket.IO, constructs `PeerApi`, and performs `link(requestId)`.
5. It registers remote candidate handling and creates a Unified Plan `PeerConnection`.
6. `startLocalCapture()` creates and adds video/audio tracks before answering.
7. It awaits `offer-description`, sets the remote description, creates an answer, sets the local description, and emits `answer-description`.
8. The viewer-created data channel arrives through `onDataChannel`; this resolves the host's suspended `peer()` call.
9. `SignalService` then exposes its peer reference, EGL context, tracks, and channel to the surrounding UI.

**Consequence:** Capture can start while the host is waiting for a viewer, but the service's `peerClient` field is only assigned after `signalClient.peer()` returns (`SignalService.kt:159–161`). Initial preview and control access through service getters may therefore be unavailable during that wait.

### 3.3 Viewer startup

1. `AnswerScreenOverlay.android.kt:400–412` starts the service and calls `peer()` using `"answer"`.
2. Native media setup is enabled when `appId == AppId.LIQUID_AUTH_STREAM.name`.
3. `PeerApi.addReceiveOnlyMediaTransceivers()` requests video and audio without starting local camera/microphone capture.
4. The viewer creates the `liquid` data channel and an SDP offer.
5. It sets its local description, emits `offer-description`, and awaits `answer-description`.
6. It applies the answer and returns its data-channel reference.
7. Remote video is surfaced by `onAddTrack` / `onTrack`, saved as `remoteVideoTrack`, and forwarded to listeners.
8. `AnswerScreen` attaches the video renderer and a separate heartbeat sink for actual frame arrival.

**Consequence:** The viewer's `peer()` return does not wait for `DataChannel.State.OPEN` or the first video frame. Callers must track those separately.

### 3.4 Media configuration and rendering

- **Android capture target:** 1280 × 720 at 30 fps; actual supported capture behavior depends on the device.
- **Camera selection:** Front camera preferred, then back camera, then the first available device.
- **Encoding/decoding:** `DefaultVideoEncoderFactory` / `DefaultVideoDecoderFactory` use the shared EGL context. The inspected code does not force a single codec; actual codec selection is negotiated.
- **Audio:** Local microphone track on the host; native WebRTC handles received audio playout. Both roles configure communication mode and speakerphone routing.
- **Mute controls:** Host microphone/camera controls enable or disable local tracks. Disabling a track is not equivalent to stopping camera capture or releasing hardware.
- **Rendering:** Android attaches a `VideoSink` through `WebRtcVideoRenderer`. Track/context changes require sink and EGL lifecycle coordination.
- **Frame health:** Android viewer heartbeat uses real native frame arrival, not merely a connected channel.

### 3.5 Shutdown

`SignalService.stop()` disconnects `SignalClient`, whose `disconnect()` invokes `PeerApi.destroy()`.

`destroy()` restores audio routing, stops/disposes the camera capturer and sources, clears tracks, closes channels, closes/disposes the peer connection, and releases EGL. The service clears its references and removes its foreground notification.

This is the intended explicit stop path. It does not establish that cancellation, failed negotiation, system-driven destruction, or every native allocation is correctly handled; see findings below.

## 4. Findings to address before substantial feature work

### High: ICE buffering checks the wrong readiness condition

**Evidence:** `SignalClient.kt:120–139, 188–193, 219–227`; `PeerApi.kt:224–229`.

Candidates are buffered only while `peerConnection == null`. After the connection object exists but before remote SDP has been successfully applied, incoming candidates are passed straight to `addIceCandidate()`.

**Risk:** Early candidates can fail to apply and are not retained/retried; the return value is ignored. This can make connection establishment timing-dependent.

**Recommendation:** Queue candidates per peer and negotiation generation until remote-description success, flush once, clear the queue, and observe candidate application failures.

### High: Negotiation failures can leave callers suspended

**Evidence:** `PeerApi.kt:261–292`; `SignalClient.kt:108–109, 256–290, 312–316`.

`onSetFailure()` logs without completing the callback. SDP, link acknowledgement, and peer setup use `suspendCoroutine`; signaling work is launched in an independent scope. Disconnect does not cancel that scope or explicitly resolve pending operations.

**Risk:** Errors, absent acknowledgements, or session cancellation can leave operations waiting or allow stale callbacks to affect later state.

**Recommendation:** Use session-owned structured concurrency, cancellable suspension, bounded timeouts, exactly-once completion, and explicit error propagation. Remove socket listeners on cancellation/teardown.

### High: SDP event ordering contains race windows

**Evidence:** `SignalClient.kt:116–118, 185–186, 195–217`.

The host registers its offer listener after link/setup. The viewer emits its offer before registering its answer listener. Both branches emit SDP without waiting for local-description success.

**Risk:** Fast messages may be missed, or SDP can be announced despite local-description failure. Exact exposure depends on server ordering and runtime timing.

**Recommendation:** Register expected remote listeners before making the session discoverable or emitting a request; await successful local-description application before emitting SDP.

### High: Native media payment enforcement is not wired on Android

**Evidence:** `LiquidAuthConnectionManager.android.kt:285–287, 421–423`; `PaywalledRTCServer.kt:140–149, 179–184`.

The creator receives `rtpSenders = emptyList()`, while the paywall's media gate disables only supplied senders.

**Risk:** This gate has no native media senders to disable. Hiding viewer UI or changing payment state is not transport-level enforcement.

**Recommendation:** Wire and test media authorization at the sender/session boundary, including audio. Confirm no unauthorized frames/audio escape before payment approval; a client-side overlay is insufficient.

### High: Foreground-service activation does not match media capture

**Evidence:** `SignalService.kt:75–85`; `wallet-sdk-ui/src/androidMain/AndroidManifest.xml:33–36, 76`.

The UI manifest declares camera/microphone foreground-service support, but runtime promotion activates only `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.

**Risk:** Background camera/microphone continuity is not correctly represented by runtime service types and may fail under platform restrictions.

**Recommendation:** Select runtime types according to the active role/work, verify merged manifests and runtime permissions, and test applicable Android background-start/while-in-use restrictions. A viewer should not need local capture permissions just to receive a stream.

### Medium: Single-session state cannot support independent viewers

**Evidence:** `SignalService.kt:45–52, 129–134`; `SignalClient.kt:55–58, 112`; `LiquidAuthConnectionManager.android.kt:524–534`.

The service retains one peer/client/channel set. Starting again disconnects the previous signaling client; changing the active request ID restarts the host binding.

**Impact:** Repeating the current startup path is replacement, not fan-out. Host UI also builds one connected-viewer entry (`LiquidStreamHostLiveScreen.kt:119–148`).

### Medium: Cleanup and replacement ownership need tightening

**Evidence:** `PeerApi.kt:505–517, 553–566`; `SignalService.kt` has no `onDestroy()` override.

The factory is not explicitly disposed, channel disposal is absent/commented, and local track references are cleared without explicit disposal. Remote listeners are cleared without publishing a null-track state.

**Risk:** Repeated sessions may retain native resources or leave UI consumers referencing released resources. This is an ownership concern requiring runtime verification, not a measured leak.

**Recommendation:** Define ownership and idempotent teardown for factories, tracks, senders, channels, sources, capturers, renderers, and EGL. Verify disposal requirements of the bundled WebRTC version rather than blindly adding duplicate disposals.

### Medium: Track and EGL state can become stale across reconnects

**Evidence:** `CameraStreamingPreview.kt:194`; `AnswerScreen.kt:79–97`; `SignalService.kt:178–181`.

Host preview may remember an existing context by manager identity; viewer fills its context only when null. Listener registration before the service exposes a peer is a no-op, currently compensated by polling.

**Risk:** Replacing a peer without recreating the UI can retain an obsolete EGL context or miss state transitions.

**Recommendation:** Publish observable session-scoped track/context state, keyed by session generation. Detach renderers before releasing their tracks/context and clear listeners when UI consumers leave.

### Medium: ICE diagnostics do not identify the selected transport reliably

**Evidence:** `SignalService.kt:329–359`.

Detection walks all `succeeded` candidate pairs and classifies the local candidate only. It does not establish which pair is selected; a successful pair need not carry current traffic.

**Risk:** Reported LOCAL/STUN/RELAY status can be wrong, including when the remote side uses a relay. A `host` candidate alone does not prove same-LAN connectivity.

**Recommendation:** Follow transport `selectedCandidatePairId` where supported, inspect both candidates, and tolerate stats-schema differences.

### Additional correctness and resilience concerns

- **String equality:** `SignalClient` uses `===` for role strings. Kotlin reference identity is not content equality; use typed roles or `==`.
- **Recovery:** `PeerApi` logs ICE failure and renegotiation requests but does not implement ICE restart or renegotiation there. Define recovery ownership before adding track replacement or network-handoff features.
- **Capture failure:** `startLocalCapture()` logs `startCapture()` failure but still creates/enables a video track. Publish an explicit capture error rather than implying frames are available.
- **Channel identity:** Incoming channel selection can treat the first channel as primary regardless of label, and `createDataChannelObserver()` reads the primary channel's state. Bind observers and session readiness to the specific expected channel.
- **Threading:** WebRTC callbacks, Socket.IO callbacks, and coroutine work cross threads. Serialize session mutations and marshal UI-facing state deliberately.
- **Logging:** SDP, candidate addresses, and application payloads are logged. Redact sensitive content and reduce production verbosity.

## 5. iOS integration notes

These are additional source-review findings, not a full iOS negotiation audit.

- **Native capture ownership:** `iosApp.swift:182–215` creates `RTCCameraVideoCapturer`, adds tracks, and retains it in `broadcastVideoCapturer`. Its stop handler (`129–133`) stops `SignalService`; no stop/clear of that retained capturer was found. Verify camera release on stop/restart.
- **Payment gate bridge:** `BroadcastRtcRtpSender.ios.kt` invokes `iosBroadcastGateVideoHandler`, but the source search found only its null initialization, not assignment. Verify and wire the bridge before relying on native enforcement.
- **Frame watchdog:** `LiquidAuthConnectionManager.ios.kt` defines `notifyViewerVideoFrameReceived()`; no caller was found. Native frame delivery needs to activate/update the watchdog.
- **Controls:** The iOS camera preview clears the rotation callback rather than implementing camera switching; do not assume Android control parity.
- **Negotiation ordering:** Host track creation is bridged through `SignalService.onPeerCreated`. Validate that tracks exist before answer creation in all timing scenarios and cross-platform combinations.

## 6. Proposed foundation for new features

These are design recommendations, not current implementation.

### 6.1 Establish explicit state and ownership

Separate three concepts:

1. **Broadcast media:** Camera, microphone, sources, local preview, and media preferences.
2. **Peer session:** One remote participant, SDP/ICE state, connection, channels, senders, authorization, jobs, and teardown.
3. **Presentation:** Renderer attachment, foreground/background visibility, and UI state.

Represent connecting, negotiating, transport-connected, authorized, first-frame-received, reconnecting, failed, and closed explicitly. Do not overload one “connected” boolean.

Use a stable session identifier plus a generation identifier so callbacks from replaced sessions cannot mutate current state.

### 6.2 If the feature is multi-viewer streaming

- **Separate IDs:** A broadcast/room ID identifies the host stream; each viewer connection needs its own peer/session ID and negotiation generation.
- **Route signaling:** Every SDP/candidate exchange must resolve to a specific peer session. Current client events do not establish a multi-viewer addressing contract; inspect and extend the actual signaling server before relying on room broadcasts.
- **Keep a peer registry:** Replace singleton peer fields with independently owned viewer sessions. Joining or leaving one viewer must not reset the broadcast.
- **Share capture:** Capture camera/microphone once and attach appropriate tracks to each connection; do not open one camera per viewer.
- **Separate authorization:** Payment state, counters, channels, and media gating must be per viewer.
- **Avoid global gating:** Calling `setEnabled(false)` on a shared source track affects every sender using that track. Use a validated per-session enforcement mechanism, such as sender encoding control or independently gated tracks, without disrupting other viewers.
- **Separate global controls:** Host mute, camera switch, and end-broadcast are broadcast-wide; kick, reconnect, and payment expiration are viewer-specific.
- **Derive UI state:** Viewer count and lists should come from the session registry, not a fixed display value.
- **Choose topology deliberately:** Direct host-to-N viewers means approximately N outbound media streams, with device-dependent encoding cost and upload/thermal limits. For larger audiences, evaluate an SFU; TURN solves reachability, not audience fan-out.
- **Plan platform parity:** Android and iOS should share session/protocol semantics even when native media ownership differs.

### 6.3 Suggested implementation order

1. Specify feature behavior, supported platforms, audience size, payment rules, and background expectations.
2. Add regression coverage for role mapping, SDP ordering, early ICE, cancellation, and teardown.
3. Fix negotiation/error handling and native media gate wiring.
4. Introduce typed roles and lifecycle-owned observable session state.
5. Extract media ownership from individual peers if multiple sessions will share capture.
6. Define/version the server signaling contract and per-viewer message routing.
7. Implement independent peer and payment sessions.
8. Update UI/renderers and platform bridges.
9. Run cross-platform, adverse-network, background, and resource tests before increasing viewer limits.

## 7. Verification checklist

### Existing single-viewer behavior

- [ ] Android host → Android viewer receives video and audio.
- [ ] Android host → iOS viewer and iOS host → Android viewer negotiate correctly.
- [ ] iOS host → iOS viewer works with native capture/rendering.
- [ ] Host preview works before/after joining according to the intended product behavior.
- [ ] Viewer receives media without opening its own camera/microphone.
- [ ] Camera switch, microphone mute, video mute, and resume behave correctly.
- [ ] Data-channel open and first rendered frame are observed independently.

### Negotiation and networks

- [ ] ICE candidates arriving before remote SDP are queued and applied.
- [ ] Missing link acknowledgement, malformed SDP, and set-description failures terminate cleanly.
- [ ] Fast SDP replies cannot arrive before listeners are registered.
- [ ] Cancellation while waiting for a peer removes listeners and releases resources.
- [ ] Same-LAN, different-network, and forced-TURN sessions work.
- [ ] Wi-Fi/mobile handoff has a defined recovery or termination outcome.

### Lifecycle and access control

- [ ] Background/foreground, lock/unlock, and mini-player transitions preserve expected behavior.
- [ ] Native audio/video are blocked before authorization and after payment expiration.
- [ ] Repeated start/stop and failed starts release camera, microphone, EGL, and peer resources.
- [ ] Reconnection replaces stale tracks/renderers without crashes or frozen video.
- [ ] Permission denial and camera-in-use failures produce visible, recoverable errors.
- [ ] Logs exclude sensitive payloads and unnecessary peer network details in production.

### Multi-viewer acceptance criteria, if selected

- [ ] Viewer B joins without interrupting viewer A.
- [ ] One viewer disconnects or renegotiates without affecting others.
- [ ] One viewer's payment failure stops only that viewer's authorized media.
- [ ] Concurrent SDP/candidates cannot be delivered to the wrong session.
- [ ] Global host controls apply consistently to all active viewers.
- [ ] End-broadcast closes all sessions and capture exactly once.
- [ ] Upload bitrate, CPU/GPU load, thermal behavior, and memory fit the chosen viewer limit.

## 8. Questions to resolve before coding the feature

1. Is the feature multi-viewer, recording, screen sharing, reconnection, quality selection, or something else?
2. Must Android and iOS support hosting and viewing at the same time?
3. How many concurrent viewers must a host support?
4. Can the signaling server change, and is SFU infrastructure an option?
5. Must preview/capture exist before anyone joins?
6. Are payment authorization and media access independent per viewer?
7. What should happen during mute, payment expiry, network loss, and app backgrounding?

**Bottom line:** The existing native media pipeline is a useful one-to-one foundation. Reliable extension requires explicit session ownership, race-safe negotiation, tested media authorization, and coordinated renderer/resource lifecycle; multi-viewer support additionally requires per-peer signaling and independent viewer state.
