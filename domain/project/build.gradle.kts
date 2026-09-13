// :domain:project — the project file format and its repository port.
//
// PURE KOTLIN/JVM (spec §4.1 rule 1). The port is an interface with no Android
// types: the implementation needs DataStore and a Context, so it lives in :app.
// Keeping the format here is what makes schemaVersion migrations testable in
// milliseconds instead of on a device (Phase 4.7, FR-6.x).
plugins {
    id("redcut.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api, not implementation: a SavedProject EMBEDS an EditDocument, so a consumer that receives one
    // has to be able to name its type. `implementation` would compile here and fail at the first
    // `project.document.clips` in :feature:editor.
    api(project(":domain:document"))

    implementation(libs.kotlinx.serialization.json)
}
