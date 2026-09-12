// :core:common — the small, framework-free things every layer needs.
//
// PURE KOTLIN/JVM (spec §4.1 rule 1, enforced by tools/check-architecture.sh): no
// Android, no AndroidX, no Hilt. That is not minimalism for its own sake — it is what
// keeps :domain:* testable in milliseconds (§12.1) and what lets a :feature:* module
// talk about results, dispatchers and logging without pulling a UI framework into its
// tests.
//
// What belongs here, per §4.1: result types, dispatcher qualifiers, logging. What does
// not: anything that needs a Context, a clock, or a codec.
plugins {
    id("redcut.jvm.library")
}

dependencies {
    // api, not implementation: :core:* and :feature:* annotate their OWN constructors
    // with the qualifiers declared here, so the @Qualifier meta-annotation has to be on
    // their compile classpath. Declaring it `implementation` would force every consumer
    // to depend on javax.inject itself, which is exactly the duplication this module
    // exists to avoid.
    api(libs.javax.inject)
}
