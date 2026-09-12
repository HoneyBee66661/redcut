import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose dependencies shared by every Compose-enabled module.
 *
 * Extracted from `AndroidComposeConventionPlugin` so the library modules
 * (`redcut.android.compose`) and the application (`redcut.android.compose.application`)
 * cannot drift apart. The BOM is the only thing pinning Compose artifact versions,
 * so a module that applied the compiler plugin but forgot the BOM would silently
 * resolve each artifact to whatever version Gradle walked into — a mismatch that
 * shows up as an obscure runtime crash, not a build error.
 *
 * Callers must have applied the Compose compiler plugin first; this function adds
 * dependencies only.
 */
internal fun Project.configureComposeDependencies() {
    dependencies {
        val bom = platform(libs.findLibrary("androidx-compose-bom").get())
        add("implementation", bom)
        add("androidTestImplementation", bom)

        add("implementation", libs.findLibrary("androidx-compose-ui").get())
        // Explicit, not transitive: :core:ui uses foundation APIs (isSystemInDarkTheme,
        // layout) directly, and relying on material3 to re-export foundation is the
        // kind of accident that breaks on a material3 minor bump.
        add("implementation", libs.findLibrary("androidx-compose-foundation").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("androidx-compose-material3").get())

        // Tooling is debug-only: it is large and has no place in release.
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
    }
}
