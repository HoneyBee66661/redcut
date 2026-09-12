import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `redcut.android.library` — Android modules with no Compose UI.
 *
 * Used by :engine:media3, :engine:native, :core:media. Compose-enabled modules use
 * `redcut.android.compose` instead, which builds on this same configuration.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        configureLibraryExtension()
        configureKotlinAndroid()
    }
}

/**
 * Shared library configuration, called by both this plugin and the Compose one so
 * the two cannot drift apart.
 */
internal fun Project.configureLibraryExtension() {
    extensions.configure<LibraryExtension> {
        compileSdk = versionInt("compileSdk")
        ndkVersion = libs.findVersion("ndk").get().requiredVersion

        defaultConfig {
            minSdk = versionInt("minSdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }

        buildFeatures {
            buildConfig = false
        }

        // Robolectric and screenshot tests need real resources on the unit-test
        // classpath.
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
            }
        }
    }
}
