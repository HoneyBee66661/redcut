// :feature:export — the export sheet, progress and share (Phase 4.x).
//
// It depends on :domain:render for OutputSpec/bitrate presets and on :core:*
// for the rest. The actual encode is not reachable from here: export goes through
// an ExportService that owns :engine:media3, so this feature asks for an export
// and observes progress rather than driving Media3 (§4.2 layering).
plugins {
    id("redcut.android.compose")
}

android {
    namespace = "com.redcut.feature.export"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":domain:document"))
    implementation(project(":domain:render"))
}
