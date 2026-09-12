package com.redcut.app.logging

import com.redcut.app.BuildConfig
import com.redcut.core.common.logging.RedcutLogger
import timber.log.Timber

/**
 * The bridge from the framework-free [RedcutLogger] to Timber (spec §3.1 "Logging:
 * Timber + a release-mode no-op tree", §4.1).
 *
 * This is the only place in the project where the two meet. `:core:common` and
 * `:domain:*` log through the interface because they cannot see Timber (Timber is an
 * Android library and those modules are not), and `:feature:*` receives a
 * `RedcutLogger` by injection rather than reaching for `Timber.d` directly — which is
 * what keeps a feature testable on the JVM with [com.redcut.core.common.logging.NoOpRedcutLogger].
 *
 * The tag is passed through per call rather than held as a field: a logger that
 * remembers a tag is a logger that logs the wrong one after a refactor.
 */
internal object TimberRedcutLogger : RedcutLogger {

    override fun d(tag: String, message: String, throwable: Throwable?) =
        Timber.tag(tag).d(throwable, message)

    override fun i(tag: String, message: String, throwable: Throwable?) =
        Timber.tag(tag).i(throwable, message)

    override fun w(tag: String, message: String, throwable: Throwable?) =
        Timber.tag(tag).w(throwable, message)

    override fun e(tag: String, message: String, throwable: Throwable?) =
        Timber.tag(tag).e(throwable, message)
}

/**
 * A tree that logs nothing, planted in release builds.
 *
 * Spec §3.1 asks for "Timber + a release-mode no-op tree", and the reason to plant an
 * explicit no-op rather than simply planting nothing is diagnosis: with no tree,
 * "why is this line not in logcat?" has two possible answers (no tree planted, or the
 * line never ran). With this tree, the policy is stated in one place, and a stray
 * `Timber.d` in release is visibly a deliberate no-op instead of an accident.
 *
 * It also keeps the release path honest for the privacy posture in §3.1: nothing is
 * logged and nothing is forwarded anywhere until a crash reporter is attached on
 * purpose (which will want this tree replaced, not bypassed).
 */
internal object NoOpReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
}

/** Plants exactly one tree, chosen by build type. Called once, from [com.redcut.app.RedcutApp]. */
internal fun plantLoggingTrees() {
    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    } else {
        Timber.plant(NoOpReleaseTree)
    }
}
