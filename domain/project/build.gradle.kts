// :domain:project — the project file format and its repository port.
//
// PURE KOTLIN/JVM (spec §4.1 rule 1). The port is an interface with no Android
// types: the implementation needs DataStore and a Context, so it lives in :app.
// Keeping the format here is what makes schemaVersion migrations testable in
// milliseconds instead of on a device (Phase 4.7, FR-6.x).
//
// SKELETON: Phase 0.2 creates the module boundary only — an empty source set and
// the serialization toolchain it will need. Phase 4.7 populates it together with
// the migration tests. Nothing depends on it yet, which is why it is safe to land
// as a boundary first and a body later.
plugins {
    id("redcut.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
