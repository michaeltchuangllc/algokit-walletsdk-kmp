# Liquid Stream — Creator ↔ Viewer Connection & Payment Sequence

This document describes the complete code flow from the **Creator (Provider/Server)** side to the **Viewer (Consumer/Client)** side — what happens first on connection, how native WebRTC video/audio is established, and what messages are exchanged.

---

## Architecture Overview

```
┌─────────────────���───────────────────────────────────────────────────┐
│                        CREATOR SIDE                                 │
│  LiquidAuthConnectionManager (Android)                              │
│      └─ SignalService (Foreground Service)                          │
│            ├─ SignalClient ──── WebSocket ──── Signaling Server     │
│            ├─ PeerApi (PeerConnection · EglBase)                    │
│            │     ├─ localVideoTrack  ──→ Camera2 capture           │
│            │     └─ localAudioTrack  ──→ Microphone capture        │
│            └─ LiquidStreamCreator                                   │
│                  ├─ MppPaymentRail (serverConfig)  → MppProvider   │
│                  └─ PaywalledRTCServer  → x402-payment-channel      │
└─────────────────────────────────────────────────────────────────────┘
              │  WebRTC PeerConnection (Unified Plan · BUNDLE)
              │  ┌─ m=video   sendonly  (Camera2, H.264/VP8)
              │  ├─ m=audio   sendonly  (Microphone, Opus)
              │  └─ m=application  (SCTP DataChannels)
              │       ├─ "liquid"               ← auth + session msgs
              │       └─ "x402-payment-channel" ← payment protocol
┌─────────────────────────────────────────────────────────────────────┐
│                        VIEWER SIDE                                  │
│  AnswerViewModel                                                    │
│      └─ SetupMppPaymentViewerUseCase                                │
│            └─ MppPaymentViewerManager                               │
│                  ├─ LiquidStreamViewer                              │
│                  │     ├─ MppPaymentRail (clientConfig) → MppConsumer│
│                  │     └─ PaywalledRTCClient → x402-payment-channel │
│                  └─ WebRtcVideoRenderer (TextureView)               │
│                        └─ remoteVideoTrack  (renders camera feed)   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Phase 1 — WebRTC Signaling & SDP Negotiation

### SDP Roles (Unified Plan)

| Role | Side | What it does |
|---|---|---|
| **SDP Offerer** | **Viewer** | `peer(type="answer")` — creates and sends the SDP Offer |
| **SDP Answerer** | **Creator** | `peer(type="offer")` — waits for the offer, creates and sends the SDP Answer |

> The parameter naming is inverted from the SDP role: `type="offer"` on the Creator means *"I wait for an offer"*, and `type="answer"` on the Viewer means *"I create the offer"*. The SDP Offerer is always the Viewer.

### Viewer (`AnswerScreenOverlay.handleWebRTCSetup`)

1. **`signalService.peer(requestId, type="answer", enableMedia=true)`** is called.
2. Inside `SignalClient.peer`:
   - Creates a `PeerApi` (PeerConnectionFactory + shared `EglBase`).
   - **`addReceiveOnlyMediaTransceivers()`** — adds two `recvonly` transceivers (video + audio) so the SDP Offer includes both m-lines.
   - Creates the **"liquid"** DataChannel.
   - Creates and sends the SDP Offer via the signaling server.
   - Waits for the SDP Answer from the Creator.

### Creator (`LiquidAuthConnectionManager.setupSignalService`)

3. **`service.peer(requestId, type="offer", enableMedia=true)`** is called.
4. Inside `SignalClient.peer`:
   - Creates a `PeerApi`.
   - **`startLocalCapture()`** — opens Camera2 + microphone; creates `localVideoTrack` and `localAudioTrack`; calls `peerConnection.addTrack()` for each.
   - **`configureAudioForStreaming()`** — sets `AudioManager.MODE_IN_COMMUNICATION` + `isSpeakerphoneOn = true` so remote audio plays through the loudspeaker instead of the earpiece.
   - Waits for the Viewer's SDP Offer, sets remote description, creates and sends the SDP Answer.

5. ICE candidates are exchanged in both directions until a DTLS/ICE path is established.
6. PeerConnection state → **CONNECTED**. All media tracks and DataChannels are now live.

---

## Phase 2 — "liquid" DataChannel Opens

### Creator

7. `onDataChannel("liquid")` fires on the Creator's `PeerApi.Observer`.
   - Incoming channel is stored as `dataChannel`.
   - `peer()` coroutine unblocks and returns the channel.
8. **`service.handleMessages()`** — registers a `DataChannel.Observer` on the "liquid" channel to receive Viewer messages.
9. DataChannel state → `OPEN`:
   - **`viewModel?.onClientConnected(requestId)`** fires.
   - `startConnectionTypePolling()` begins.

### Viewer

10. "liquid" DataChannel state → `OPEN`.
11. **Viewer sends a passkey credential/auth message** over the "liquid" channel.
12. Creator's `onMessage` fires → `tryCaptureViewerAddressFromMessage()` captures viewer address and signer key if present.

---

## Phase 3 — Creator Starts Payment & Announces Session

13. **`sendPaymentRequest()`** is called on the Creator (triggered by the `LiquidAuthOfferScreen` UI):
    - **`getOrCreateHostPaymentDataChannel()`** — creates the **`"x402-payment-channel"`** DataChannel (odd SCTP stream ID, negotiated).
    - Builds a `LiquidStreamCreator` wrapping `PaywalledRTCServer` + `MppPaymentRail(serverConfig)`.
    - **`creator.start()`** → `PaywalledRTCServer` starts its segment loop.
    - Stores `activePaymentRecipient` (creator's Algorand address) and `activePaymentSessionId`.

14. **`sendCreatorSessionInfo(hostAddress, sessionId)`** — immediately sends a lightweight JSON message over the "liquid" channel:

```json
// reference: "liquid:stream:info"   (Creator → Viewer, via "liquid" DataChannel)
{
  "reference": "liquid:stream:info",
  "hostAddress": "<creator_algorand_address>",
  "sessionId": "<uuid>"
}
```

> **Why this message is needed:** Previously `hostAddress` was piggybacked on every `liquid:video:frame` JPEG message. Native WebRTC media tracks replaced those frames, so this dedicated message now delivers the creator's payment address to the viewer. Without it, the viewer never knows who to pay.

---

## Phase 4 — Viewer Sets Up Payment Flow

15. Viewer's `onMessage` receives `"liquid:stream:info"`.
    - `extractHostAddress(peerMsg)` finds `hostAddress` → not blank.
    - **`viewModel.setupMppPaymentViewer(viewerAddress, hostAddress)`** is called.

16. **`SetupMppPaymentViewerUseCase.invoke()`**:
    - **`awaitPaymentDataChannel(service)`** — polls up to 2 seconds for the `"x402-payment-channel"` DataChannel (created by the Creator in step 13).
    - On success: wraps it as `WebRtcDataChannel(paymentChannel)`.

17. **`MppPaymentViewerManager.start()`** — creates a `LiquidStreamViewer` wrapping `PaywalledRTCClient` + `MppPaymentRail(clientConfig)`.
    - **`viewer.start()`** → `PaywalledRTCClient.connect(dataChannel)`.
    - Registers `RtcDataChannelObserver` on the x402 channel.

18. When `"x402-payment-channel"` state → `OPEN`, `onDataChannelOpen` fires:
    - Viewer sends `segment:handshake` over the **"x402-payment-channel"**:

```json
// type: "segment:handshake"   (Viewer → Creator, via "x402-payment-channel")
{
  "type": "segment:handshake",
  "viewer": "<viewer_algorand_address>",
  "viewerPublicKey": "<base64_authorized_signer_public_key>"
}
```

19. Creator receives the hello → `tryCaptureViewerAddressFromMessage()`:
    - Sets `activeViewerAuthorizedSignerKey`.
    - Calls `updateCreatorViewerSignerConfig(signerKey)` → resolves `viewerKeyDeferred` inside `PaywalledRTCServer` so `channelId` can be computed.

---

## Phase 5 — Native WebRTC Video & Audio (Runs in Parallel)

Video and audio stream as native WebRTC media tracks, independent of the DataChannels.

### Creator (sender)

- `PeerApi.startLocalCapture()` opened Camera2 + microphone during Phase 1.
- `localVideoTrack` and `localAudioTrack` are already added via `peerConnection.addTrack()`.
- `CameraStreamingPreview` renders `localVideoTrack` locally via `WebRtcVideoRenderer` (self-preview).

### Viewer (receiver)

- `PeerApi.onTrack(transceiver)` fires when the remote video track arrives.
- `handleRemoteVideoTrack(track)` stores `remoteVideoTrack` and invokes `onRemoteVideoTrack`.
- `AnswerScreen` polls `signalService.eglBaseContext` and `signalService.remoteVideoTrack` every 300 ms until both are non-null (avoids the race with async `peerClient` creation).
- `WebRtcVideoRenderer` — backed by **`WebRtcTextureViewRenderer`** (a `TextureView`) — renders the remote track.

> **Why `TextureView` instead of `SurfaceViewRenderer`:** WebRTC's `SurfaceViewRenderer` is a `SurfaceView` that punches a hole in its host window. Compose's `ModalBottomSheet` renders in a separate dialog window, so a `SurfaceView` always appears black there. A `TextureView` composites as a normal view and works correctly inside dialogs/bottom sheets.

### Audio routing

- WebRTC's audio device module uses `AudioAttributes.USAGE_VOICE_COMMUNICATION` (`usage=2`), which Android routes to the earpiece by default.
- `configureAudioForStreaming()` forces `MODE_IN_COMMUNICATION` + `speakerphoneOn = true` so audio plays through the loudspeaker.
- `restoreAudioMode()` reverts these settings when `PeerApi.destroy()` is called.

---

## Phase 6 — Creator Sends Payment Request

20. **`PaywalledRTCServer.requestPayment()`** is triggered by the segment timer:
    - **`resolveChannelIdBase64()`** — derives the escrow session vault channel ID from `viewerAddress + payTo + viewerAuthorizedSignerPublicKey` (requires `segment:handshake` to have been received first).
    - Checks if the session vault is already on-chain funded → if yes, **skips the request entirely** and auto-completes with a synthetic receipt.
    - Otherwise calls `MppPaymentRail.createPaymentRequest()` → `MppProvider.issueChallenge()` → generates a **WWW-Authenticate challenge**.
    - **Sends the first x402 DataChannel message →**

```json
// type: "segment:request"   (Creator → Viewer, via "x402-payment-channel")
{
  "type": "segment:request",
  "sessionId": "<uuid>",
  "segmentIndex": 0,
  "payload": {
    "id": "<uuid>",
    "sessionId": "<uuid>",
    "segmentIndex": 0,
    "amount": "100000",
    "asset": "USDC",
    "network": "algorand:testnet",
    "payTo": "<creator_algorand_address>",
    "ttl": 30,
    "nonce": "mpp:<challengeId>",
    "channelId": "<base64_escrow_channel_id>",
    "salt": "<base64_salt>",
    "meta": {
      "gatingMode": "partial:time",
      "enforcement": "track",
      "segmentDuration": 30,
      "viewerAddress": "<viewer_algorand_address>"
    },
    "railPayload": {
      "protocol": "mpp",
      "version": 0,
      "challengeId": "<uuid>",
      "wwwAuthenticate": "Charge realm=...",
      "issuedAt": 1715000000000
    }
  }
}
```

---

## Phase 7 — Viewer Processes & Responds with Payment

21. `PaywalledRTCClient.handleDataChannelMessage()` receives `"segment:request"` → `handlePaymentRequest()`.

22. **Consent check:**
    - Checks if session vault is already funded on-chain → if funded, auto-approves with a `BudgetCap`.
    - Otherwise `ConsentHandler.requestConsent()` is called → shows **payment consent UI** (`LiquidAuthSessionVaultModal`) to the user.
    - If vault not funded yet:
      - `MppPayments.openSessionAndDeposit()` — deposits USDC into the escrow vault on-chain.
      - `MppPayments.setAuthorizedSignerForSession()` — registers the viewer's authorized signer key on-chain.

23. If approved: `MppPaymentRail.createRailPayment(request)` → `MppConsumer.createCredential()` → **signs the Algorand transaction group** with the viewer's wallet key.

24. **Sends the second x402 DataChannel message →**

```json
// type: "segment:payment"   (Viewer → Creator, via "x402-payment-channel")
{
  "type": "segment:payment",
  "sessionId": "<uuid>",
  "segmentIndex": 0,
  "payload": {
    "railId": "mpp",
    "version": 0,
    "nonce": "mpp:<challengeId>",
    "paymentPayload": {
      "credential": "Payment <base64_signed_txn_group>"
    },
    "paymentRequirements": {
      "scheme": "charge",
      "network": "algorand:testnet",
      "amount": "100000",
      "asset": "USDC",
      "payTo": "<creator_algorand_address>"
    }
  }
}
```

> If the viewer **denies** payment: sends `"segment:payment"` with `"payload": null` → Creator terminates the session.

---

## Phase 8 — Creator Verifies & Unlocks the Stream

25. `PaywalledRTCServer.handleDataChannelMessage()` receives `"segment:payment"` → `handlePayment()`.
26. **Nonce check** — must match the pending `PaymentRequest.nonce`.
27. **Replay protection** — `NonceStore.checkAndStore(nonce, ttl)` rejects duplicate nonces.
28. `MppPaymentRail.verifyAndSettle()` → `MppProvider.verifyAndBroadcast()` → **submits the signed transaction group to the Algorand node**.
29. `completePaidSegment()`:
    - `ungate()` → **stream continues / segment unlocked**.
    - **Sends the third x402 DataChannel message →**

```json
// type: "segment:accepted"   (Creator → Viewer, via "x402-payment-channel")
{
  "type": "segment:accepted",
  "sessionId": "<uuid>",
  "segmentIndex": 0,
  "payload": {
    "txId": "<blockchain_tx_id>",
    "sessionId": "<uuid>",
    "segmentIndex": 0,
    "amount": "100000",
    "asset": "USDC",
    "payTo": "<creator_algorand_address>",
    "payFrom": "<viewer_algorand_address>",
    "network": "algorand:testnet",
    "channelId": "<base64_escrow_channel_id>",
    "timestamp": 1715000000000
  }
}
```

30. For **`PARTIAL_TIME` mode**: schedules the next segment timer → the request/payment/accept cycle **repeats every `segmentDuration` seconds**.

---

## Phase 9 — Viewer Voucher Update (Post-Receipt)

31. Viewer's **`onPaymentReceipt`** callback fires.
32. Builds a **cumulative voucher** — a FIDO2-signed claim with `totalAmountClaimedMicroUsdc`.
33. `MppPayments.updateVoucherOnChain()` — updates the escrow session vault on-chain.
34. Sends a **voucher JSON over the `x402-payment-channel` DataChannel** (`rtcClient.sendVoucher()`):

```json
// type: "segment:voucher"   (Viewer → Creator, via "x402-payment-channel" DataChannel)
{
  "type": "segment:voucher",
  "id": "<sessionId>",
  "viewer": "<viewer_algorand_address>",
  "viewerPublicKey": "<base64_authorized_signer_public_key>",
  "signature": "<base64_fido2_signature>",
  "totalAmountClaimedMicroUsdc": 100000
}
```

35. Creator's `tryCaptureViewerAddressFromMessage()` receives this → stores `activeCreatorVoucherClaimSnapshot` → used to **skip future payment requests** if the vault still has balance.

---

## Complete Message Flow

```
SIGNALING SERVER       CREATOR                              VIEWER
       │                  │                                    │
       │←── connect (WS) ─┤                                    │
       │←── connect (WS) ─┼────────────────────────────────────┤
       │                  │                                    │
       │                  │  [Viewer: addReceiveOnlyTransceivers│
       │                  │   video recvonly · audio recvonly]  │
       │←── SDP Offer ────┼────────────────────────────────────┤
       │                  │                                    │
       │   [Creator: startLocalCapture() → cam + mic]          │
       │   [configureAudioForStreaming() → speakerphone]        │
       │                  │                                    │
       │── SDP Answer ────┼────────────────────────────────────►│
       │                  │                                    │
       │         [ICE candidates exchanged ↔]                  │
       │         [DTLS → PeerConnection CONNECTED]             │
       │                  │                                    │
       ╔══════════════════╪══ "liquid" DataChannel OPEN ═══════╪╗
       ║                  │                                    │║
       ║                  │←── credential / passkey auth msg ──┤║  Viewer auth
       ║                  │    [onClientConnected fires]        │║
       ╚══════════════════╪════════════════════════════════════╪╝
       │                  │                                    │
       │  [Creator: sendPaymentRequest()]                       │
       │  [create x402-payment-channel DC]                     │
       │  [LiquidStreamCreator.start()]                        │
       │                  │                                    │
       │                  │── liquid:stream:info ──────────────►│  "liquid" DC
       │                  │   { hostAddress, sessionId }        │
       │                  │                                    │
       │           [Viewer: extractHostAddress()]               │
       │           [setupMppPaymentViewer()]                    │
       │           [awaitPaymentDataChannel() → found]          │
       │           [PaywalledRTCClient.connect()]               │
       │                  │                                    │
       ╔══════════════════╪══ "x402-payment-channel" OPEN ═════╪╗
       ║                  │                                    │║
       ║                  │←── segment:handshake ──────────────┤║  x402 DC
       ║                  │    { viewer, viewerPublicKey }      │║
       ║   [Creator: viewerKeyDeferred resolved]                │║
       ║   [channelId can now be computed]                      │║
       ╚══════════════════╪════════════════════════════════════╪╝
       │                  │                                    │
       │    ══════════════╪═ Native WebRTC Media (continuous) ═╪══
       │                  │~~~ Video Track (H.264/VP8) ~~~~~~~~►│
       │                  │~~~ Audio Track (Opus) ~~~~~~~~~~~~~►│
       │    ══════════════╪════════════════════════════════════╪══
       │                  │   [Viewer: onTrack() fires]         │
       │                  │   [remoteVideoTrack → TextureView]  │
       │                  │                                    │
       ╔══════════════════╪══ Payment Cycle (x402 DC) ═════════╪╗
       ║                  │                                    │║
       ║  [resolveChannelIdBase64()]                            │║
       ║  [MppProvider.issueChallenge()]                        │║
       ║                  │── segment:request ─────────────────►│║
       ║                  │   { channelId, nonce, amount }      │║
       ║                  │                                    │║
       ║          [Viewer shows consent UI]                     │║
       ║          [or auto-approves if vault funded]            │║
       ║          [openSessionAndDeposit() if needed]           │║
       ║          [MppConsumer.createCredential()]              │║
       ║                  │                                    │║
       ║                  │←── segment:payment ────────────────┤║
       ║                  │    { signed credential }            │║
       ║                  │    OR payload=null to deny          │║
       ║                  │                                    │║
       ║  [verifyAndBroadcast() → Algorand node]                │║
       ║  [completePaidSegment() → ungate()]                    │║
       ║                  │                                    │║
       ║                  │── segment:accepted ────────────────►│║
       ║                  │   { txId, receipt }                 │║
       ║                  │                                    │║
       ║          ──── repeats every segmentDuration s ────     │║
       ╚══════════════════╪════════════════════════════════════╪╝
       │                  │                                    │
       │          [Viewer: build FIDO2 voucher]                 │
       │          [updateVoucherOnChain()]                      │
       │                  │←── segment:voucher ────────────────┤  x402 DC
       │                  │    { totalAmountClaimed, sig }      │
       │                  │                                    │
       │                  │── session:terminate ───────────────►│  Either side
       │                  │   { reason }                        │
       │                  │                                    │
       │  [PeerApi.destroy()]                                   │
       │  [restoreAudioMode() · stopLocalCapture()]             │
       │                  │                                    │
```

---

## DataChannel Message Reference

### "liquid" channel — auth & session messages

| Reference | Direction | Purpose |
|---|---|---|
| *(credential/auth)* | Viewer → Creator | Passkey authentication at connection time |
| `liquid:stream:info` | Creator → Viewer | Delivers `hostAddress` + `sessionId` — replaces old `liquid:video:frame` piggyback |
| `segment:voucher` | Viewer → Creator | Cumulative FIDO2-signed claim sent over x402 DC; Creator skips future requests if vault still funded |

### "x402-payment-channel" — payment protocol

| Message Type | Direction | Purpose |
|---|---|---|
| `segment:handshake` | Viewer → Creator | Viewer's Algorand address + authorized signer public key; required to compute `channelId` |
| `segment:request` | Creator → Viewer | Payment challenge + stream terms (includes `channelId`) |
| `segment:payment` | Viewer → Creator | Signed credential (or `null` to deny) |
| `segment:accepted` | Creator → Viewer | Payment receipt — segment unlocked |
| `segment:rejected` | Creator → Viewer | Nonce mismatch or replay detected |
| `session:terminate` | Either → Either | Session ended with reason |
| `segment:key` | Creator → Viewer | Reserved for crypto enforcement (future) |

---

## Error / Edge Cases

| Scenario | Behavior |
|---|---|
| Viewer denies consent | Sends `segment:payment` with `payload: null` → Creator calls `terminate("Payment denied")` |
| Nonce mismatch | Creator sends `segment:rejected` with `reason: "nonce_mismatch"` → `terminate()` |
| Nonce replay | Creator sends `segment:rejected` with `reason: "nonce_replay"` |
| On-chain broadcast fails | Falls back to `session-vault-fallback` synthetic receipt; stream still unlocks |
| Session vault already funded | Creator **skips** `segment:request` entirely; stream unlocks immediately with synthetic receipt |
| Budget cap exceeded | Viewer emits `onBudgetExceeded` + `onStreamGated("Budget exceeded")` |
| DataChannel closed | Both sides emit `onSessionTerminated` |
| Camera permission not granted | `startLocalCapture` catches and logs; audio-only stream continues |
| `x402-payment-channel` race | Viewer polls 2 s; Creator sends channel immediately after `sendPaymentRequest` — resolves within window |
| `channelId = null` in segment:request | `segment:handshake` not yet received; Creator waits for viewer key before `resolveChannelIdBase64` runs |
| Remote video black in bottom sheet | Fixed by `WebRtcTextureViewRenderer` (`TextureView`-backed) — `SurfaceViewRenderer` punches through dialog windows |
| Audio plays from earpiece | Fixed by `configureAudioForStreaming()` — forces `MODE_IN_COMMUNICATION` + `speakerphoneOn = true` |

---

## Key Classes & Files

| Class / File | Module | Role |
|---|---|---|
| `PeerApi` | `wallet-sdk-core` | WebRTC peer connection, media track capture/receive, EGL context, DataChannels, audio routing |
| `SignalClient` | `wallet-sdk-core` | WebSocket signaling, SDP offer/answer exchange, ICE candidate relay |
| `SignalService` | `wallet-sdk-core` | Android foreground service hosting the WebRTC session; exposes `localVideoTrack`, `remoteVideoTrack`, `eglBaseContext` |
| `PaywalledRTCServer` | `wallet-sdk-core` | Creator-side x402 DataChannel manager — sends requests, verifies payments, resolves `channelId` |
| `PaywalledRTCClient` | `wallet-sdk-core` | Viewer-side x402 DataChannel manager — receives requests, handles consent, sends payments |
| `MppPaymentRail` | `wallet-sdk-core` | Payment rail — creates WWW-Authenticate challenges (provider) and signs credentials (consumer) |
| `MppProvider` | `wallet-sdk-core` | Issues challenges; verifies and broadcasts signed txns to Algorand |
| `MppConsumer` | `wallet-sdk-core` | Parses challenges; builds and signs Algorand transaction groups |
| `DataChannelProtocol.kt` | `wallet-sdk-core` | DC message types, JSON serializers/deserializers, `PAYMENT_CHANNEL_LABEL` |
| `WebRtcDataChannel` | `wallet-sdk-core` | Adapts `org.webrtc.DataChannel` to the platform-agnostic `RtcDataChannel` interface |
| `LiquidStreamCreator` | `wallet-sdk-core` | High-level creator wrapper (`PaywalledRTCServer` + `MppPaymentRail`) |
| `LiquidStreamViewer` | `wallet-sdk-core` | High-level viewer wrapper (`PaywalledRTCClient` + `MppPaymentRail`) |
| `LiquidAuthConnectionManager` | `wallet-sdk-ui` | Creator: manages `SignalService` binding, calls `sendPaymentRequest`, sends `liquid:stream:info` |
| `SetupMppPaymentViewerUseCase` | `wallet-sdk-ui` | Viewer: `awaitPaymentDataChannel`, builds `MppPaymentViewerManager` |
| `MppPaymentViewerManager` | `wallet-sdk-ui` | Viewer: wires `PaywalledRTCClient` callbacks, sends `segment:handshake`, handles vouchers |
| `WebRtcVideoRenderer` | `wallet-sdk-ui` | Compose composable that renders a `VideoTrack` via `WebRtcTextureViewRenderer` |
| `WebRtcTextureViewRenderer` | `wallet-sdk-ui` | `TextureView`-backed `EglRenderer` sink — works correctly inside `ModalBottomSheet` dialog windows |
| `CameraStreamingPreview` | `wallet-sdk-ui` | Creator self-preview; polls `getLocalVideoTrack()` and renders via `WebRtcVideoRenderer` |
| `AnswerScreen` | `wallet-sdk-ui` | Viewer screen; polls `signalService.remoteVideoTrack` + `eglBaseContext` and renders remote stream |
| `AnswerScreenOverlay` | `wallet-sdk-ui` | Wires viewer `handleWebRTCSetup`, extracts host address from `liquid:stream:info`, triggers payment setup |
