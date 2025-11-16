package com.michaeltchuang.walletsdk.core.foundation.utils

sealed class AlgoKitResult<out T : Any> {

    data class Success<out T : Any>(val data: T) : AlgoKitResult<T>()
    data class Error(val exception: Exception, val code: Int? = null) : AlgoKitResult<Nothing>()

    val isSuccess: Boolean
        get() = this is Success

    val isFailed: Boolean
        get() = this is Error

    fun getDataOrNull(): T? {
        return when (this) {
            is Success -> data
            is Error -> null
        }
    }

    fun getExceptionOrNull(): Exception? {
        return when (this) {
            is Success -> null
            is Error -> exception
        }
    }

    suspend fun <R> use(onSuccess: (suspend (T) -> R), onFailed: (suspend (Exception, Int?) -> R)): R {
        return when (this) {
            is Success -> onSuccess.invoke(data)
            is Error -> onFailed.invoke(exception, code)
        }
    }

    suspend fun <R : Any> map(transform: suspend (T) -> R): AlgoKitResult<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Error -> Error(exception, code)
        }
    }

    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception]"
        }
    }
}
