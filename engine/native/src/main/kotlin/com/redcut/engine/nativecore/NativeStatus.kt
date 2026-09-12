package com.redcut.engine.nativecore

/**
 * What the native seam can tell a caller about itself.
 *
 * A sealed type rather than a boolean or a nullable: the interesting information is
 * WHY native code is unavailable (the library failed to load, or the two halves
 * disagree about the ABI), and a caller that renders "native: off" without a reason
 * turns a five-second diagnosis into a bug hunt.
 */
sealed interface NativeStatus {

    /** The library loaded and reports a version this Kotlin understands. */
    data class Ready(val versionCode: Int) : NativeStatus

    /** Native code is unusable in this build, for the reason given. */
    data class Unavailable(val reason: String) : NativeStatus
}
