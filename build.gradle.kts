// Root build file. Deliberately thin: every module's configuration lives in a
// convention plugin under build-logic/, so a module build file is ~10 lines and
// Android/Kotlin configuration exists in exactly one place.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
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
 * Static analysis, applied to every module.
 *
 * Deliberately here and not in each module's build file: detekt and ktlint are
 * project-wide policy, and a policy that a new module has to remember to opt into
 * is a policy with holes. Applying it from the root also means the pure-JVM
 * modules and the Android ones are held to the same standard — which matters
 * because the domain is where correctness lives and where a reviewer's attention
 * is best spent on semantics rather than on formatting.
 *
 * `check` picks both up automatically (their plugins wire themselves in), so this
 * cannot be forgotten in a module that is added later.
 *
 * Settings live in .editorconfig (ktlint) and detekt's own defaults; neither is
 * configured here, because configuration in a build script is configuration that
 * only builders can read.
 */
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // detekt's own defaults stay on; config/detekt/detekt.yml carries only the
        // deliberate deviations, each with its reasoning written next to it.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }
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
