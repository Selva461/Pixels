package com.pixels.enhancer.core.error

import kotlin.coroutines.cancellation.CancellationException

object ErrorMapper {

    /**
     * Converts any throwable into a controlled failure.
     *
     * [fallback] names the phase that failed (e.g. ANALYSIS_FAILED) and is used when the error
     * carries no more specific code.
     */
    fun toFailure(error: Throwable, fallback: ErrorCode): OperationResult.Failure = when (error) {
        is EnhancerException -> OperationResult.Failure(error.code, error.message ?: error.code.name, error)
        is OutOfMemoryError -> OperationResult.Failure(ErrorCode.OUT_OF_MEMORY, "Not enough memory to process this image", error)
        else -> OperationResult.Failure(fallback, error.message ?: fallback.name, error)
    }
}

/**
 * Runs [block] at an application boundary and converts failures (including OutOfMemoryError)
 * into [OperationResult.Failure]. Cancellation is rethrown so coroutine cancellation keeps working.
 */
inline fun <T> runControlled(fallback: ErrorCode, block: () -> T): OperationResult<T> = try {
    OperationResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    ErrorMapper.toFailure(error, fallback)
}
