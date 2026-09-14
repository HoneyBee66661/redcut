// :domain:document — the edit document model and its commands.
//
// PURE KOTLIN/JVM. No Android, no AndroidX, not even android.net.Uri (spec §4.1
// rule 1, enforced by tools/check-architecture.sh). Sources are opaque Strings;
// only :engine:* resolves them into real media.
//
// This module is where correctness lives and where the fast test tier (§12.1)
// gets its leverage: everything here is verifiable in milliseconds on the JVM.
plugins {
    id("redcut.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // The frame grid (WS T): SourceRef's exact rate and the frame arithmetic that reads it. Both
    // modules are pure JVM, so the edge is legal — rule 1 forbids a pure module depending on
    // :core:media, :core:ui, :engine:*, :feature:* or :app, and this is none of them.
    implementation(project(":core:common"))
}
