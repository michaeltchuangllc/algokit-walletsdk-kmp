package com.michaeltchuang.walletsdk.core.foundation

sealed class WalletSdkResult<out T : Any> {
    data class Success<out T : Any>(
        val data: T,
    ) : WalletSdkResult<T>()

    data class Error(
        val exception: Exception,
        val code: Int? = null,
    ) : WalletSdkResult<Nothing>()

    val isSuccess: Boolean
        get() = this is Success

    val isFailed: Boolean
        get() = this is Error

    fun getDataOrNull(): T? =
        when (this) {
            is Success -> data
            is Error -> null
        }

    fun getExceptionOrNull(): Exception? =
        when (this) {
            is Success -> null
            is Error -> exception
        }

    suspend fun <R> use(
        onSuccess: (suspend (T) -> R),
        onFailed: (suspend (Exception, Int?) -> R),
    ): R =
        when (this) {
            is Success -> onSuccess.invoke(data)
            is Error -> onFailed.invoke(exception, code)
        }

    suspend fun <R : Any> map(transform: suspend (T) -> R): WalletSdkResult<R> =
        when (this) {
            is Success -> Success(transform(data))
            is Error -> Error(exception, code)
        }

    override fun toString(): String =
        when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception]"
        }
}
