// :domain:render — what the video LOOKS like, stated platform-neutrally.
//
// PURE KOTLIN/JVM (spec §4.1 rule 1, enforced by tools/check-architecture.sh).
// This module knows nothing about Media3, GL, codecs, or the filesystem. It
// consumes an :domain:document EditDocument and emits a RenderGraph; :engine:*
// turns that graph into something playable or encodable.
//
// That direction of dependency is the whole point of §4.3: preview and export
// both start from the same RenderGraph value, so "the export does not match the
// preview" stops being a bug class and becomes a test (§12.3).
plugins {
    id("redcut.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api, not implementation: RenderGraph embeds document types (SourceRef,
    // TimeRange, TransformSpec, ...) in its public surface, so a consumer of the
    // graph needs them on its compile classpath without re-declaring this module.
    api(project(":domain:document"))
    // The keyframe resolver (WS K) delegates its interpolation to :core:common's
    // pure math, so this module needs it on its compile classpath.
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
}
