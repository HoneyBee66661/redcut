// :feature:editor — the stage host, timeline and inspectors (Phase 1.5 onward).
//
// This is where the three stages live and where the timeline Canvas is drawn
// (§13.2 1.5). It reads the domain and issues EditCommands; it does not know
// Media3 or JNI exist — those arrive through the :engine:* modules at the
// composition root.
plugins {
    id("redcut.android.compose")
}

android {
    namespace = "com.redcut.feature.editor"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":domain:document"))
    // The editor renders the compiled graph's *description* (frame size, canvas,
    // output spec) to draw the timeline at the right scale — a pure value from
    // :domain:render, not a Media3 type.
    implementation(project(":domain:render"))
}
