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

android {
    // NOT `com.redcut.engine.native`: `native` is a Java keyword, and AGP rejects
    // it as a namespace ("not a valid Java package name") — which is a
    // configuration-time failure, so it costs nothing now and would cost a
    // confusing detour later. The Gradle module keeps the spec's name; the Java
    // package cannot. `nativecore` names the thing rather than the mechanism.
    namespace = "com.redcut.engine.nativecore"
}

dependencies {
    implementation(project(":domain:render"))
    implementation(project(":core:common"))
}
