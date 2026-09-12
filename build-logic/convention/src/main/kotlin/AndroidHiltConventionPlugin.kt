import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `redcut.android.hilt` — adds Hilt + KSP to an Android module.
 *
 * Kept separate from the library plugin so only modules that actually own or
 * consume injected types pay the annotation-processing cost. Note that
 * `:domain:*` and `:core:common` never apply this: they are pure JVM and take
 * their collaborators as constructor parameters.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
        }
    }
}
