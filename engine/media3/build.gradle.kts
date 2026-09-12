// :engine:media3 — RenderGraph -> Composition, and the Media3 binding (Android).
//
// The ONE module allowed to speak Media3 (§6.8 rule D1, enforced by
// tools/check-architecture.sh). Both paths in spec §4.3 run through here:
// export (Transformer -> MP4) and preview (CompositionPlayer -> SurfaceTexture),
// which is what makes preview/export parity a test rather than a hope (§12.3).
//
// SKELETON: Phase 0.2. CompositionPlayerRenderer + PreviewRenderer are Phase 1.11,
// Transformer integration is Phase 4.2.
plugins {
    id("redcut.android.library")
}

android {
    namespace = "com.redcut.engine.media3"
}

dependencies {
    implementation(project(":domain:render"))
    implementation(project(":core:common"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)
}
