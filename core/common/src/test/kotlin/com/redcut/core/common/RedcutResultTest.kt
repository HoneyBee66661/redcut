package com.redcut.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behaviour of [RedcutResult] — the result type every layer boundary uses.
 *
 * The assertions that matter most are the identity ones (`assertSame`): a failure that
 * passes through [map] or [flatMap] must be *the same error value*, not an equivalent
 * copy. Rebuilding it is how a specific cause (a revoked SAF grant, FR-1.5) turns into a
 * generic "something went wrong" by the time it reaches the dialog, and no equality
 * assertion would catch it.
 */
class RedcutResultTest {

    private val boom = RedcutError.of(ErrorCode.DECODE_FAILED, "the decoder refused the stream")
    private val failure: RedcutResult<Int> = boom.asFailure()

    @Test
    fun `a success reports itself and yields its value`() {
        val result: RedcutResult<Int> = 7.asSuccess()

        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals(7, result.getOrNull())
        assertNull(result.errorOrNull())
    }

    @Test
    fun `a failure reports itself and yields no value`() {
        assertTrue(failure.isFailure)
        assertFalse(failure.isSuccess)
        assertNull(failure.getOrNull())
        assertSame(boom, failure.errorOrNull())
    }

    @Test
    fun `map transforms a success`() {
        assertEquals("7!", 7.asSuccess().map { "$it!" }.getOrNull())
    }

    @Test
    fun `map passes a failure through as the same error`() {
        val mapped = failure.map { it * 2 }

        assertTrue(mapped.isFailure)
        assertSame(boom, mapped.errorOrNull(), "the error value must survive untouched")
    }

    @Test
    fun `map never runs its transform on a failure`() {
        var calls = 0

        failure.map {
            calls++
            it
        }

        assertEquals(0, calls, "a transform that ran would be a side effect on a failure path")
    }

    @Test
    fun `flatMap chains successes`() {
        val chained = 2.asSuccess().flatMap { (it * 3).asSuccess() }

        assertEquals(6, chained.getOrNull())
    }

    @Test
    fun `flatMap short-circuits on the first failure`() {
        var calls = 0
        val second = RedcutError.of(ErrorCode.IO, "the project file could not be written")

        val chained = failure.flatMap {
            calls++
            second.asFailure()
        }

        assertEquals(0, calls, "the chain must not continue past a failure")
        assertSame(
            boom,
            chained.errorOrNull(),
            "the FIRST error explains the outcome; a later generic one would hide it",
        )
    }

    @Test
    fun `getOrElse substitutes and hands the fallback the error`() {
        var seen: RedcutError? = null

        val value = failure.getOrElse { error ->
            seen = error
            -1
        }

        assertEquals(-1, value)
        assertSame(boom, seen)
        assertEquals(7, 7.asSuccess().getOrElse { -1 })
    }

    @Test
    fun `onFailure and onSuccess run on their own branch only and return the receiver`() {
        var failures = 0
        var successes = 0

        val fromFailure = failure.onFailure { failures++ }.onSuccess { successes++ }
        val fromSuccess = 7.asSuccess().onSuccess { successes++ }.onFailure { failures++ }

        assertEquals(1, failures)
        assertEquals(1, successes)
        assertSame(failure, fromFailure, "chaining must not copy the result")
        assertEquals(7, fromSuccess.getOrNull())
    }

    @Test
    fun `fold collapses both branches`() {
        assertEquals("ok 7", 7.asSuccess().fold({ "ok $it" }, { "failed: ${it.message}" }))
        assertEquals(
            "failed: the decoder refused the stream",
            failure.fold({ "ok $it" }, { "failed: ${it.message}" }),
        )
    }

    @Test
    fun `asSuccess and asFailure land in the branch they name`() {
        assertEquals(RedcutResult.Success("v"), "v".asSuccess())
        assertEquals(RedcutResult.Failure(boom), boom.asFailure())
    }

    @Test
    fun `a failure is assignable where any value type is expected`() {
        // Failure is declared over Nothing, which is what lets one error value flow
        // through chains that produce different types without a cast. Declared as the
        // concrete `asFailure()` (not a `RedcutResult<Int>` value) because that is the
        // type that carries the covariance: once the static type is RedcutResult<Int>,
        // the assignment below is — correctly — a type error.
        val asStrings: RedcutResult<String> = boom.asFailure()
        val asBooleans: RedcutResult<Boolean> = boom.asFailure()

        assertTrue(asStrings.isFailure)
        assertTrue(asBooleans.isFailure)
        assertSame(boom, asStrings.errorOrNull())
    }
}
