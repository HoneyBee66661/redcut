// :feature:export — the export sheet, progress and share (Phase 4.3).
//
// It depends on :domain:render for OutputSpec/bitrate presets and on :core:*
// for the rest. The actual encode is not reachable from here: export goes through
// an ExportService that owns :engine:media3, so this feature asks for an export
// and observes progress rather than driving Media3 (§4.2 layering). The thing it
// holds is :core:media's ExportController port — the same shape the editor holds
// PreviewRenderer by.
plugins {
    id("redcut.android.compose")
    // The export sheet owns an injected type (ExportViewModel), so it pays for
    // annotation processing the way :feature:editor has since Phase 1.5.
    id("redcut.android.hilt")
}

android {
    namespace = "com.redcut.feature.export"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(project(":domain:document"))
    // The sheet loads the project the editor autosaves (ProjectStore.latest) to
    // compile the export graph from — the same document, through the same port.
    implementation(project(":domain:project"))
    implementation(project(":domain:render"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    // The ViewModel is constructible in a plain JVM test (ProjectStore and
    // ExportController are ports with fakes; nothing here touches an Android API
    // outside the graph compile, which is pure), so the hand-to-controller wiring
    // is verified in the fast tier.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
