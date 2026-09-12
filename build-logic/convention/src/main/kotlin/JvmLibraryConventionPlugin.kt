import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * `redcut.jvm.library` — pure-Kotlin modules with NO Android dependency.
 *
 * This is the plugin behind `:domain:*` and `:core:common`, the modules the
 * architecture depends on being testable in milliseconds (spec §12.1). Note what
 * is absent: no `com.android.library`, no AndroidX, nothing that could pull in
 * `android.jar`. If someone adds one, the build breaks here rather than at
 * runtime — and tools/check-architecture.sh catches the source-level case.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        configureKotlinJvm()

        dependencies {
            add("testImplementation", libs.findLibrary("junit-jupiter").get())
            add("testRuntimeOnly", libs.findLibrary("junit-jupiter-engine").get())
            // Property tests carry most of the weight here: cut-left ≡ split+delete,
            // and N random commands then N undos returns the original document.
            add("testImplementation", libs.findLibrary("kotest-property").get())
            add("testImplementation", libs.findLibrary("truth").get())
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // The whole point of this tier is speed; fail loudly if it regresses.
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        }
    }
}
