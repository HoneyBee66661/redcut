package com.redcut.domain.document

import org.junit.jupiter.api.Assertions.assertTrue

/** Test fixtures and the invariant checker shared by the command and undo suites. */

internal const val SEC = 1_000_000L

internal fun source(
    id: String,
    durationUs: Long = 10 * SEC,
): SourceRef = SourceRef(
    id = id,
    // Deliberately not a real Uri: the domain must never learn about android.net.Uri
    // (spec §4.1 rule 1). If this file ever needs one, the architecture check fails.
    uri = "content://fixture/$id",
    displayName = "$id.mp4",
    durationUs = durationUs,
    width = 1920,
    height = 1080,
    rotationDegrees = 0,
    frameRate = 30f,
    hasAudio = true,
    videoCodec = "video/avc",
    audioCodec = "audio/mp4a-latm",
)

internal fun clip(
    id: String,
    sourceId: String,
    inUs: Long,
    outUs: Long,
    speed: Float = 1f,
    reverse: Boolean = false,
): Clip = Clip(
    id = id,
    sourceId = sourceId,
    sourceInUs = inUs,
    sourceOutUs = outUs,
    speed = speed,
    reverse = reverse,
)

/**
 * A two-clip document over one source, with the clips source-contiguous so that
 * [MergeClips] has a case that succeeds as well as one that fails.
 */
internal fun sampleDocument(): EditDocument = EditDocument(
    id = "doc-1",
    name = "Sample",
    sources = listOf(source("s1")),
    clips = listOf(
        clip("c1", "s1", 0L, 2 * SEC),
        clip("c2", "s1", 2 * SEC, 5 * SEC),
    ),
)

/**
 * The invariants from spec §7.2, asserted after every command in the property suite.
 *
 * These are the properties that, if a command ever breaks one, produce a document
 * the render pipeline cannot consume -- and the failure would otherwise surface far
 * from the command that caused it.
 */
internal fun assertInvariants(
    doc: EditDocument,
    context: String = "",
) {
    val where = if (context.isEmpty()) "" else " [$context]"

    assertTrue(doc.clips.isNotEmpty()) {
        "invariant 2: clips must never be empty$where"
    }

    doc.clips.forEach { c ->
        assertTrue(c.sourceDurationUs >= Clip.MIN_DURATION_US) {
            "invariant 1: clip ${c.id} is ${c.sourceDurationUs}us, below the " +
                "${Clip.MIN_DURATION_US}us floor$where"
        }
    }

    doc.clips.forEach { c ->
        assertTrue(doc.sourceById(c.sourceId) != null) {
            "invariant 4: clip ${c.id} references missing source ${c.sourceId}$where"
        }
    }

    doc.effects.forEach { effect ->
        val scope = effect.scope
        if (scope is EffectScope.Clip) {
            assertTrue(doc.clipById(scope.clipId) != null) {
                "invariant 3: effect ${effect.id} is scoped to missing clip " +
                    "${scope.clipId}$where"
            }
        }
    }
}
