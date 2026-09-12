import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `redcut.android.application` — the :app module.
 *
 * Everything Android-related is configured here rather than in app/build.gradle.kts,
 * so the module file stays a short list of dependencies.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<ApplicationExtension> {
            compileSdk = versionInt("compileSdk")
            // AGP needs to know which NDK to use for :engine:native.
            ndkVersion = libs.findVersion("ndk").get().requiredVersion

            defaultConfig {
                minSdk = versionInt("minSdk")
                targetSdk = versionInt("targetSdk")
                versionCode = 1
                versionName = "0.1.0"
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

                // Ship only the ABIs we support. A universal APK is how you blow
                // the 25 MB budget (NFR-7).
                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                }
            }

            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
                debug {
                    // Never minify debug: it destroys stack traces and slows the loop.
                    isMinifyEnabled = false
                }
            }

            compileOptions {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }

            buildFeatures {
                compose = true
                buildConfig = true
            }

            packaging {
                resources {
                    excludes += setOf(
                        "/META-INF/{AL2.0,LGPL2.1}",
                        "/META-INF/LICENSE*",
                    )
                }
            }

            testOptions {
                unitTests {
                    isIncludeAndroidResources = true
                }
            }
        }

        configureKotlinAndroid()
    }
}
