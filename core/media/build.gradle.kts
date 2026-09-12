// :core:media — source probing, thumbnail extraction, MediaResourceBroker (Android).
//
// DELIBERATELY NOT A MEDIA3 MODULE (spec §6.8 rule D1): Media3 types are confined
// to :engine:media3, so probing here uses the platform MediaMetadataRetriever.
// The split is not cosmetic — the broker answers questions about a source file
// (duration, rotation, frame size, track layout) and returns domain values; it
// never renders anything. That is what lets the broker be replaced or wrapped
// when the C++ core takes over rendering, without touching the domain.
//
// SKELETON: Phase 0.2. The broker and thumbnail pipeline are Phase 1.4.
plugins {
    id("redcut.android.library")
}

android {
    namespace = "com.redcut.core.media"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:document"))
}
