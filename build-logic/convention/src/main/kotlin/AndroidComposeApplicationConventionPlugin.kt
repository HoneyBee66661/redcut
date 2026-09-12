import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `redcut.android.compose.application` — :app, the one module that is both an
 * Android application and a Compose host.
 *
 * It exists because `redcut.android.compose` applies `com.android.library`, and
 * AGP refuses a module that applies both `com.android.application` and
 * `com.android.library`. Rather than make :app chain three plugins by hand — and
 * then duplicate the Compose BOM wiring that the library plugin owns — this
 * plugin applies `redcut.android.application` (which owns the application-level
 * configuration) and then adds the Compose compiler plus the shared dependency
 * list from `configureComposeDependencies()`.
 *
 * :app applies this ONE plugin id; the ordering inside is fixed here, not in the
 * module build file.
 */
class AndroidComposeApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("redcut.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        configureComposeDependencies()
    }
}
