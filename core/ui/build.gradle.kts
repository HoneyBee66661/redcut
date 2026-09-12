// :core:ui — the design system: theme, tokens, shared composables (Android).
//
// Every Compose host depends on this module, never on each other's UI (spec §4.1
// rule 2). It is the only place a colour, a typography scale or a reusable
// component is defined, which is what keeps the three stages visually consistent
// without a shared "common" module that everything dumps into.
plugins {
    id("redcut.android.compose")
}

android {
    namespace = "com.redcut.core.ui"
}

dependencies {
    implementation(project(":core:common"))
}
