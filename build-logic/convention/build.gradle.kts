plugins {
    `kotlin-dsl`
}

group = "com.redcut.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly: these plugins are on the buildscript classpath at runtime
    // (supplied by the root project's `plugins {}` block), so we only need them
    // to compile against. Bundling them would create version conflicts.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

// Register the convention plugin IDs so modules can apply them by name
// (e.g. `plugins { id("redcut.android.library") }`).
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "redcut.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "redcut.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "redcut.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidComposeApplication") {
            id = "redcut.android.compose.application"
            implementationClass = "AndroidComposeApplicationConventionPlugin"
        }
        register("androidHilt") {
            id = "redcut.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "redcut.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
