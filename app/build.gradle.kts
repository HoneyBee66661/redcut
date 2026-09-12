// :app — the composition root.
//
// The only module that sees the whole graph: it wires features to engines and
// owns the Activity shells. Note the absence of a `redcut.android.application`
// plugin id here — :app applies `redcut.android.compose.application`, which
// applies the application convention and then adds Compose. Ordering lives in
// build-logic so this file stays a list of what the app IS, not how it is built.
plugins {
    id("redcut.android.compose.application")
}

android {
    namespace = "com.redcut.app"
}

dependencies {
    // --- Features: the four stages of the product ---------------------------
    // Every feature is listed even while some are still empty skeletons, so the
    // graph edge "app -> feature" is compiled and therefore real. An unlisted
    // feature is a feature that silently cannot be reached.
    implementation(project(":feature:home"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))
    implementation(project(":feature:settings"))

    // --- Domain: read at the composition root, never rendered here ----------
    implementation(project(":domain:document"))
    implementation(project(":domain:render"))

    // --- Core ---------------------------------------------------------------
    implementation(project(":core:common"))
    implementation(project(":core:ui"))

    // --- Engines: bound here and nowhere else -------------------------------
    implementation(project(":engine:media3"))
    implementation(project(":engine:native"))

    // --- AndroidX -----------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
