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
include(":domain:render")

// --- Engines (own the rendering technology; nothing depends on them) -------
include(":engine:media3")
include(":engine:native")

// --- Core ------------------------------------------------------------------
include(":core:common")   // PURE JVM
include(":core:media")
include(":core:ui")
