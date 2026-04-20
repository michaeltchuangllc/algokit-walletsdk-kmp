package com.michaeltchuang.walletsdk.core.railmpp

import com.algorand.algosdk.account.Account

/**
 * Provider-side configuration. Required when the rail issues challenges and
 * verifies/broadcasts credentials (`createPaymentRequest` + `verifyAndSettle`).
 */
data class MppServerConfig(
    /** Algorand CAIP-2 network. Defaults to TestNet for the demo. */
    val network: String = MppNetworks.TESTNET,
    /** Custom algod URL. Defaults to [DEFAULT_ALGOD_URLS] for the network. */
    val algodUrl: String? = null,

    /** Address that receives the payment. Must match `params.payTo`. */
    val recipient: String,

    /**
     * HMAC secret used to sign challenge IDs. Per-session is fine; per-deployment
     * is recommended for cross-process challenge verification.
     */
    val secretKey: String,

    /**
     * Server "realm" string echoed in the challenge. Defaults to
     * `"webrtc-mpp"` — visible to consumers as part of the challenge envelope.
     */
    val realm: String = "webrtc-mpp",

    /**
     * If non-null, the provider operates in fee-sponsorship mode: it includes a
     * fee payer transaction in the issued challenge and signs that txn before
     * broadcasting. Consumers see `feePayer: true` and `feePayerKey: <address>`
     * in `methodDetails`.
     */
    val feePayer: Account? = null,

    /**
     * Time-to-live for issued challenges in seconds. Used to compute the
     * `expires` field on each WWW-Authenticate header.
     */
    val challengeTtlSeconds: Int = 60,
)

/**
 * Consumer-side configuration. Required when the rail receives a challenge,
 * builds the txn group, and signs it (`createRailPayment`).
 */
data class MppClientConfig(
    /** Algorand CAIP-2 network. Defaults to TestNet. */
    val network: String = MppNetworks.TESTNET,
    /** Custom algod URL. Defaults to [DEFAULT_ALGOD_URLS] for the network. */
    val algodUrl: String? = null,

    /** Wallet that signs the payment transaction. */
    val signer: MppWalletSigner,

    /** Optional progress callback (mirrors the TS rail's onProgress). */
    val onProgress: ((MppProgressEvent) -> Unit)? = null,
)

/**
 * Progress callbacks emitted during consumer-side credential creation.
 */
sealed interface MppProgressEvent {
    /** Challenge parsed and ready to sign. */
    data class Challenge(
        val amount: String,
        val currency: String,
        val recipient: String,
        val asaId: String?,
        val feePayerKey: String?,
    ) : MppProgressEvent

    /** Wallet is being asked to sign the payment transaction. */
    data object Signing : MppProgressEvent

    /** Signed credential ready to send to provider. */
    data class Signed(val paymentGroup: List<String>) : MppProgressEvent
}
