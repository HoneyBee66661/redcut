package com.redcut.domain.render

import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.videoTrack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Time constants, document builders and the graph invariant checker shared by the
 * :domain:render suites.
 *
 * The builders exist so a test can state the thing it is about — "a disabled clip
 * before it", "a document-scoped range that starts mid-layer" — in one visible line
 * instead of ten constructor arguments. Everything the tests assert on is either
 * derived from these builders or read back off the compiled graph; nothing here
 * re-implements a rule the compiler owns.
 */

internal const val SEC: Long = 1_000_000L
internal const val MS: Long = 1_000L

internal fun source(id: String, durationUs: Long = 10 * SEC): SourceRef = SourceRef(
    id = id,
    // Deliberately not a real Uri: the domain must never learn about android.net.Uri
    // (spec §4.1 rule 1). If this file ever needs one, the architecture check fails.
    uri = "content://fixture/$id",
    displayName = "$id.mp4",
    durationUs = durationUs,
    width = 1920,
    height = 1080,
    hasAudio = true,
    videoCodec = "video/avc",
    audioCodec = "audio/mp4a-latm",
)

/**
 * One clip. Every parameter with a default is a field the document model does NOT
 * validate (`volume`, the fade pair), or one whose default hides the property under
 * test — so a test that cares must say so explicitly.
 */
internal fun clip(
    id: String,
    sourceId: String,
    inUs: Long,
    outUs: Long,
    speed: Float = 1f,
    enabled: Boolean = true,
    volume: Float = 1f,
    muted: Boolean = false,
    fadeInMs: Long = 0L,
    fadeOutMs: Long = 0L,
    reverse: Boolean = false,
): Clip = Clip(
    id = id,
    sourceId = sourceId,
    sourceInUs = inUs,
    sourceOutUs = outUs,
    speed = speed,
    enabled = enabled,
    volume = volume,
    muted = muted,
    fadeInMs = fadeInMs,
    fadeOutMs = fadeOutMs,
    reverse = reverse,
)

internal fun document(
    clips: List<Clip>,
    sources: List<SourceRef> = listOf(source("s1")),
    effects: List<AppliedEffect> = emptyList(),
    revision: Long = 0L,
): EditDocument = EditDocument(
    id = "doc-test",
    name = "Test",
    sources = sources,
    tracks = listOf(videoTrack(clips)),
    effects = effects,
    revision = revision,
)

/**
 * Three clips over one source, contiguous in the source, covering 7 s of timeline:
 * c1 = 0–2 s, c2 = 2–4 s, c3 = 4–7 s.
 *
 * Three clips rather than two is deliberate: a seam in the middle of the timeline and
 * a seam at the end of it behave differently for dissolves (any start except the very
 * first is a seam; the last clip's *end* is not), and a two-clip fixture cannot tell
 * those apart.
 */
internal fun sampleDocument(): EditDocument = document(
    clips = listOf(
        clip("c1", "s1", 0L, 2 * SEC),
        clip("c2", "s1", 2 * SEC, 4 * SEC),
        clip("c3", "s1", 4 * SEC, 7 * SEC),
    ),
)

/** One clip, one second: the smallest document that still renders something. */
internal fun singleClipDocument(): EditDocument = document(
    clips = listOf(clip("c1", "s1", 0L, 1 * SEC)),
)

/** The compiled layer for [clipId]. Fails the test with a clear message if absent. */
internal fun videoLayer(graph: RenderGraph, clipId: String): RenderLayer.Video =
    graph.videoLayers.firstOrNull { it.clipId == clipId }
        ?: throw AssertionError(
            "no video layer for clip $clipId; layers are " +
                graph.videoLayers.map { it.clipId },
        )

/**
 * The invariants §4.3 and §7.2 require of a compiled graph, asserted after every
 * compile in the property suite.
 *
 * These are the failures that would otherwise surface far from their cause: a gap in
 * the layer list is a frozen frame in a preview, and an unresolved source is a
 * decoder error during an export. [RenderGraph] enforces the structural half in its
 * constructor; this re-states it at the call site so a regression points at the test
 * that produced the document rather than at the type.
 */
internal fun assertGraphInvariants(graph: RenderGraph, context: String = "") {
    val where = if (context.isEmpty()) "" else " [$context]"

    var cursor = 0L
    graph.videoLayers.forEach { layer ->
        assertEquals(
            cursor,
            layer.timeRange.startUs,
            "video layers must be contiguous from 0$where",
        )
        assertTrue(
            layer.timeRange.durationUs >= RenderGraph.MIN_LAYER_US,
            "layer ${layer.clipId} is below the ${RenderGraph.MIN_LAYER_US}us floor$where",
        )
        cursor = layer.timeRange.endUs
    }
    assertEquals(
        cursor,
        graph.durationUs,
        "graph duration must be the end of the last video layer$where",
    )

    // Every layer must be decodable: its source is carried on the layer, and the
    // source range must be a real slice of it.
    graph.videoLayers.forEach { layer ->
        assertTrue(
            layer.source.durationUs >= layer.sourceRange.endUs,
            "layer ${layer.clipId} reads past the end of its source$where",
        )
    }

    graph.transitions.forEach { transition ->
        val incoming = graph.videoLayers[transition.toIndex]
        assertEquals(
            incoming.timeRange.startUs,
            transition.startUs,
            "a transition must start exactly at the seam$where",
        )
    }
}

/** Every layer of [graph], in render order: video layers first, then overlays. */
internal fun RenderGraph.layersInRenderOrder(): List<RenderLayer> = layers
