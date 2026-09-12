package com.redcut.engine.nativecore

import com.redcut.core.common.ErrorCode
import com.redcut.core.common.RedcutError
import com.redcut.core.common.RedcutResult
import com.redcut.core.common.asFailure
import com.redcut.core.common.asSuccess

/**
 * The version handshake across the JNI boundary (spec §6.5).
 *
 * ### Why this exists at all
 *
 * The Kotlin and C++ halves of the seam are compiled separately, by different
 * toolchains, and can legitimately disagree inside one APK: a stale `.so` from an
 * incremental build, a `clean` that skipped one variant, a bad merge of generated
 * output, a device installing an older APK over a newer one. Every other check the
 * build performs is blind to that — the C++ compiles, the Kotlin compiles, the tests
 * pass, and then the editor crashes on the first frame because a signature changed.
 *
 * Comparing one integer at load time converts that into a named failure.
 *
 * ### Deliberately pure Kotlin
 *
 * [verifyHandshake] takes the reported version as a parameter instead of reading it
 * from [NativeBridge]. Two reasons, both practical: the fast test tier has no `.so`
 * and must still be able to test this policy, and a function whose input is a
 * parameter is testable without a device — which is the same argument that keeps
 * `:domain:*` framework-free.
 */
internal object NativeContract {

    /**
     * The ABI version this Kotlin expects.
     *
     * MUST equal `redcut::seam::kVersionCode` in `native/include/redcut/seam.h`. The
     * two are checked against each other by the native CI job, which greps both files
     * — a comment asking people to keep them in step is not a mechanism.
     */
    const val EXPECTED_VERSION_CODE: Int = 1

    /**
     * Verifies the version the loaded library reports, returning it when it matches.
     *
     * Returning the VERIFIED value rather than `Unit` is what lets a caller report the
     * number that was actually checked — a handshake whose success carries no value
     * invites the caller to read the version a second time, and the one thing a
     * handshake must not do is validate one number and publish another.
     *
     * On mismatch the failure is [ErrorCode.UNKNOWN] rather than a new enum entry, and
     * that is deliberate: the error vocabulary in `:core:common` exists for outcomes a
     * user can be told about (a revoked permission, an unsupported codec), while a
     * version mismatch is a build defect that must never reach a user. Giving it a
     * user-facing code would invite a UI to render it, which is the wrong response —
     * the message exists so the log names both numbers.
     */
    fun verifyHandshake(reported: Int): RedcutResult<Int> = if (reported == EXPECTED_VERSION_CODE) {
        reported.asSuccess()
    } else {
        RedcutError(
            code = ErrorCode.UNKNOWN,
            message = "native ABI mismatch: kotlin expects $EXPECTED_VERSION_CODE, " +
                "libredcut_core.so reports $reported — stale or mismatched build artifacts",
        ).asFailure()
    }
}
