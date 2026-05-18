package com.michaeltchuang.walletsdk.core.railmpp.utils

/**
 * Typed error hierarchy for the EscrowSessionVaultManager payment channel contract.
 *
 * Every `assert` failure message from the on-chain contract maps to exactly one subtype here,
 * so the UI never shows a raw JSON blockchain error to the user.
 *
 * Usage:
 *   val error = PaymentError.from(throwable)
 *   showError(error.userMessage)
 */
sealed class PaymentError {
    /** Short, human-readable sentence suitable for displaying directly in the UI. */
    abstract val userMessage: String

    // ─── Channel State ────────────────────────────────────────────────────────

    /** Contract: "Channel does not exist" */
    object ChannelNotFound : PaymentError() {
        override val userMessage =
            "Payment channel not found. Please open a new session first."
    }

    // ─── Authorization ────────────────────────────────────────────────────────

    /** Contract: "Only payer can top up" / "Only payer can reopen channel" */
    object OnlyPayerCanDeposit : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) account can deposit funds into this channel."
    }

    /** Contract: "Only payer can update voucher" */
    object OnlyPayerCanUpdateVoucher : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can record a new voucher on-chain."
    }

    /** Contract: "Only payer can set authorized signer" */
    object OnlyPayerCanSetSigner : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can update the authorized signer for this channel."
    }

    /** Contract: "Only payer can request close" */
    object OnlyPayerCanRequestClose : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can initiate a forced-close request."
    }

    /** Contract: "Only payer can withdraw" */
    object OnlyPayerCanWithdraw : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can withdraw funds after the grace period expires."
    }

    /** Contract: "Only payee can settle" */
    object OnlyPayeeCanSettle : PaymentError() {
        override val userMessage =
            "Only the creator (payee) account can settle earned funds from this channel."
    }

    /** Contract: "Only payee can close" */
    object OnlyPayeeCanClose : PaymentError() {
        override val userMessage =
            "Only the creator (payee) can close a fully-settled channel."
    }

    /** Contract: "Only creator can opt in USDC" */
    object OnlyContractCreatorCanOptIn : PaymentError() {
        override val userMessage =
            "Only the contract deployer can opt the escrow account into USDC."
    }

    // ─── Voucher Validation ───────────────────────────────────────────────────

    /** Contract: "Voucher exceeds deposit" */
    object VoucherExceedsDeposit : PaymentError() {
        override val userMessage =
            "Voucher amount exceeds the deposited balance. Please top up the session vault first."
    }

    /** Contract: "Voucher not increasing" */
    object VoucherNotIncreasing : PaymentError() {
        override val userMessage =
            "Voucher amount must be higher than the current on-chain value. " +
                "This amount may have already been recorded."
    }

    /** Contract: "Voucher below settled amount" */
    object VoucherBelowSettled : PaymentError() {
        override val userMessage =
            "Voucher amount is below the already-settled amount and cannot be applied."
    }

    // ─── Settlement ───────────────────────────────────────────────────────────

    /** Contract: "Nothing new to settle" */
    object NothingToSettle : PaymentError() {
        override val userMessage =
            "No new funds to settle. The latest voucher has already been fully collected."
    }

    /** Contract: "Settle exceeds latest voucher" */
    object SettleExceedsVoucher : PaymentError() {
        override val userMessage =
            "Settlement amount exceeds the latest approved voucher. " +
                "Request an updated voucher from the viewer first."
    }

    /** Contract: "Unclaimed voucher funds remain" */
    object UnclaimedFundsRemain : PaymentError() {
        override val userMessage =
            "The channel still has unclaimed voucher funds. " +
                "The creator must settle all earnings before the channel can be closed."
    }

    // ─── Channel Close / Withdraw ─────────────────────────────────────────────

    /** Contract: "Close not requested" */
    object CloseNotRequested : PaymentError() {
        override val userMessage =
            "A close has not been requested yet. " +
                "The viewer must call 'Request Close' before withdrawing."
    }

    /** Contract: "Close grace period not elapsed" */
    object CloseGracePeriodActive : PaymentError() {
        override val userMessage =
            "The 15-minute close grace period is still active. " +
                "The creator has until the period expires to settle any remaining funds."
    }

    // ─── Signer / Signature ───────────────────────────────────────────────────

    /** Contract: "Authorized signer public key not set yet" / "Authorized signer pubkey required" */
    object SignerPublicKeyNotSet : PaymentError() {
        override val userMessage =
            "No authorized signer public key is registered for this channel. " +
                "Re-open the session to register a signing key."
    }

    /** Contract: "Invalid signer pubkey" — sha512_256 of stored key ≠ registered hash */
    object InvalidSignerPublicKey : PaymentError() {
        override val userMessage =
            "The stored signer public key does not match the hash registered on-chain. " +
                "The channel may have been opened with a different key."
    }

    /** Contract: "Authorized signer hash mismatch" */
    object SignerHashMismatch : PaymentError() {
        override val userMessage =
            "The authorized signer key does not hash to the expected value. " +
                "Ensure the correct public key is being used."
    }

    /** Contract: "Signer hash must be 32 bytes" */
    object SignerHashWrongLength : PaymentError() {
        override val userMessage =
            "Internal error: the signer hash passed to the contract must be exactly 32 bytes."
    }

    /** Contract: "Invalid Ed25519 signature length" */
    object InvalidSignatureLength : PaymentError() {
        override val userMessage =
            "Signature has an invalid length for Ed25519. Expected exactly 64 bytes."
    }

    /** Contract: "Invalid signature" */
    object InvalidSignature : PaymentError() {
        override val userMessage =
            "Signature verification failed. The voucher may have been signed with the wrong key " +
                "or the message was tampered with."
    }

    // ─── Deposit / USDC Transfer ──────────────────────────────────────────────

    /** Contract: "Deposit must be > 0" */
    object DepositMustBePositive : PaymentError() {
        override val userMessage = "Deposit amount must be greater than zero."
    }

    /** Contract: "Payment sender mismatch" */
    object DepositSenderMismatch : PaymentError() {
        override val userMessage =
            "The USDC transfer must originate from the same account as the app call."
    }

    /** Contract: "Payment asset must be USDC" */
    object DepositMustBeUsdc : PaymentError() {
        override val userMessage =
            "Only USDC asset transfers are accepted as deposits into the session vault."
    }

    /** Contract: "Payment must be to contract" */
    object DepositReceiverMismatch : PaymentError() {
        override val userMessage =
            "The USDC transfer must be directed to the contract escrow address."
    }

    /** Contract: "Clawback transfer not allowed" */
    object ClawbackNotAllowed : PaymentError() {
        override val userMessage =
            "Clawback USDC transfers are not permitted in this contract."
    }

    /** Contract: "Asset close not allowed" */
    object AssetCloseNotAllowed : PaymentError() {
        override val userMessage =
            "Closing asset positions as part of a deposit transfer is not permitted."
    }

    // ─── Channel Config ───────────────────────────────────────────────────────

    /** Contract: "Payee mismatch" — trying to reopen a channel with a different payee */
    object PayeeMismatch : PaymentError() {
        override val userMessage =
            "The creator (payee) address does not match the original channel. " +
                "Use a different salt to open a separate channel with a new payee."
    }

    // ─── Transaction Lifecycle ────────────────────────────────────────────────

    /**
     * A transaction was broadcast successfully but did not reach a confirmed round
     * within the polling window. The operation cannot proceed until it is confirmed.
     */
    object TransactionNotConfirmed : PaymentError() {
        override val userMessage =
            "The transaction was submitted but did not confirm in time. " +
                "Please wait a moment and try again, or check your network connection."
    }

    // ─── Client / Device ─────────────────────────────────────────────────────

    /**
     * The local wallet account for [address] is not stored on this device.
     * Both the viewer and creator accounts must be present to run the settle flow.
     */
    data class SignerNotFound(
        val address: String,
    ) : PaymentError() {
        override val userMessage =
            "Wallet account not found on this device for address: $address. " +
                "Ensure both the viewer and creator accounts are imported."
    }

    /** The session channel snapshot could not be read from the chain. */
    object SessionNotFound : PaymentError() {
        override val userMessage =
            "Session data not found on-chain. Please open a new session first."
    }

    // ─── Network ──────────────────────────────────────────────────────────────

    /**
     * The Algorand node rejected the transaction group.
     * [rawMessage] is the trimmed inner message extracted from the node's JSON response
     * (PC numbers and raw opcode mnemonics are stripped before display).
     */
    data class BroadcastFailed(
        val rawMessage: String,
    ) : PaymentError() {
        override val userMessage: String =
            run {
                when {
                    rawMessage.contains("overspend", ignoreCase = true) ->
                        "Insufficient ALGO balance to cover transaction fees. Please add ALGO to your wallet."
                    rawMessage.contains("balance below min", ignoreCase = true) ->
                        "Your account balance would drop below the minimum required amount (MBR). Add more ALGO first."
                    rawMessage.contains("asset not opted in", ignoreCase = true) ||
                        rawMessage.contains("asset not found", ignoreCase = true) ->
                        "The account has not opted into the required asset (USDC). Please opt in before transacting."
                    rawMessage.contains("fee too small", ignoreCase = true) ->
                        "Transaction fee is too small. Please retry with a higher fee."
                    rawMessage.contains("logic eval error", ignoreCase = true) ->
                        // Strip the ". Details: app=..., pc=..., opcodes=..." suffix — not user-readable
                        "The transaction was rejected by the smart contract. " +
                            "Please check that the channel is open and all amounts are valid."
                    rawMessage.isNotBlank() ->
                        "The transaction was rejected by the network. ${rawMessage.take(120)}"
                    else ->
                        "The transaction could not be submitted. Please check your connection and try again."
                }
            }
    }

    // ─── Fallback ─────────────────────────────────────────────────────────────

    /** Catch-all for unexpected errors that don't match any known contract message. */
    data class Unknown(
        val message: String?,
        val cause: Throwable? = null,
    ) : PaymentError() {
        override val userMessage =
            "An unexpected error occurred: ${message?.take(120) ?: "no details available"}"
    }

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        /**
         * Parses any [Throwable] — including wrapped on-chain assertion errors —
         * into the most specific [PaymentError] subtype.
         */
        fun from(throwable: Throwable): PaymentError = parse(throwable.message ?: "", throwable)

        /**
         * Parses a raw error [message] string into the most specific [PaymentError] subtype.
         */
        fun parse(
            message: String,
            cause: Throwable? = null,
        ): PaymentError =
            when {
                // ── Channel state ──────────────────────────────────────────────────
                message.contains("Channel does not exist", ignoreCase = true) ->
                    ChannelNotFound

                // ── Authorization ──────────────────────────────────────────────────
                message.contains("Only payer can top up", ignoreCase = true) ||
                    message.contains("Only payer can reopen channel", ignoreCase = true) ->
                    OnlyPayerCanDeposit

                message.contains("Only payer can update voucher", ignoreCase = true) ->
                    OnlyPayerCanUpdateVoucher

                message.contains("Only payer can set authorized signer", ignoreCase = true) ->
                    OnlyPayerCanSetSigner

                message.contains("Only payer can request close", ignoreCase = true) ->
                    OnlyPayerCanRequestClose

                message.contains("Only payer can withdraw", ignoreCase = true) ->
                    OnlyPayerCanWithdraw

                message.contains("Only payee can settle", ignoreCase = true) ->
                    OnlyPayeeCanSettle

                message.contains("Only payee can close", ignoreCase = true) ->
                    OnlyPayeeCanClose

                message.contains("Only creator can opt in USDC", ignoreCase = true) ->
                    OnlyContractCreatorCanOptIn

                // ── Voucher ────────────────────────────────────────────────────────
                message.contains("Voucher exceeds deposit", ignoreCase = true) ->
                    VoucherExceedsDeposit

                message.contains("Voucher not increasing", ignoreCase = true) ->
                    VoucherNotIncreasing

                message.contains("Voucher below settled amount", ignoreCase = true) ->
                    VoucherBelowSettled

                // ── Settlement ─────────────────────────────────────────────────────
                message.contains("Nothing new to settle", ignoreCase = true) ->
                    NothingToSettle

                message.contains("Settle exceeds latest voucher", ignoreCase = true) ->
                    SettleExceedsVoucher

                message.contains("Unclaimed voucher funds remain", ignoreCase = true) ->
                    UnclaimedFundsRemain

                // ── Close / Withdraw ───────────────────────────────────────────────
                message.contains("Close not requested", ignoreCase = true) ->
                    CloseNotRequested

                message.contains("Close grace period not elapsed", ignoreCase = true) ->
                    CloseGracePeriodActive

                // ── Signer / Signature ─────────────────────────────────────────────
                message.contains("Authorized signer public key not set yet", ignoreCase = true) ||
                    message.contains("Authorized signer pubkey required", ignoreCase = true) ->
                    SignerPublicKeyNotSet

                message.contains("Invalid signer pubkey", ignoreCase = true) ->
                    InvalidSignerPublicKey

                // "Authorized signer hash mismatch" appears in both open() and setAuthorizedSignerPublicKeyIfProvided()
                message.contains("Authorized signer hash mismatch", ignoreCase = true) ->
                    SignerHashMismatch

                message.contains("Signer hash must be 32 bytes", ignoreCase = true) ->
                    SignerHashWrongLength

                message.contains("Invalid Ed25519 signature length", ignoreCase = true) ->
                    InvalidSignatureLength

                // Check this after the more specific "Invalid signer pubkey" and "Invalid Ed25519" checks
                message.contains("Invalid signature", ignoreCase = true) ->
                    InvalidSignature

                // ── Deposit / USDC Transfer ────────────────────────────────────────
                message.contains("Deposit must be > 0", ignoreCase = true) ->
                    DepositMustBePositive

                message.contains("Payment sender mismatch", ignoreCase = true) ->
                    DepositSenderMismatch

                message.contains("Payment asset must be USDC", ignoreCase = true) ->
                    DepositMustBeUsdc

                message.contains("Payment must be to contract", ignoreCase = true) ->
                    DepositReceiverMismatch

                message.contains("Clawback transfer not allowed", ignoreCase = true) ->
                    ClawbackNotAllowed

                message.contains("Asset close not allowed", ignoreCase = true) ->
                    AssetCloseNotAllowed

                // ── Channel Config ─────────────────────────────────────────────────
                message.contains("Payee mismatch", ignoreCase = true) ->
                    PayeeMismatch

                // ── Transaction lifecycle ──────────────────────────────────────────
                message.contains("not confirmed in time", ignoreCase = true) ||
                    message.contains("did not confirm", ignoreCase = true) ->
                    TransactionNotConfirmed

                // ── Client / device ────────────────────────────────────────────────
                message.contains("not found for address:", ignoreCase = true) -> {
                    val address =
                        Regex("not found for address:\\s*(\\S+)")
                            .find(message)
                            ?.groupValues
                            ?.getOrNull(1)
                            .orEmpty()
                    SignerNotFound(address)
                }

                message.contains("signer not found", ignoreCase = true) ->
                    SignerNotFound("")

                message.contains("Session snapshot not found", ignoreCase = true) ||
                    message.contains("Session data not found", ignoreCase = true) ->
                    SessionNotFound

                // ── Network ───────────────────────────────────────────────────────────
                message.contains("broadcast failed", ignoreCase = true) ->
                    parseBroadcastError(message)

                else -> Unknown(message.ifBlank { null }, cause)
            }

        /**
         * Deep-parses a "broadcast failed: {json}" error from the Algorand node.
         *
         * Strategy:
         * 1. Extract the `"message"` field from the JSON body.
         * 2. Decode JSON Unicode escapes (e.g. `\u003c` → `<`) so opcodes like
         *    `\u003c=` become `<=` for reliable pattern matching.
         * 3. Pull out the `opcodes=` section from the Details suffix.
         * 4. Map known AVM opcode patterns to the correct typed [PaymentError]:
         *    - `dig N; <=; assert` → assertion `A <= B` failed (amount exceeded limit):
         *      • In `settle()`: `cumulativeAmount > latestVoucherAmount` → [SettleExceedsVoucher]
         *      • In `updateVoucher()`: `cumulativeAmount > totalDeposit`  → [VoucherExceedsDeposit]
         *    - `dig N; ==; assert` → equality assertion failed:
         *      • In `verifySettleSignature()`: signer key hash mismatch  → [InvalidSignerPublicKey]
         *      • In role checks (`Txn.sender == payer/payee`): may surface as [OnlyPayerCanUpdateVoucher]
         *        etc., but those are caught earlier by the contract message strings above.
         * 5. Fall back to [BroadcastFailed] (strips raw PC + opcodes so users never see mnemonics).
         */
        private fun parseBroadcastError(message: String): PaymentError {
            // Step 1: pull the "message" field out of the JSON body
            val inner =
                Regex(""""message"\s*:\s*"([^"]+)"""")
                    .find(message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim() ?: ""

            // Step 2: decode common JSON Unicode escapes so we can match symbols
            val decoded =
                inner
                    .replace("\\u003c", "<") // < (less-than)
                    .replace("\\u003e", ">") // > (greater-than)
                    .replace("\\u003d", "=") // = (equals)
                    .replace("\\u0026", "&") // &
                    .replace("\\u0027", "'") // '

            // Step 3: extract the opcodes token from "Details: app=..., pc=..., opcodes=..."
            val opcodes =
                Regex("""opcodes\s*=\s*([^\n"\\]+)""")
                    .find(decoded)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim() ?: ""

            return when {
                // ── "dig N; <=; assert" ────────────────────────────────────────
                // The `<=` comparison failed → amount EXCEEDS the on-chain limit.
                //   settle()        → cumulativeAmount > latestVoucherAmount  → SettleExceedsVoucher
                //   updateVoucher() → cumulativeAmount > totalDeposit         → VoucherExceedsDeposit
                // "Settle exceeds latest voucher" is the far more common failure in the settle
                // flow (voucher update not yet confirmed). VoucherExceedsDeposit can only happen
                // if no deposit was ever recorded (totalDeposit == 0).
                opcodes.contains("<=") && opcodes.contains("assert") ->
                    SettleExceedsVoucher

                // ── "dig N; ==; assert" ────────────────────────────────────────
                // The `==` comparison failed → value doesn't match expected hash/address.
                //   verifySettleSignature() → sha512_256(storedPubKey) != data.authorizedSigner
                //   Sender checks → caught earlier by named assert messages (never reach here)
                opcodes.contains("==") && opcodes.contains("assert") ->
                    InvalidSignerPublicKey

                // ── "intc N; >; assert" / "dig N; >; assert" ──────────────────
                // Amount not greater than previous → voucher is not increasing.
                opcodes.contains(">") && opcodes.contains("assert") ->
                    VoucherNotIncreasing

                // ── Fallback: strip PC / opcodes detail so users never see mnemonics ──
                else -> {
                    val clean =
                        decoded
                            .substringBefore(". Details:")
                            .removePrefix("TransactionPool.Remember: ")
                            .trim()
                    BroadcastFailed(clean.ifBlank { message }.take(200))
                }
            }
        }
    }
}
