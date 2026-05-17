# Liquid Stream — Creator ↔ Viewer Connection & Payment Sequence

This document describes the complete code flow from the **Creator (Provider/Server)** side to the **Viewer (Consumer/Client)** side — what happens first on connection and what messages are exchanged.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CREATOR SIDE                                 │
│  AndroidLiquidAuthConnectionManager                                 │
│      └─ SignalService (WebRTC Offerer)                              │
│            └─ LiquidStreamCreator                                   │
│                  ├─ MppPaymentRail (serverConfig)  → MppProvider    │
│                  └─ PaywalledRTCServer              → DataChannel   │
└─────────────────────────────────────────────────────────────────────┘
                          │  WebRTC PeerConnection
                          │  DataChannel: "x402-payment-channel"
                          │  SignalService WebSocket
┌─────────────────────────────────────────────────────────────────────┐
│                        VIEWER SIDE                                  │
│  AnswerViewModel                                                    │
│      └─ SetupMppPaymentViewerUseCase                                │
│            └─ LiquidStreamViewer                                    │
│                  ├─ MppPaymentRail (clientConfig)  → MppConsumer    │
│                  └─ PaywalledRTCClient              → DataChannel   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Phase 1 — WebRTC Signaling & Connection Setup

### Creator (`AndroidLiquidAuthConnectionManager`)

1. **`startListening(origin, requestId)`** is called.
2. Starts `SignalService` as a foreground Android service.
3. **`service.start(url)`** + **`service.peer(requestId, type = "offer", iceServers)`**
   - Creator creates a `PeerConnection` as the **WebRTC offerer**.
   - SDP Offer is sent through the signaling server.
4. ICE candidates are exchanged until a direct/relay path is established.

### Viewer (`AnswerViewModel` → `SetupMppPaymentViewerUseCase`)

5. Viewer connects to the same signaling server as the **WebRTC answerer**.
6. Accepts the SDP Offer, sends back an SDP Answer.
7. **`awaitPaymentDataChannel(service)`** — polls/waits for a `DataChannel` with label `"x402-payment-channel"`.

---

## Phase 2 — DataChannel Opens (First Event on Both Sides)

### Creator

8. `DataChannel` state → `OPEN`.
9. **`viewModel?.onClientConnected(requestId)`** fires.
10. **`sendPaymentRequest()`** is called, which:
    - Creates a **`LiquidStreamCreator`** wrapping `PaywalledRTCServer` + `MppPaymentRail(serverConfig)`.
    - Calls **`creator.start()`** → `PaywalledRTCServer.listen(dataChannel, rtpSenders)`.
    - Registers `DataChannel.Observer` on the server side.

### Viewer

11. The `"x402-payment-channel"` `DataChannel` is found (or created).
12. **`LiquidStreamViewer`** is created, wrapping `PaywalledRTCClient` + `MppPaymentRail(clientConfig)`.
13. **`viewer.start()`** → `PaywalledRTCClient.connect(dataChannel)`.
    - Registers `DataChannel.Observer`.
    - **`onDataChannelOpen` callback fires** on the viewer side.

---

## Phase 3 — First Message: Creator Sends Payment Request

14. **`PaywalledRTCServer.handleDataChannelOpen()`** fires (with a 100 ms safety delay), then:
    - **`WHOLE_STREAM` mode** → immediately calls `requestPayment()`.
    - **`PARTIAL_TIME` mode** → `ungate()` stream (stream starts freely), schedules a segment timer with `leadTime`, then calls `requestPaymentWithGrace()`.

15. **`requestPayment()`** on Creator:
    - Checks if the viewer's **session vault** is already on-chain funded → if yes, **skips the request entirely** and auto-completes with a synthetic receipt.
    - Otherwise calls `MppPaymentRail.createPaymentRequest()` → `MppProvider.issueChallenge()`.
    - `issueChallenge()` generates a **WWW-Authenticate challenge** (UUID nonce + Algorand suggested params embedded).
    - **Sends the first DataChannel message →**

```json
// type: "segment:request"   (Creator → Viewer)
{
  "type": "segment:request",
  "sessionId": "<uuid>",
  "segmentIndex": 0,
  "payload": {
    "id": "<uuid>",
    "sessionId": "<uuid>",
    "segmentIndex": 0,
    "amount": "3000",
    "asset": "10458941",
    "network": "algorand:testnet",
    "payTo": "<creator_address>",
    "ttl": 60,
    "nonce": "mpp:<challengeId>",
    "meta": {
      "gatingMode": "partial:time",
      "enforcement": "track",
      "segmentDuration": 3,
      "viewerAddress": "<viewer_address>"
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

> **This is always the first message.** The Viewer never sends a message first — it only responds.

---

## Phase 4 — Viewer Processes & Responds with Payment

16. `PaywalledRTCClient.handleDataChannelMessage()` receives `"segment:request"` → `handlePaymentRequest()`.

17. **Consent check:**
    - Checks if session vault is already funded on-chain → if funded, auto-approves with a `BudgetCap`.
    - Otherwise `ConsentHandler.requestConsent()` is called → shows **payment consent UI** to the user.
    - If vault not funded yet:
      - `MppPayments.openSessionAndDeposit()` — deposits USDC into the escrow vault on-chain.
      - `MppPayments.setAuthorizedSignerForSession()` — registers the viewer's authorized signer key on-chain.

18. If approved: `MppPaymentRail.createRailPayment(request)` → `MppConsumer.createCredential()` → **signs the Algorand transaction group** with the viewer's wallet key.

19. **Sends the second DataChannel message →**

```json
// type: "segment:payment"   (Viewer → Creator)
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
      "amount": "3000",
      "asset": "10458941",
      "payTo": "<creator_address>"
    }
  }
}
```

> If the viewer **denies** payment: sends `"segment:payment"` with `"payload": null` → Creator terminates the session.

---

## Phase 5 — Creator Verifies & Unlocks the Stream

20. `PaywalledRTCServer.handleDataChannelMessage()` receives `"segment:payment"` → `handlePayment()`.
21. **Nonce check** — must match the pending `PaymentRequest.nonce`.
22. **Replay protection** — `NonceStore.checkAndStore(nonce, ttl)` rejects duplicate nonces.
23. `MppPaymentRail.verifyAndSettle()` → `MppProvider.verifyAndBroadcast()` → **submits the signed transaction group to the Algorand/Solana node**.
24. `completePaidSegment()`:
    - `ungate()` → **enables RTP tracks — the media stream unlocks**.
    - **Sends the third DataChannel message →**

```json
// type: "segment:accepted"   (Creator → Viewer)
{
  "type": "segment:accepted",
  "sessionId": "<uuid>",
  "segmentIndex": 0,
  "payload": {
    "txId": "<blockchain_tx_id>",
    "sessionId": "<uuid>",
    "segmentIndex": 0,
    "amount": "3000",
    "asset": "10458941",
    "payTo": "<creator_address>",
    "payFrom": "<viewer_address>",
    "network": "algorand:testnet",
    "timestamp": 1715000000000
  }
}
```

25. For **`PARTIAL_TIME` mode**: schedules the next segment timer → the request/payment/accept cycle **repeats every `segmentDuration` seconds**.

---

## Phase 6 — Viewer Voucher Update (Post-Receipt)

26. Viewer's **`onPaymentReceipt`** callback fires.
27. Builds a **cumulative voucher** — a FIDO2-signed claim message with `totalAmountClaimedMicroUsdc`.
28. `MppPayments.updateVoucherOnChain()` — updates the escrow session vault on-chain with the new voucher amount.
29. Sends a **voucher JSON over the WebSocket signaling channel** (not the DataChannel):

```json
// reference: "liquid:payment:voucher"   (Viewer → Creator, via SignalService.send())
{
  "reference": "liquid:payment:voucher",
  "id": "<sessionId>",
  "viewer": "<viewer_algorand_address>",
  "viewerPublicKey": "<base64_authorized_signer_public_key>",
  "signature": "<base64_fido2_signature>",
  "totalAmountClaimedMicroUsdc": 3000
}
```

30. Creator's `tryCaptureViewerAddressFromMessage()` receives this → stores `activeCreatorVoucherClaimSnapshot` → used to **skip future payment requests** if the vault still has balance.

---

## Complete DataChannel Message Flow

```
CREATOR                                  VIEWER
   |                                        |
   |  [DataChannel OPEN]                    |
   |                                        |
   |──── segment:request ──────────────────>|   ← First message ever sent
   |     (challenge + payment terms)        |     100ms after DC opens
   |                                        |
   |     [Viewer shows consent UI]          |
   |     [or auto-approves if vault funded] |
   |                                        |
   |<─── segment:payment ───────────────────|   ← Viewer's first response
   |     (signed credential)                |
   |     OR payload=null to deny            |
   |                                        |
   |  [Creator verifies + broadcasts txn]   |
   |  [Ungate → RTP tracks enabled]         |
   |                                        |
   |──── segment:accepted ─────────────────>|   ← Stream is now live
   |     (payment receipt)                  |
   |                                        |
   |  ───── repeats every N seconds ──────  |
   |  (for PARTIAL_TIME gating mode)        |
   |                                        |
   |──── session:terminate ────────────────>|   ← Either side can close
   |     (reason)                           |
   |                                        |
```

### All DataChannel Message Types

| Message Type | Direction | Purpose |
|---|---|---|
| `segment:request` | Creator → Viewer | Payment challenge + stream terms |
| `segment:payment` | Viewer → Creator | Signed credential (or `null` to deny) |
| `segment:accepted` | Creator → Viewer | Payment receipt — stream unlocked |
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

---

## Key Classes & Files

| Class / File | Module | Role |
|---|---|---|
| `PaywalledRTCServer` | `wallet-sdk-core` | Creator-side DataChannel manager — sends requests, verifies payments |
| `PaywalledRTCClient` | `wallet-sdk-core` | Viewer-side DataChannel manager — receives requests, sends payments |
| `MppPaymentRail` | `wallet-sdk-core` | Payment rail implementation — creates challenges (provider) and credentials (consumer) |
| `MppProvider` | `wallet-sdk-core` | Issues WWW-Authenticate challenges; verifies and broadcasts signed txns |
| `MppConsumer` | `wallet-sdk-core` | Parses challenges; builds and signs Algorand/Solana transaction groups |
| `DataChannelProtocol.kt` | `wallet-sdk-core` | DC message types, JSON serializers/deserializers |
| `LiquidStreamCreator` | `wallet-sdk-core` | High-level creator convenience wrapper |
| `LiquidStreamViewer` | `wallet-sdk-core` | High-level viewer convenience wrapper |
| `AndroidLiquidAuthConnectionManager` | `wallet-sdk-ui` | Manages `SignalService` binding + creator session lifecycle |
| `SetupMppPaymentViewerUseCase` | `wallet-sdk-ui` | Manages viewer session — consent, on-chain vault, voucher updates |
| `AnswerViewModel` | `wallet-sdk-ui` | Viewer-side ViewModel orchestrating the whole viewer flow |
