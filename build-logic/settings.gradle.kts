// build-logic is a separate included build. It needs its own settings file so
// Gradle can resolve the plugin artifacts the convention plugins compile against.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Share the root project's version catalog so a version is declared once,
    // in gradle/libs.versions.toml, and used by both build-logic and the app.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
