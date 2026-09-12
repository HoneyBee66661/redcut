// :core:media — source probing, thumbnail extraction, MediaResourceBroker (Android).
//
// DELIBERATELY NOT A MEDIA3 MODULE (spec §6.8 rule D1): Media3 types are confined
// to :engine:media3, so probing here uses the platform MediaMetadataRetriever.
// The split is not cosmetic — the broker answers questions about a source file
// (duration, rotation, frame size, track layout) and returns domain values; it
// never renders anything. That is what lets the broker be replaced or wrapped
// when the C++ core takes over rendering, without touching the domain.
//
// SKELETON: Phase 0.2 created the module. Phase 1.3 added the probe and the SAF reader
// (this file's dependencies); Phase 1.4 adds the MediaResourceBroker and thumbnails.
plugins {
    id("redcut.android.library")
    // Applied here for the same reason :app and :feature:editor apply it: this module now
    // owns injected types (the probe and the reader). The pure modules never do.
    id("redcut.android.hilt")
}

android {
    namespace = "com.redcut.core.media"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:document"))

    // Probing and reading a content:// URI are I/O; the reader is suspend and runs on
    // Dispatchers.IO. `-android` is what supplies it (see DispatchersModule).
    implementation(libs.kotlinx.coroutines.android)

    // The broker's rules are concurrency behaviour, and the concurrency tests need to control
    // time and dispatchers. This module's tests run in CI's `build` job: an Android module has
    // no test runner on the development host.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
