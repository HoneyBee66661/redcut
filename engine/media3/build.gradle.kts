// :engine:media3 — RenderGraph -> Composition, and the Media3 binding (Android).
//
// The ONE module allowed to speak Media3 (§6.8 rule D1, enforced by
// tools/check-architecture.sh). Both paths in spec §4.3 run through here:
// export (Transformer -> MP4) and preview (CompositionPlayer -> SurfaceTexture),
// which is what makes preview/export parity a test rather than a hope (§12.3).
//
// Phase 1.11 landed the preview half: RenderGraphMapper, CompositionPlayerRenderer and
// the ExoPlayerRenderer fallback, behind the PreviewRenderer interface that :core:media
// declares (the UI holds the interface and may not see this module — §6.8 rule D6, with
// §4.1 rule 2 forbidding a feature -> engine edge). Transformer integration is Phase 4.2.
plugins {
    id("redcut.android.library")
}

android {
    namespace = "com.redcut.engine.media3"
}

dependencies {
    implementation(project(":domain:render"))
    implementation(project(":core:common"))
    // The PreviewRenderer contract the UI holds (§6.8 rule D6), and the codec broker every
    // player must be built through (§9.1: "Nothing in the app may construct a MediaCodec (or a
    // Media3 Transformer/CompositionPlayer) except through the broker"). The interface cannot
    // live in this module: a feature may not depend on an engine (§4.1 rule 2), so it lives in
    // the core module the editor already sees and this module implements it. Inward, and fine —
    // an engine may depend on a :core module.
    implementation(project(":core:media"))
    // StateFlow for PreviewRenderer.state, and the scope the renderer's session runs in (§9.2).
    // Not inherited from anywhere: :core:common is pure and declares no coroutines artifact.
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)
}
