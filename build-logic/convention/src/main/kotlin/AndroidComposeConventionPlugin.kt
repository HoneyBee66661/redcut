import org.gradle.api.Plugin
import org.gradle.api.Project

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
        configureComposeDependencies()
    }
}
