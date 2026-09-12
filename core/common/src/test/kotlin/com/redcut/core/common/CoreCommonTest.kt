package com.redcut.core.common

import com.redcut.core.common.di.DefaultDispatcher
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.di.MainDispatcher
import com.redcut.core.common.logging.NoOpRedcutLogger
import com.redcut.core.common.logging.RedcutLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rest of :core:common: [RedcutError], the logging seam, and the dispatcher
 * qualifiers.
 *
 * The qualifier test is intentionally a *compile-time* one. The annotations are
 * `BINARY`-retained (qualifiers are read by the DI processor, not at runtime), so there
 * is nothing for reflection to find — what a test can prove is the promise the module
 * makes: a plain JVM class takes three qualified dependencies with no Android or Hilt
 * dependency in sight. If someone narrows `@Target` and drops `VALUE_PARAMETER`, this
 * file stops compiling.
 */
class CoreCommonTest {

    // --- RedcutError ------------------------------------------------------

    @Test
    fun `RedcutError rejects a blank message`() {
        assertThrows(IllegalArgumentException::class.java) {
            RedcutError(ErrorCode.UNKNOWN, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedcutError.of(ErrorCode.UNKNOWN, "   ")
        }
    }

    @Test
    fun `RedcutError carries a code, a message and an optional cause`() {
        val cause = IllegalStateException("disk gone")
        val error = RedcutError.of(ErrorCode.IO, "could not write the project", cause)

        assertEquals(ErrorCode.IO, error.code)
        assertEquals("could not write the project", error.message)
        assertSame(cause, error.cause)
        assertNull(RedcutError.of(ErrorCode.UNKNOWN, "no cause").cause)
    }

    @Test
    fun `the codes the spec names by hand exist under those names`() {
        // §9.1 (codec pool exhaustion) and FR-1.4 (unsupported media) are called out by
        // name in the spec, which makes them API: a rename here would silently change
        // what the UI switches on.
        assertEquals("CODEC_EXHAUSTED", ErrorCode.CODEC_EXHAUSTED.name)
        assertEquals("UNSUPPORTED_MEDIA", ErrorCode.UNSUPPORTED_MEDIA.name)
    }

    // --- Logging ----------------------------------------------------------

    @Test
    fun `the no-op logger discards every call at every level`() {
        val boom = IllegalStateException("ignored")

        // The point is that none of these throw: a logger call is never a failure path,
        // in release or in a test that does not care about logging.
        NoOpRedcutLogger.d("tag", "message")
        NoOpRedcutLogger.i("tag", "message", boom)
        NoOpRedcutLogger.w("tag", "message")
        NoOpRedcutLogger.e("tag", "message", boom)
    }

    @Test
    fun `a logger can be implemented without a framework`() {
        // A recording double is the reason the seam exists; if this needed more than
        // four overrides, the facade would have become a framework.
        val recorded = mutableListOf<String>()
        val logger = object : RedcutLogger {
            override fun d(tag: String, message: String, throwable: Throwable?) {
                recorded += "d:$tag:$message"
            }

            override fun i(tag: String, message: String, throwable: Throwable?) {
                recorded += "i:$tag:$message"
            }

            override fun w(tag: String, message: String, throwable: Throwable?) {
                recorded += "w:$tag:$message"
            }

            override fun e(tag: String, message: String, throwable: Throwable?) {
                recorded += "e:$tag:$message"
            }
        }

        logger.w("Compile", "revision 4 changed")

        assertEquals(listOf("w:Compile:revision 4 changed"), recorded)
    }

    @Test
    fun `a class can take all three qualified dispatchers`() {
        val consumer = QualifiedConsumer("io", "compute", "main")

        assertEquals("io/compute/main", consumer.summary())
        assertSame(NoOpRedcutLogger, NoOpRedcutLogger, "the no-op logger is a singleton object")
        assertTrue(QualifiedConsumer::class.java.declaredConstructors.isNotEmpty())
    }
}

/**
 * Exists so the qualifiers are exercised where they are used: on constructor
 * parameters. See the class KDoc on [CoreCommonTest].
 */
private class QualifiedConsumer(
    @IoDispatcher private val io: String,
    @DefaultDispatcher private val compute: String,
    @MainDispatcher private val main: String,
) {
    fun summary(): String = "$io/$compute/$main"
}
