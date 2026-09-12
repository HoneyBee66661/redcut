package com.redcut.core.common

/**
 * The result of an operation that can fail for a reason the caller must handle
 * (spec §4.1: "Result types, dispatchers, logging").
 *
 * ### Why not Kotlin's `Result`
 *
 * `kotlin.Result` cannot be a return type in a public API without `@PublishedApi`
 * gymnastics, it can only carry a [Throwable] — and most of this app's failures are not
 * exceptional: a media probe that rejects a codec, a SAF permission that was revoked, a
 * codec pool that is briefly exhausted. Those are outcomes to display, not crashes to
 * report, and they need a stable [ErrorCode] a UI can switch on rather than a message
 * string.
 *
 * ### Why not exceptions for the same cases
 *
 * An exception at a layer boundary in this app crosses a coroutine, a message queue, or
 * a JNI edge, and arrives somewhere that cannot do anything useful with it. A value
 * that the caller has to look at cannot be forgotten as easily as a `catch`.
 *
 * [Success] holds the value; [Failure] holds a [RedcutError]. [Failure] is declared
 * over `Nothing` so that a `RedcutResult<T>` is a subtype of `RedcutResult<R>` for any
 * failure, which is what makes [map] and [flatMap] total without an `else` branch.
 */
sealed interface RedcutResult<out T> {

    /** The operation produced [value]. */
    data class Success<T>(val value: T) : RedcutResult<T>

    /** The operation failed with [error]. */
    data class Failure(val error: RedcutError) : RedcutResult<Nothing>
}

/** True when this is a [RedcutResult.Success]. */
val RedcutResult<*>.isSuccess: Boolean get() = this is RedcutResult.Success

/** True when this is a [RedcutResult.Failure]. */
val RedcutResult<*>.isFailure: Boolean get() = this is RedcutResult.Failure

/** The value, or null when this failed. */
fun <T> RedcutResult<T>.getOrNull(): T? = when (this) {
    is RedcutResult.Success -> value
    is RedcutResult.Failure -> null
}

/** The error, or null when this succeeded. */
fun RedcutResult<*>.errorOrNull(): RedcutError? = when (this) {
    is RedcutResult.Success -> null
    is RedcutResult.Failure -> error
}

/**
 * The value, or [fallback] applied to the error.
 *
 * The fallback takes the error rather than a bare default so that a call site which
 * substitutes something still has a reason to log — losing the error is how a failure
 * becomes invisible.
 */
fun <T> RedcutResult<T>.getOrElse(fallback: (RedcutError) -> T): T = when (this) {
    is RedcutResult.Success -> value
    is RedcutResult.Failure -> fallback(error)
}

/** Transforms a success; a failure passes through unchanged, error and all. */
inline fun <T, R> RedcutResult<T>.map(transform: (T) -> R): RedcutResult<R> = when (this) {
    is RedcutResult.Success -> RedcutResult.Success(transform(value))
    is RedcutResult.Failure -> this
}

/**
 * Chains an operation that can itself fail.
 *
 * Short-circuits on the first failure: the first error is the one that explains the
 * outcome, and replacing it with a later, more generic one is how a specific cause
 * (say, a revoked SAF permission) becomes "something went wrong".
 */
inline fun <T, R> RedcutResult<T>.flatMap(transform: (T) -> RedcutResult<R>): RedcutResult<R> =
    when (this) {
        is RedcutResult.Success -> transform(value)
        is RedcutResult.Failure -> this
    }

/** Runs [action] on the error, when there is one. Returns the receiver, unchanged. */
inline fun <T> RedcutResult<T>.onFailure(action: (RedcutError) -> Unit): RedcutResult<T> {
    if (this is RedcutResult.Failure) action(error)
    return this
}

/** Runs [action] on the value, when there is one. Returns the receiver, unchanged. */
inline fun <T> RedcutResult<T>.onSuccess(action: (T) -> Unit): RedcutResult<T> {
    if (this is RedcutResult.Success) action(value)
    return this
}

/**
 * Collapses both branches into one value.
 *
 * Preferred at UI boundaries, where both outcomes render something and two separate
 * `when` blocks would drift apart.
 */
inline fun <T, R> RedcutResult<T>.fold(onSuccess: (T) -> R, onFailure: (RedcutError) -> R): R =
    when (this) {
        is RedcutResult.Success -> onSuccess(value)
        is RedcutResult.Failure -> onFailure(error)
    }

/** Lifts any value into a success. */
fun <T> T.asSuccess(): RedcutResult<T> = RedcutResult.Success(this)

/** Lifts an error into a failure. */
fun RedcutError.asFailure(): RedcutResult<Nothing> = RedcutResult.Failure(this)
