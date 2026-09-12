// :app — the composition root.
//
// The only module that sees the whole graph: it wires features to engines and
// owns the Activity shells. Note the absence of a `redcut.android.application`
// plugin id here — :app applies `redcut.android.compose.application`, which
// applies the application convention and then adds Compose. Ordering lives in
// build-logic so this file stays a list of what the app IS, not how it is built.
plugins {
    id("redcut.android.compose.application")
    // Hilt is applied here and in :feature:editor — the only two modules that own or
    // consume injected types today. `:domain:*` and `:core:common` never apply it
    // (they take their collaborators as constructor parameters), which is what keeps
    // the fast tier free of annotation processing.
    id("redcut.android.hilt")
    // Baseline profiles (spec §11, NFR-1). Applied HERE and not on the modules that are
    // profiled: the profile is a property of the SHIPPED APK, so the module that produces
    // the APK is the one that owns it. The extension is configured below the `android`
    // block — `mergeIntoMain` puts the generated rules in `src/main/baselineProfiles`,
    // where they are a reviewed, checked-in build input rather than a build artifact.
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.redcut.app"
}

// Single-line on purpose: a multi-line top-level lambda in a `.kts` file is what ktlint's
// indent rule rewrites — it re-indented this entire file (dependencies block included) to
// one space and called that the correct style. The rule is right about Kotlin sources and
// wrong about Gradle scripts whose bodies are DSL lambdas; the workaround is to not give it
// a multi-line top-level block, and to keep the explanation in the comment above instead.
//
// `mergeIntoMain` puts the generated rules at
// `app/src/main/generated/baselineProfiles/baseline-prof.txt` — the profile becomes a
// REVIEWED, CHECKED-IN build input rather than a build artifact. That exact path was found
// by the first real run of .github/workflows/baseline-profile.yml, which had been looking
// in `src/main/baselineProfiles/` and reported an empty profile while one existed; the
// workflow now searches both and assumes neither.
baselineProfile { mergeIntoMain = true }

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
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Navigation (spec §7.1) --------------------------------------------
    implementation(libs.androidx.navigation.compose)
    // Wires `hiltViewModel()` to the Hilt graph, which is what lets a feature screen
    // receive its ViewModel without the feature knowing Hilt exists beyond that call.
    implementation(libs.androidx.hilt.navigation.compose)

    // --- Persistence (spec §10, §3.1 "Preferences: DataStore") --------------
    implementation(libs.androidx.datastore.preferences)

    // --- Async --------------------------------------------------------------
    implementation(libs.kotlinx.coroutines.android)

    // --- Logging (spec §3.1: "Timber + a release-mode no-op tree") ----------
    implementation(libs.timber)

    // --- Baseline profile (spec §11, NFR-1) ---------------------------------
    // The generator. :benchmark instruments this app; nothing is packaged from it.
    baselineProfile(project(":benchmark"))
    // Installs the profile on API 26..30. From API 31 the platform does it; below that it
    // does not, and a profile that is never installed is a file in an APK and nothing more.
    implementation(libs.androidx.profileinstaller)
}
