package com.pixels.enhancer.core.error

/** Controlled result of a major operation. Raw exceptions never cross into the UI layer. */
sealed class OperationResult<out T> {

    data class Success<out T>(val value: T) : OperationResult<T>()

    data class Failure(
        val code: ErrorCode,
        val message: String,
        val cause: Throwable? = null,
    ) : OperationResult<Nothing>()
}

inline fun <T, R> OperationResult<T>.map(transform: (T) -> R): OperationResult<R> = when (this) {
    is OperationResult.Success -> OperationResult.Success(transform(value))
    is OperationResult.Failure -> this
}

/** Thrown by adapters and stages when they already know which [ErrorCode] applies. */
class EnhancerException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
