package com.redcut.core.common.logging

/**
 * The logging seam (spec §4.1 "logging", §3's Timber row).
 *
 * ### Why an interface instead of calling Timber
 *
 * Timber is an Android library, and this module is not (§4.1 rule 1). The app narrows
 * the gap at the composition root: `:app` installs a `Timber` tree whose `RedcutLogger`
 * implementation forwards, and — per §3 — that tree is a no-op in release builds, so the
 * release artifact carries no logging behaviour to remove later.
 *
 * Test code uses [NoOpRedcutLogger] or its own recording double, which is the other
 * half of the point: a class under test takes a [RedcutLogger] and stops needing a
 * Robolectric runner just to survive a log call.
 *
 * The surface is deliberately four levels and no more. A facade that mirrors a logging
 * framework's full API is a framework, and one that offers `v`/`wtf` without a policy
 * for when to use them produces logs nobody reads.
 */
interface RedcutLogger {

    /** Debug detail: useful while developing, dropped in release. */
    fun d(tag: String, message: String, throwable: Throwable? = null)

    /** Normal lifecycle events worth seeing in a release log. */
    fun i(tag: String, message: String, throwable: Throwable? = null)

    /** Recoverable problems: something was retried, substituted, or skipped. */
    fun w(tag: String, message: String, throwable: Throwable? = null)

    /** Failures the user will notice, with the throwable attached when there is one. */
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * A logger that logs nothing.
 *
 * Used in release builds (§3: "Timber + a release-mode no-op tree") and in tests. It is
 * an `object` rather than a class so that "we deliberately log nothing here" is
 * allocatable-free and greppable, and so a caller cannot accidentally hold mutable state
 * in a logger that is supposed to have no behaviour.
 */
object NoOpRedcutLogger : RedcutLogger {
    override fun d(tag: String, message: String, throwable: Throwable?) = Unit

    override fun i(tag: String, message: String, throwable: Throwable?) = Unit

    override fun w(tag: String, message: String, throwable: Throwable?) = Unit

    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
