package com.michaeltchuang.walletsdk.core.railmpp.utils

/**
 * Typed error hierarchy for the EscrowSessionVaultManager payment channel contract.
 * Every on-chain assert message maps to exactly one subtype with a user-readable message.
 */
sealed class PaymentError {
    abstract val userMessage: String

    // ─── Channel State ────────────────────────────────────────────────────────

    object ChannelNotFound : PaymentError() {
        override val userMessage =
            "Payment channel not found. Please open a new session first."
    }

    // ─── Authorization ────────────────────────────────────────────────────────

    object OnlyPayerCanDeposit : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) account can deposit funds into this channel."
    }

    object OnlyPayerCanUpdateVoucher : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can record a new voucher on-chain."
    }

    object OnlyPayerCanSetSigner : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can update the authorized signer for this channel."
    }

    object OnlyPayerCanRequestClose : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can initiate a forced-close request."
    }

    object OnlyPayerCanWithdraw : PaymentError() {
        override val userMessage =
            "Only the viewer (payer) can withdraw funds after the grace period expires."
    }

    object OnlyPayeeCanSettle : PaymentError() {
        override val userMessage =
            "Only the creator (payee) account can settle earned funds from this channel."
    }

    object OnlyPayeeCanClose : PaymentError() {
        override val userMessage =
            "Only the creator (payee) can close a fully-settled channel."
    }

    object OnlyContractCreatorCanOptIn : PaymentError() {
        override val userMessage =
            "Only the contract deployer can opt the escrow account into USDC."
    }

    // ─── Voucher Validation ───────────────────────────────────────────────────

    object VoucherExceedsDeposit : PaymentError() {
        override val userMessage =
            "Voucher amount exceeds the deposited balance. Please top up the session vault first."
    }

    object VoucherNotIncreasing : PaymentError() {
        override val userMessage =
            "Voucher amount must be higher than the current on-chain value. " +
                "This amount may have already been recorded."
    }

    object VoucherBelowSettled : PaymentError() {
        override val userMessage =
            "Voucher amount is below the already-settled amount and cannot be applied."
    }

    // ─── Settlement ───────────────────────────────────────────────────────────

    object NothingToSettle : PaymentError() {
        override val userMessage =
            "No new funds to settle. The latest voucher has already been fully collected."
    }

    object SettleExceedsVoucher : PaymentError() {
        override val userMessage =
            "Settlement amount exceeds the latest approved voucher. " +
                "Request an updated voucher from the viewer first."
    }

    object UnclaimedFundsRemain : PaymentError() {
        override val userMessage =
            "The channel still has unclaimed voucher funds. " +
                "The creator must settle all earnings before the channel can be closed."
    }

    // ─── Channel Close / Withdraw ─────────────────────────────────────────────

    object CloseNotRequested : PaymentError() {
        override val userMessage =
            "A close has not been requested yet. " +
                "The viewer must call 'Request Close' before withdrawing."
    }

    object CloseGracePeriodActive : PaymentError() {
        override val userMessage =
            "The 15-minute close grace period is still active. " +
                "The creator has until the period expires to settle any remaining funds."
    }

    // ─── Signer / Signature ───────────────────────────────────────────────────

    object SignerPublicKeyNotSet : PaymentError() {
        override val userMessage =
            "No authorized signer public key is registered for this channel. " +
                "Re-open the session to register a signing key."
    }

    object InvalidSignerPublicKey : PaymentError() {
        override val userMessage =
            "The stored signer public key does not match the hash registered on-chain. " +
                "The channel may have been opened with a different key."
    }

    object SignerHashMismatch : PaymentError() {
        override val userMessage =
            "The authorized signer key does not hash to the expected value. " +
                "Ensure the correct public key is being used."
    }

    object SignerHashWrongLength : PaymentError() {
        override val userMessage =
            "Internal error: the signer hash passed to the contract must be exactly 32 bytes."
    }

    object InvalidSignatureLength : PaymentError() {
        override val userMessage =
            "Signature has an invalid length for Ed25519. Expected exactly 64 bytes."
    }

    object InvalidSignature : PaymentError() {
        override val userMessage =
            "Signature verification failed. The voucher may have been signed with the wrong key " +
                "or the message was tampered with."
    }

    // ─── Deposit / USDC Transfer ──────────────────────────────────────────────

    object DepositMustBePositive : PaymentError() {
        override val userMessage = "Deposit amount must be greater than zero."
    }

    object DepositSenderMismatch : PaymentError() {
        override val userMessage =
            "The USDC transfer must originate from the same account as the app call."
    }

    object DepositMustBeUsdc : PaymentError() {
        override val userMessage =
            "Only USDC asset transfers are accepted as deposits into the session vault."
    }

    object DepositReceiverMismatch : PaymentError() {
        override val userMessage =
            "The USDC transfer must be directed to the contract escrow address."
    }

    object ClawbackNotAllowed : PaymentError() {
        override val userMessage =
            "Clawback USDC transfers are not permitted in this contract."
    }

    object AssetCloseNotAllowed : PaymentError() {
        override val userMessage =
            "Closing asset positions as part of a deposit transfer is not permitted."
    }

    // ─── Channel Config ───────────────────────────────────────────────────────

    object PayeeMismatch : PaymentError() {
        override val userMessage =
            "The creator (payee) address does not match the original channel. " +
                "Use a different salt to open a separate channel with a new payee."
    }

    // ─── Transaction Lifecycle ────────────────────────────────────────────────

    object TransactionNotConfirmed : PaymentError() {
        override val userMessage =
            "The transaction was submitted but did not confirm in time. " +
                "Please wait a moment and try again, or check your network connection."
    }

    // ─── Client / Device ─────────────────────────────────────────────────────

    data class SignerNotFound(
        val address: String,
    ) : PaymentError() {
        override val userMessage =
            "Wallet account not found on this device for address: $address. " +
                "Ensure both the viewer and creator accounts are imported."
    }

    object SessionNotFound : PaymentError() {
        override val userMessage =
            "Session data not found on-chain. Please open a new session first."
    }

    // ─── Network ──────────────────────────────────────────────────────────────

    /** [rawMessage] is the trimmed inner message extracted from the node's JSON response. */
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

    data class Unknown(
        val message: String?,
        val cause: Throwable? = null,
    ) : PaymentError() {
        override val userMessage =
            "An unexpected error occurred: ${message?.take(120) ?: "no details available"}"
    }

    companion object {
        fun from(throwable: Throwable): PaymentError = parse(throwable.message ?: "", throwable)

        fun parse(
            message: String,
            cause: Throwable? = null,
        ): PaymentError =
            when {
                message.contains("Channel does not exist", ignoreCase = true) ->
                    ChannelNotFound

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

                message.contains("Voucher exceeds deposit", ignoreCase = true) ->
                    VoucherExceedsDeposit

                message.contains("Voucher not increasing", ignoreCase = true) ->
                    VoucherNotIncreasing

                message.contains("Voucher below settled amount", ignoreCase = true) ->
                    VoucherBelowSettled

                message.contains("Nothing new to settle", ignoreCase = true) ->
                    NothingToSettle

                message.contains("Settle exceeds latest voucher", ignoreCase = true) ->
                    SettleExceedsVoucher

                message.contains("Unclaimed voucher funds remain", ignoreCase = true) ->
                    UnclaimedFundsRemain

                message.contains("Close not requested", ignoreCase = true) ->
                    CloseNotRequested

                message.contains("Close grace period not elapsed", ignoreCase = true) ->
                    CloseGracePeriodActive

                message.contains("Authorized signer public key not set yet", ignoreCase = true) ||
                    message.contains("Authorized signer pubkey required", ignoreCase = true) ->
                    SignerPublicKeyNotSet

                message.contains("Invalid signer pubkey", ignoreCase = true) ->
                    InvalidSignerPublicKey

                message.contains("Authorized signer hash mismatch", ignoreCase = true) ->
                    SignerHashMismatch

                message.contains("Signer hash must be 32 bytes", ignoreCase = true) ->
                    SignerHashWrongLength

                message.contains("Invalid Ed25519 signature length", ignoreCase = true) ->
                    InvalidSignatureLength

                message.contains("Invalid signature", ignoreCase = true) ->
                    InvalidSignature

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

                message.contains("Payee mismatch", ignoreCase = true) ->
                    PayeeMismatch

                message.contains("not confirmed in time", ignoreCase = true) ||
                    message.contains("did not confirm", ignoreCase = true) ->
                    TransactionNotConfirmed

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

                message.contains("broadcast failed", ignoreCase = true) ->
                    parseBroadcastError(message)

                else -> Unknown(message.ifBlank { null }, cause)
            }

        /** Deep-parses a "broadcast failed: {json}" error from the Algorand node into a typed error. */
        private fun parseBroadcastError(message: String): PaymentError {
            val inner =
                Regex(""""message"\s*:\s*"([^"]+)"""")
                    .find(message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim() ?: ""

            val decoded =
                inner
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">")
                    .replace("\\u003d", "=")
                    .replace("\\u0026", "&")
                    .replace("\\u0027", "'")

            val opcodes =
                Regex("""opcodes\s*=\s*([^\n"\\]+)""")
                    .find(decoded)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim() ?: ""

            return when {
                opcodes.contains("<=") && opcodes.contains("assert") ->
                    SettleExceedsVoucher

                opcodes.contains("==") && opcodes.contains("assert") ->
                    InvalidSignerPublicKey

                opcodes.contains(">") && opcodes.contains("assert") ->
                    VoucherNotIncreasing

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
