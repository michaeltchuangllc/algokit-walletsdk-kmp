package com.michaeltchuang.walletsdk.core.railmpp.utils

/**
 * Typed result for payment channel operations.
 *
 * Wraps either a successful value of type [T], or a typed [PaymentError] that maps
 * every on-chain assertion failure to a user-readable message.
 *
 * Usage:
 * ```
 * val result: PaymentResult<String> = MppPayments.openSessionAndDeposit(...).toPaymentResult()
 * result
 *     .onSuccess { txId -> showSuccess("Deposited! TxId: $txId") }
 *     .onFailure { error -> showError(error.userMessage) }
 * ```
 */
sealed class PaymentResult<out T> {
    /** The operation completed successfully. [value] is the returned data (e.g. a transaction ID). */
    data class Success<T>(
        val value: T,
    ) : PaymentResult<T>()

    /** The operation failed. [error] carries the typed [PaymentError] with a user-friendly message. */
    data class Failure(
        val error: PaymentError,
    ) : PaymentResult<Nothing>()

    // ─── Convenience ──────────────────────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    /** Returns the success value, or null if this is a [Failure]. */
    fun getOrNull(): T? = (this as? Success)?.value

    /** Returns the [PaymentError], or null if this is a [Success]. */
    fun errorOrNull(): PaymentError? = (this as? Failure)?.error

    /**
     * Invokes [action] with the success value if this is a [Success].
     * Returns `this` for chaining.
     */
    fun onSuccess(action: (T) -> Unit): PaymentResult<T> {
        if (this is Success) action(value)
        return this
    }

    /**
     * Invokes [action] with the [PaymentError] if this is a [Failure].
     * Returns `this` for chaining.
     */
    fun onFailure(action: (PaymentError) -> Unit): PaymentResult<T> {
        if (this is Failure) action(error)
        return this
    }

    /**
     * Transforms a [Success] value using [transform].
     * A [Failure] passes through unchanged.
     */
    fun <R> map(transform: (T) -> R): PaymentResult<R> =
        when (this) {
            is Success -> Success(transform(value))
            is Failure -> this
        }

    companion object {
        /** Wraps a Kotlin [Result] into a [PaymentResult], parsing any exception via [PaymentError.from]. */
        fun <T> from(result: Result<T>): PaymentResult<T> =
            result.fold(
                onSuccess = { Success(it) },
                onFailure = { Failure(PaymentError.from(it)) },
            )

        /** Creates a [Success] directly. */
        fun <T> success(value: T): PaymentResult<T> = Success(value)

        /** Creates a [Failure] from a [PaymentError]. */
        fun failure(error: PaymentError): PaymentResult<Nothing> = Failure(error)

        /** Creates a [Failure] by parsing the given [Throwable] into a [PaymentError]. */
        fun failure(cause: Throwable): PaymentResult<Nothing> = Failure(PaymentError.from(cause))
    }
}

// ─── Extension ────────────────────────────────────────────────────────────────

/**
 * Converts a Kotlin [Result] to a [PaymentResult], mapping any [Throwable]
 * to the most specific [PaymentError] subtype via [PaymentError.from].
 */
fun <T> Result<T>.toPaymentResult(): PaymentResult<T> = PaymentResult.from(this)
