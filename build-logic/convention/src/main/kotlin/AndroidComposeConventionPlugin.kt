import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `redcut.android.compose` — Android library modules that host Compose UI.
 *
 * Self-sufficient: it applies the Android library + Kotlin + Compose compiler
 * plugins and the shared library configuration, so a feature module applies this
 * one plugin rather than a chain of three (where order would matter).
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        // Kotlin 2.x: the Compose compiler ships as a Kotlin plugin and MUST track
        // the Kotlin version, which is why it is version-referenced from `kotlin`.
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        configureLibraryExtension()
        configureKotlinAndroid()

        dependencies {
            val bom = platform(libs.findLibrary("androidx-compose-bom").get())
            add("implementation", bom)
            add("androidTestImplementation", bom)

            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())

            // Tooling is debug-only: it is large and has no place in release.
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        }
    }
}
