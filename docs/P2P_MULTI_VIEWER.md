## Android and iOS P2P multi-viewer broadcasting

### What changed

Both hosts keep one WebRTC connection and one Socket.IO connection per viewer.
All host connections share one camera/microphone capture, peer factory, and EGL context.
Adding or removing a viewer does not restart capture, replace another connection, or
renegotiate the other viewers. Only stopping the broadcast releases shared media.

This is direct host-to-many P2P fan-out (a host-centered mesh/star), not an SFU and not
viewer-to-viewer relaying. Host upload and encoding load increase with viewer count;
there is no guarantee of unlimited viewers or uninterrupted video under network/device overload.

### Joining from the live screen

1. Start a broadcast and grant camera/microphone permissions.
2. Select **Free** or **Paid** streaming; neither mode restricts additional viewer admission.
3. Viewer A scans the initial QR and connects.
4. Open the QR action in `LiquidStreamHostLiveScreen`.
5. Share the **current** invitation with viewer B.
6. Each accepted viewer rotates the displayed invitation automatically. Use the new QR
   or copied URL for each additional viewer.

Invitation refresh affects invitation state only, never the live broadcast or its peers.
The viewer count follows connected primary data channels; it is not a measurement of
successfully decoded video on every device.

### Signaling compatibility

The existing server messages contain no peer-routing ID. Rather than inventing an
unsupported room protocol, each viewer uses a distinct request ID and a dedicated
Socket.IO transport (`forceNew`, no multiplexing). SDP and ICE stay on that link.
The viewer continues to create the SDP offer and the host answers it.

**One URL is not a reusable room link.** Do not send the same QR to several viewers.
Existing invitations already shared can still be in flight when the host generates
another invitation; their peers are kept independent rather than being replaced.
At most eight invitations may wait for a connection, and each expires after five minutes.
Expiry affects only the pending peer; connected viewers do not expire with their invitations.
The deployed backend must allow concurrent independent request-ID links. That backend
is external to this repository, and live concurrent-link behavior needs device testing.

### Platform and payment scope

- **Host:** Both Android and the iOS demo bridge implement isolated multi-viewer hosting.
  Other iOS integrators must register the keyed mesh handlers before opting into mesh mode.
- **Viewer:** The existing receive-only protocol is retained. Android/iOS interoperability
  must be verified on devices.
- **Free streams:** Additional viewers receive the shared native audio/video through their
  independent peer connections.
- **Payments:** Free/Paid mode does not reject additional viewers or gate their native
  media transport. Existing session-vault payment and zero-balance popup behavior remains
  unchanged; no alternate direct-charge flow is introduced. The viewer UI, rather than
  mesh transport, handles the existing zero-balance restriction for paid streams.
  This is not server-side media access enforcement, and does not add per-viewer vault batching.
- **Analytics:** Each additional viewer publishes its wallet identity and selected ICE route
  to its own card. Read-only vault polling supplies that viewer's balance, cumulative settled
  amount, and deposit total for the progress bar. Reported channel IDs are checked against
  on-chain participants and signer identity; missing or failed reads remain unavailable,
  never copied from the primary viewer. These reads do not submit settlements or add batching.

### Key implementation files

- **`SignalService.kt`:** Broadcast-owned media, peer registry, independent add/remove.
- **`HostViewerSession.kt`:** Per-viewer socket, peer, primary channel, and teardown.
- **`SharedBroadcastMedia.kt`:** Single camera/microphone, EGL, and factory ownership.
- **`SignalClient.kt`:** Isolated signaling, early ICE buffering, ordered SDP, cancellation.
- **`LiquidAuthConnectionManager.android.kt`:** Invitation admission and per-viewer callbacks.
- **`LiquidAuthOfferViewModel.kt`:** Separate invitation, membership, and legacy payment state.
- **`LiquidStreamHostLiveScreen.kt`:** Existing QR modal and viewer count; no extra invitation controls or banners.
- **`SignalService.swift` / `PeerApi.swift`:** iOS peer registry and shared capture sources.
- **`iosApp.swift` / `App.ios.kt`:** Keyed native-to-Kotlin callbacks and permission/capture lifecycle.
- **`LiquidAuthConnectionManager.ios.kt`:** Invitation membership and viewer-scoped metadata.
- **`HostViewerDetails.kt`:** Request-keyed card data, cleared when its viewer disconnects.
- **`HostViewerVaultReader.kt`:** Explicit-network, read-only viewer vault snapshots.

### Verification

Automated tests cover registry insertion/removal/duplicate protection and real `PeerApi`
ownership logic with mocked WebRTC/media objects. They do not render video or establish
network connections.

Before release, verify with at least three physical devices:

- [ ] Both Free and Paid streams accept additional viewers without restarting existing peers.
- [ ] Paid viewers still see the existing zero-balance popup when their session-vault balance reaches zero.
- [ ] Top-up and existing payment behavior are unchanged; no alternate direct charges are introduced.
- [ ] A continues receiving frames/audio while B joins using the next invitation.
- [ ] Repeat with Android host + Android/iOS viewers, and iOS host + Android/iOS viewers.
- [ ] Each card shows its own wallet, selected connection type, balance, and settled total.
- [ ] A top-up or settlement updates only that viewer's data; progress uses its deposit total.
- [ ] B continues streaming when A leaves, and vice versa.
- [ ] C can join after the original viewer has left.
- [ ] QR refresh and failed/denied joins leave existing peers untouched.
- [ ] First launch requests permissions before camera/microphone foreground-service startup.
- [ ] Minimize/expand, camera switch, mute/resume, and end-broadcast work with two viewers.
- [ ] Independent links work across LAN, mobile networks, and forced TURN.
- [ ] Temporary signaling loss does not terminate already-negotiated media.
- [ ] Repeated joins/leaves release peer resources while retaining only one camera capture.
- [ ] Establish a tested viewer limit for bandwidth, encoder capacity, thermal load, and memory.

### Build validation

- Android core and UI host tests pass; shared-media tests mock native objects and do not stream.
- iOS Kotlin simulator compilation passes.
- Xcode Debug arm64 simulator build passes for the app and extensions, with signing disabled.
- No physical-device streaming or production-signaling concurrency tests were run here.
- The iOS demo currently supplies STUN only; TURN configuration is still needed for networks
  where direct connectivity fails. Android/iOS builds alone do not validate NAT traversal.
