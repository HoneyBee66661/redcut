// Root build file. Deliberately thin: every module's configuration lives in a
// convention plugin under build-logic/, so a module build file is ~10 lines and
// Android/Kotlin configuration exists in exactly one place.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * Spec §4.1 rule 1 and §6.8 rule D1, enforced.
 *
 * Fails the build if `:domain:*` or `:core:common` reference `android.*` or
 * `androidx.*`, or if any module outside `:engine:media3` references a Media3
 * type. Runs as part of `check`, so it is caught locally and in CI — not by
 * code review, which is exactly how these rules get skipped under deadline
 * pressure (risk R11).
 */
val checkArchitectureBoundaries by tasks.registering(Exec::class) {
    group = "verification"
    description = "Enforces the domain purity and Media3 isolation rules (spec §4.1, §6.8 D1)."
    commandLine("bash", rootProject.file("tools/check-architecture.sh").absolutePath)
    workingDir = rootProject.projectDir
}

// Wire into the standard `check` lifecycle so it cannot be forgotten.
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkArchitectureBoundaries)
    }
}
