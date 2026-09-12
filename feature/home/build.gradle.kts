// :feature:home — the project list (FR-6.1–6.4). Phase 4.7.
//
// Features depend on :domain:* and :core:* and NEVER on each other (§4.1 rule 2).
// The rule is what keeps a stage from growing a private shortcut into another
// stage's internals; anything two features need belongs in :core:*.
plugins {
    id("redcut.android.compose")
}

android {
    namespace = "com.redcut.feature.home"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":domain:document"))
}
