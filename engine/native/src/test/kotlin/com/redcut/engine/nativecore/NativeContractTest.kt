package com.redcut.engine.nativecore

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.ErrorCode
import com.redcut.core.common.errorOrNull
import com.redcut.core.common.getOrNull
import com.redcut.core.common.isFailure
import com.redcut.core.common.isSuccess
import org.junit.Test

/**
 * Tests the handshake POLICY, never the library.
 *
 * Nothing in this file may touch [NativeBridge] or [NativeSeam]: both load
 * `libredcut_core.so` in their initializer, and there is no `.so` on a JVM test
 * classpath. That constraint is the reason [NativeContract.verifyHandshake] takes the
 * reported version as a parameter, and it is why this test runs in the ordinary unit
 * test tier instead of needing a device.
 *
 * What it does NOT prove — stated so a green run is not over-read: that the library
 * loads, that `JNI_OnLoad` registered the natives, or that `add` returns the sum.
 * Those need a device; CI proves the build artifacts instead (16 KB page alignment,
 * registered symbols, both ABIs packaged in the APK).
 */
class NativeContractTest {

    @Test
    fun `a matching version is a success`() {
        val result = NativeContract.verifyHandshake(NativeContract.EXPECTED_VERSION_CODE)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `an older native version fails and names both numbers`() {
        val reported = NativeContract.EXPECTED_VERSION_CODE - 1

        val error = NativeContract.verifyHandshake(reported).errorOrNull()

        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("${NativeContract.EXPECTED_VERSION_CODE}")
        assertThat(error.message).contains("$reported")
    }

    @Test
    fun `a newer native version fails too`() {
        // Not "the .so is too old" but "the halves disagree": a library newer than the
        // Kotlin loading it is equally broken, and treating only the older case as an
        // error is how a downgrade install ships.
        val result = NativeContract.verifyHandshake(NativeContract.EXPECTED_VERSION_CODE + 1)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `a mismatch is not a user-facing error code`() {
        // A version mismatch is a build defect that must never reach a user, so it is
        // deliberately not one of the codes a UI switches on to offer a remedy.
        val error = NativeContract.verifyHandshake(-1).errorOrNull()

        assertThat(error!!.code).isEqualTo(ErrorCode.UNKNOWN)
    }

    @Test
    fun `success carries the verified version`() {
        // The value that comes back is the one that was verified, so a caller cannot
        // log a version that was never checked.
        val reported = NativeContract.EXPECTED_VERSION_CODE

        assertThat(NativeContract.verifyHandshake(reported).getOrNull()).isEqualTo(reported)
    }
}
