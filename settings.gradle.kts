pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Fail the build if a module declares its own repository. All repositories
    // live here so dependency resolution is auditable in one file.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RedCut"

// Convention plugins. `includeBuild` makes them available as plugin IDs to
// every module below without publishing anything.
includeBuild("build-logic")

// --- Application -----------------------------------------------------------
include(":app")

// --- Features (depend on :domain:* and :core:*, never on each other) -------
include(":feature:home")
include(":feature:editor")
include(":feature:export")
include(":feature:settings")

// --- Domain (PURE JVM — no Android dependency, enforced by CI) -------------
// Spec §4.1 rule 1. See tools/check-domain-purity.sh.
include(":domain:document")
include(":domain:project")
include(":domain:render")

// --- Engines (own the rendering technology; nothing depends on them) -------
include(":engine:media3")
include(":engine:native")

// --- Benchmark (test-only, never shipped) ----------------------------------
// The baseline profile generator (spec §11 "Baseline Profiles", §13 task 0.6): a
// `com.android.test` module that instruments a device to produce the profile shipped in
// the APK. It depends on :app; :app never depends on it, so it cannot leak into the
// product — which is the whole reason it is a module and not a test source set.
include(":benchmark")

// --- Core ------------------------------------------------------------------
include(":core:common")   // PURE JVM
include(":core:media")
include(":core:ui")
