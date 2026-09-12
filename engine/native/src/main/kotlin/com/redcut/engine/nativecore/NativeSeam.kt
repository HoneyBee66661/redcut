package com.redcut.engine.nativecore

import com.redcut.core.common.RedcutResult

/**
 * The only entry point the rest of the app should use to reach native code.
 *
 * Everything above it talks in Kotlin values; everything below it is JNI. Keeping the
 * boundary in one small object is what makes Phase 5 (the real C++ core) a change
 * behind a stable interface rather than a change scattered through the app — the same
 * reason `:engine:media3` exists for Media3.
 *
 * This is the module's public surface: `:app` wires it and nothing else may reach it
 * (spec §4.1 rule 4 confines JNI to `:app` and `:engine:native`, and
 * `tools/check-architecture.sh` enforces the source-level half of that — including
 * that no other module writes `System.loadLibrary` or `external fun`).
 */
object NativeSeam {

    /**
     * Probes the boundary without committing to it: loads the library, reads the
     * native ABI version, and verifies the handshake.
     *
     * [UnsatisfiedLinkError] is caught here and converted to
     * [NativeStatus.Unavailable] rather than propagated. That is a deliberate
     * boundary decision: a missing `.so` is a build/packaging defect that a release
     * must degrade on (the app still edits and exports — no MVP feature depends on
     * native code, spec §13.2), while a debug build should notice it immediately.
     * A throw here would make the difference between those two cases everybody's
     * problem at every call site.
     *
     * The version is read ONCE and the same value is what gets verified and reported:
     * a handshake checked against a different number than it returns is not a
     * handshake.
     */
    fun status(): NativeStatus {
        val reported = try {
            NativeBridge.nativeVersionCode()
        } catch (e: UnsatisfiedLinkError) {
            return NativeStatus.Unavailable("libredcut_core.so failed to load: ${e.message}")
        }

        return when (val handshake = NativeContract.verifyHandshake(reported)) {
            is RedcutResult.Success -> NativeStatus.Ready(handshake.value)
            is RedcutResult.Failure -> NativeStatus.Unavailable(handshake.error.message)
        }
    }

    /** True when the library loaded and the ABIs agree. */
    fun isReady(): Boolean = status() is NativeStatus.Ready

    /**
     * The seam proof: a sum computed in C++ (spec §6.5, Phase 0 exit criterion).
     *
     * Throws if the library is unavailable, and that is on purpose — a caller has
     * [status] to check first. Returning a silent 0 would make a broken boundary look
     * like a working one.
     */
    fun add(a: Int, b: Int): Int = NativeBridge.nativeAdd(a, b)
}
