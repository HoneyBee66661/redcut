package com.redcut.engine.nativecore

/**
 * The Kotlin half of the JNI seam (spec §6.5).
 *
 * [System.loadLibrary] runs in this object's initializer, so touching [NativeBridge]
 * at all is what loads `libredcut_core.so` and what runs `JNI_OnLoad`. That has two
 * consequences a reader should know before editing anything here:
 *
 *  - A broken native build fails HERE, at first use, with the class name in the
 *    message — not from inside a frame callback minutes into an edit session.
 *  - Nothing that runs on the JVM-only test tier may touch this object, because
 *    there is no `.so` on a host test classpath. That is why the handshake policy is
 *    in [NativeContract], which is plain Kotlin and is unit-tested.
 *
 * The `external` declarations here are registered explicitly by name from
 * `native/jni/JniOnLoad.cpp`, so these function names are part of a contract that
 * spans two languages: changing one without the other produces a load failure.
 * R8 rename risk is zero for the same reason.
 */
internal object NativeBridge {

    init {
        System.loadLibrary("redcut_core")
    }

    /** The seam proof: `a + b`, computed in C++ (spec §6.5). Phase 5 replaces the body. */
    external fun nativeAdd(a: Int, b: Int): Int

    /** The native ABI version, so Kotlin can detect a stale `.so` (see [NativeContract]). */
    external fun nativeVersionCode(): Int
}
