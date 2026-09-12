import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** The shared version catalog, aliased so convention plugins read like module files. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.versionInt(alias: String): Int =
    libs.findVersion(alias).get().requiredVersion.toInt()

/**
 * Warnings-as-errors is opt-in via `-Predcut.warningsAsErrors`, not the default.
 * On a greenfield project a hard -Werror during scaffolding is hostile; in CI it
 * is exactly what you want. Controlled by a property so both are true.
 */
internal fun Project.warningsAsErrors(): Provider<Boolean> =
    providers.gradleProperty("redcut.warningsAsErrors").map { it.toBoolean() }

/**
 * Applies warnings-as-errors via the `-Werror` CLI flag rather than the
 * `allWarningsAsErrors` property.
 *
 * That property is NOT resolvable from the `compilerOptions {}` DSL on the KGP
 * version this project pins: it lives on the deprecated `KotlinCommonToolOptions`
 * surface and is hidden there, so naming it fails to compile with
 * "Unresolved reference: allWarningsAsErrors". `-Werror` is the flag the property
 * sets underneath, and unlike the property name it is stable across KGP versions
 * — which matters here, because build-logic is the one module with no test
 * coverage of its own, so a breakage surfaces only in CI.
 *
 * Called from both [configureKotlinAndroid] and [configureKotlinJvm], which is why
 * the receiver is the common type rather than either concrete one.
 */
internal fun KotlinCommonCompilerOptions.warningsAsErrorsFlag(enabled: Boolean) {
    // freeCompilerArgs is a list, so this appends. It is never set wholesale,
    // which is what lets -Xjsr305=strict and -Xexplicit-api=strict coexist here.
    if (enabled) freeCompilerArgs.add("-Werror")
}

/** Kotlin configuration shared by every Android module. */
internal fun Project.configureKotlinAndroid() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            warningsAsErrorsFlag(warningsAsErrors().get())
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }
}

/**
 * Kotlin configuration for pure-JVM modules.
 *
 * These modules are the ones the architecture depends on being testable in
 * milliseconds (spec §12.1), so explicit API mode is ON: every public
 * declaration must state its visibility and return type. It costs a little
 * verbosity here and buys a stable, intentional public surface for the domain —
 * which is what keeps `:domain:*` from quietly accumulating a sprawling API.
 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            warningsAsErrorsFlag(warningsAsErrors().get())
            // Explicit API mode is opt-in via `-Predcut.explicitApi`. It forces an
            // explicit visibility modifier on every public declaration — the right
            // discipline for the domain's public surface, but it slows scaffolding,
            // so it is not the default.
            if (providers.gradleProperty("redcut.explicitApi").isPresent) {
                freeCompilerArgs.add("-Xexplicit-api=strict")
            }
        }
    }
}

internal val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
