// :feature:settings — app settings (storage location, defaults, about).
plugins {
    id("redcut.android.compose")
}

android {
    namespace = "com.redcut.feature.settings"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
}
