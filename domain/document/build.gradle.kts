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
}
