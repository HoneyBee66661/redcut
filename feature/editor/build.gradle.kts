// :feature:editor — the stage host, timeline and inspectors (Phase 1.5 onward).
//
// This is where the three stages live and where the timeline Canvas is drawn
// (§13.2 1.5). It reads the domain and issues EditCommands; it does not know
// Media3 or JNI exist — those arrive through the :engine:* modules at the
// composition root.
plugins {
    id("redcut.android.compose")
    // The editor is the first module that owns an injected type (EditorViewModel), so
    // it is one of only two modules paying for annotation processing.
    id("redcut.android.hilt")
}

android {
    namespace = "com.redcut.feature.editor"
}

dependencies {
    implementation(project(":domain:project"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":domain:document"))
    // The editor renders the compiled graph's *description* (frame size, canvas,
    // output spec) to draw the timeline at the right scale — a pure value from
    // :domain:render, not a Media3 type.
    implementation(project(":domain:render"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    // The tool strip's per-tool icons (FR-2.2's restyle to the LibreCuts icon-above-label pattern).
    // The core icon set is too small to give every Cut tool a distinct glyph; `extended` is the
    // catalogue, and it is declared HERE rather than in :core:ui because only the editor draws it.
    implementation(libs.androidx.compose.material.icons.extended)

    // The import path (FR-1). The editor depends on the PORT (MediaSourceReader) and never
    // on the platform implementation: Hilt binds SafMediaSourceReader at the composition
    // root, so a JVM test supplies fixtures and the screen knows nothing about SAF.
    implementation(project(":core:media"))

    // The ViewModel is constructible in a plain JVM test (it takes a RedcutLogger and
    // touches no Android API), so the stage/undo/import wiring is verified in the fast tier
    // rather than only on a device. `coroutines-test` is what lets that test control the
    // dispatcher the import runs on.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
