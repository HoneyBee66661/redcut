// :engine:native — the JNI bridge to the C++ render core (Android).
//
// Together with :app, this is one of only two modules allowed to touch JNI
// (spec §4.1 rule 4) — enforced by tools/check-architecture.sh, which rejects
// `System.loadLibrary` and `external fun` anywhere else.
//
// SKELETON: Phase 0.2 creates the module so the boundary exists. Phase 0.4 adds
// native/CMakeLists.txt (the add(a,b) seam proof, 16 KB page alignment, -Werror);
// Phase 3.2 adds the Kotlin wrappers. No `external` declarations are written here
// yet on purpose: a JNI signature committed before the C++ side exists is a
// contract nobody has verified, and it would make the native CI job (parked in
// .github/workflows/ci.yml) look like it had something to test.
plugins {
    id("redcut.android.library")
}

/**
 * Whether to wire the CMake build.
 *
 * NOT laziness, and not a convenience — a hard constraint found the hard way:
 * declaring `externalNativeBuild` makes AGP resolve the SDK LOCATION during
 * configuration, so on a host without an Android SDK the whole project stops
 * configuring, and with it the local gate the entire fast tier rests on
 * (`:domain:*:test`, ktlint, detekt all configure the project before they run).
 *
 * The alternative — no native configuration at all — is worse: CI would build a
 * different project than the one the tests ran against.
 *
 * So the signal is the SDK's presence, which is exactly the thing that decides
 * whether this build is possible. `-Predcut.nativeBuild=true|false` overrides it, so
 * CI never depends on an environment variable it does not control.
 */
val nativeBuildRequested = providers.gradleProperty("redcut.nativeBuild").map(String::toBoolean)
val androidSdkPresent = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
    .any { !providers.environmentVariable(it).orNull.isNullOrBlank() } ||
    rootProject.file("local.properties").exists()

android {
    // NOT `com.redcut.engine.native`: `native` is a Java keyword, and AGP rejects
    // it as a namespace ("not a valid Java package name") — which is a
    // configuration-time failure, so it costs nothing now and would cost a
    // confusing detour later. The Gradle module keeps the spec's name; the Java
    // package cannot. `nativecore` names the thing rather than the mechanism.
    namespace = "com.redcut.engine.nativecore"

    defaultConfig {
        // Both ABIs the app ships (spec §11). A missing .so for one ABI is not a
        // build error — it is an install-time or load-time crash on the devices that
        // use it, which is exactly the class of failure the native CI job exists to
        // catch before a tester finds it.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    if (nativeBuildRequested.getOrElse(androidSdkPresent)) {
        externalNativeBuild {
            cmake {
                // The C++ core lives at the REPO ROOT (spec §11), not inside this
                // module: it is one build shared by whatever ends up binding to it, and
                // Phase 5 grows it there. This module owns the Kotlin side of the
                // boundary and the decision to compile the native side at all.
                path = file("../../native/CMakeLists.txt")
            }
        }
    } else {
        logger.lifecycle(
            ":engine:native — no Android SDK on this host, so the CMake build is NOT " +
                "wired (AGP cannot configure the project with it). The CI `native` job " +
                "compiles it. Pass -Predcut.nativeBuild=true to force it.",
        )
    }
}

dependencies {
    implementation(project(":domain:render"))
    implementation(project(":core:common"))

    // JUnit 4 + Truth: what the other Android modules will use, and what the seam's
    // pure-logic test needs. No Robolectric — the contract test deliberately never
    // touches the library loader (see NativeContractTest).
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
