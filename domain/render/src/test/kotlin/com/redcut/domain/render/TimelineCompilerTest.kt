package com.redcut.domain.render

import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.ColorAdjustSpec
import com.redcut.domain.document.EffectScope
import com.redcut.domain.document.KeyframableProperty
import com.redcut.domain.document.Keyframe
import com.redcut.domain.document.LutRef
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The compilation rules of spec §4.3 / §8.1, one test each.
 *
 * This is the highest-value suite in the repo (§12.1): the compiler is the only
 * place framing, timing and ordering rules exist, and both the preview and the
 * export path consume its output — so a rule that is wrong here is wrong in two
 * places at once, and a rule that is untested here is untested everywhere.
 *
 * Tests read as "given this document, the graph looks like this", never as a
 * restatement of the implementation. Where a rule has a *reason*, the test names the
 * reason (a disabled clip ripples rather than leaving a hole; a no-op effect must not
 * cost a shader pass) so a future change that breaks the intent fails a test whose
 * name explains why it existed.
 */
class TimelineCompilerTest {

    // --- Layers (rule 1, rule 2) ------------------------------------------

    @Test
    fun `an empty document compiles to an empty graph`() {
        val graph = TimelineCompiler.compile(document(clips = emptyList()))

        assertTrue(graph.videoLayers.isEmpty())
        assertEquals(0L, graph.durationUs)
        assertTrue(graph.transitions.isEmpty())
        assertTrue(graph.overlayLayers.isEmpty())
    }

    @Test
    fun `a single clip becomes one layer covering the whole timeline`() {
        val doc = singleClipDocument()
        val graph = TimelineCompiler.compile(doc)
        val layer = videoLayer(graph, "c1")

        assertEquals(TimeRange(0L, SEC), layer.timeRange)
        assertEquals(TimeRange(0L, SEC), layer.sourceRange)
        assertEquals(doc.sources.single(), layer.source)
        assertEquals(1000 * MS, graph.durationUs)
    }

    @Test
    fun `a keyed clip carries its keys onto its layer and an unkeyed one carries none`() {
        // WS G1: the export path reads the transform PER FRAME, and the keys are what makes that
        // possible — a layer is a value with no clock of its own, so a compiler that dropped this map
        // would leave the graph nothing to evaluate and export every keyed clip as its static crop,
        // while the preview animated. Carried whole and unmoved: nothing here touches these times.
        val keys = listOf(Keyframe(0L, 0f), Keyframe(SEC, 0.5f))
        val graph = TimelineCompiler.compile(
            document(
                clips = listOf(
                    clip("c1", "s1", 0L, 2 * SEC).copy(
                        keyframes = mapOf(KeyframableProperty.CROP_LEFT to keys),
                    ),
                    clip("c2", "s1", 2 * SEC, 4 * SEC),
                ),
            ),
        )

        assertEquals(keys, videoLayer(graph, "c1").keyframes[KeyframableProperty.CROP_LEFT])

        // The un-keyed clip is the fast path: no keys at all, and reading its transform at any time is
        // its own static spec — not an equal copy, the same value the graph has always carried.
        val plain = videoLayer(graph, "c2")
        assertTrue(plain.keyframes.isEmpty())
        assertEquals(plain.transform, plain.transformAt(SEC))
    }

    @Test
    fun `layers are contiguous and in document order`() {
        val graph = TimelineCompiler.compile(sampleDocument())

        assertEquals(listOf("c1", "c2", "c3"), graph.videoLayers.map { it.clipId })
        assertEquals(TimeRange(0L, 2 * SEC), videoLayer(graph, "c1").timeRange)
        assertEquals(TimeRange(2 * SEC, 4 * SEC), videoLayer(graph, "c2").timeRange)
        assertEquals(TimeRange(4 * SEC, 7 * SEC), videoLayer(graph, "c3").timeRange)
        assertEquals(7 * SEC, graph.durationUs)
        assertGraphInvariants(graph)
    }

    @Test
    fun `speed shortens the layer but not the source range`() {
        val doc = document(clips = listOf(clip("c1", "s1", 0L, 2 * SEC, speed = 2f)))
        val layer = videoLayer(TimelineCompiler.compile(doc), "c1")

        assertEquals(TimeRange(0L, 2 * SEC), layer.sourceRange, "the trim is unchanged")
        assertEquals(TimeRange(0L, SEC), layer.timeRange, "the timeline is half as long")
        assertEquals(2f, layer.speed)
    }

    @Test
    fun `a very short fast clip is floored at the minimum layer length`() {
        // 150 ms of source at 4x is 37.5 ms of timeline; Clip floors the layer at
        // 100 ms, and RenderGraph refuses anything shorter — so the floor has to hold
        // in both places or the graph cannot be constructed at all.
        val doc = document(clips = listOf(clip("c1", "s1", 0L, 150 * MS, speed = 4f)))
        val layer = videoLayer(TimelineCompiler.compile(doc), "c1")

        assertEquals(RenderGraph.MIN_LAYER_US, layer.timeRange.durationUs)
    }

    @Test
    fun `a disabled clip is dropped and the timeline ripples closed`() {
        val doc = document(
            clips = listOf(
                clip("c1", "s1", 0L, 2 * SEC),
                clip("c2", "s1", 2 * SEC, 4 * SEC, enabled = false),
                clip("c3", "s1", 4 * SEC, 7 * SEC),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(listOf("c1", "c3"), graph.videoLayers.map { it.clipId })
        assertEquals(
            TimeRange(2 * SEC, 5 * SEC),
            videoLayer(graph, "c3").timeRange,
            "the gap closes rather than being held open",
        )
        assertGraphInvariants(graph)
    }

    @Test
    fun `a clip whose source is missing is dropped`() {
        val doc = document(
            clips = listOf(
                clip("c1", "s1", 0L, 2 * SEC),
                clip("orphan", "ghost", 0L, 2 * SEC),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(listOf("c1"), graph.videoLayers.map { it.clipId })
    }

    // --- Document identity (rules carried verbatim) -----------------------

    @Test
    fun `the graph carries the document revision and canvas verbatim`() {
        val doc = document(clips = listOf(clip("c1", "s1", 0L, SEC)), revision = 7L)
            .copy(canvas = CanvasSpec.LANDSCAPE_720)
        val graph = TimelineCompiler.compile(doc)

        assertEquals(7L, graph.revision, "a cached graph is identified by this value")
        assertEquals(CanvasSpec.LANDSCAPE_720, graph.canvas)
    }

    @Test
    fun `preview output defaults to the document canvas and an export output is kept`() {
        val doc = document(clips = listOf(clip("c1", "s1", 0L, SEC)))
        val preview = TimelineCompiler.compile(doc)
        assertEquals(OutputSpec.preview(CanvasSpec.PORTRAIT_1080), preview.output)

        val export =
            OutputSpec(1920, 1080, fps = 30, videoBitrate = 8_000_000, audioBitrate = 192_000)
        assertEquals(export, TimelineCompiler.compile(doc, export).output)
    }

    // --- Effect chains (rules 3, 4) ---------------------------------------

    @Test
    fun `a clip-scoped adjust lands only in its own layer, in clip-local time`() {
        val adjust = colorAdjust()
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust("e1", EffectScope.Clip("c2"), TimeRange(0L, SEC), adjust),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(
            listOf(RenderEffect.Adjust(adjust, TimeRange(0L, SEC))),
            videoLayer(graph, "c2").effects,
        )
        assertTrue(videoLayer(graph, "c1").effects.isEmpty())
        assertTrue(videoLayer(graph, "c3").effects.isEmpty())
    }

    @Test
    fun `a document-scoped adjust is rebased into every layer it overlaps`() {
        val adjust = colorAdjust()
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust("e1", EffectScope.Document, TimeRange(SEC, 3 * SEC), adjust),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(
            listOf(RenderEffect.Adjust(adjust, TimeRange(SEC, 2 * SEC))),
            videoLayer(graph, "c1").effects,
            "an absolute range becomes layer-local",
        )
        assertEquals(
            listOf(RenderEffect.Adjust(adjust, TimeRange(0L, SEC))),
            videoLayer(graph, "c2").effects,
        )
        assertTrue(videoLayer(graph, "c3").effects.isEmpty())
    }

    @Test
    fun `a document-scoped effect that misses a layer is dropped from it`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust(
                    "e1",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    colorAdjust(),
                ),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertTrue(
            videoLayer(graph, "c1").effects.isEmpty(),
            "an effect that does not touch c1 must not become a per-frame no-op on it",
        )
        assertEquals(1, videoLayer(graph, "c2").effects.size)
    }

    @Test
    fun `an identity adjust never enters a chain`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust(
                    "e1",
                    EffectScope.Document,
                    TimeRange(0L, 5 * SEC),
                    ColorAdjustSpec(),
                ),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertTrue(graph.videoLayers.all { it.effects.isEmpty() })
    }

    @Test
    fun `a zero-strength LUT never enters a chain`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Lut(
                    "e1",
                    EffectScope.Document,
                    TimeRange(0L, 5 * SEC),
                    LutRef("vivid"),
                    strength = 0f,
                ),
            ),
        )

        assertTrue(TimelineCompiler.compile(doc).videoLayers.all { it.effects.isEmpty() })
    }

    @Test
    fun `a disabled effect never enters a chain`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust(
                    "e1",
                    EffectScope.Document,
                    TimeRange(0L, 5 * SEC),
                    colorAdjust(),
                    enabled = false,
                ),
            ),
        )

        assertTrue(TimelineCompiler.compile(doc).videoLayers.all { it.effects.isEmpty() })
    }

    @Test
    fun `chain entries keep document stack order`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Lut("e1", EffectScope.Document, TimeRange(0L, SEC), LutRef("vivid")),
                AppliedEffect.Adjust("e2", EffectScope.Document, TimeRange(0L, SEC), colorAdjust()),
                AppliedEffect.Lut("e3", EffectScope.Document, TimeRange(0L, SEC), LutRef("noir")),
            ),
        )
        val chain = videoLayer(TimelineCompiler.compile(doc), "c1").effects

        assertEquals(
            listOf("vivid", "adjust", "noir"),
            chain.map {
                when (it) {
                    is RenderEffect.Lut -> it.ref.id
                    is RenderEffect.Adjust -> "adjust"
                }
            },
            "render order is list order (FR-4.7): no sort step may come between them",
        )
    }

    @Test
    fun `an effect scoped to a clip that no longer exists is dropped`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust(
                    "e1",
                    EffectScope.Clip("deleted"),
                    TimeRange(0L, SEC),
                    colorAdjust(),
                ),
            ),
        )

        assertTrue(TimelineCompiler.compile(doc).videoLayers.all { it.effects.isEmpty() })
    }

    @Test
    fun `a clip-scoped effect longer than its layer is clamped to the layer`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Adjust(
                    "e1",
                    EffectScope.Clip("c1"),
                    TimeRange(0L, 5 * SEC),
                    colorAdjust(),
                ),
            ),
        )
        val chain = videoLayer(TimelineCompiler.compile(doc), "c1").effects

        assertEquals(
            listOf(RenderEffect.Adjust(colorAdjust(), TimeRange(0L, 2 * SEC))),
            chain,
            "an effect cannot name time the layer does not have",
        )
    }

    // --- Overlays (rule 5) ------------------------------------------------

    @Test
    fun `overlays are emitted after the video layers, in stack order`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                textEffect("e1", EffectScope.Document, TimeRange(0L, SEC), "first"),
                imageEffect("e2", EffectScope.Document, TimeRange(0L, SEC), "s1"),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(
            listOf("Video", "Video", "Video", "Text", "Image"),
            graph.layersInRenderOrder().map { kind(it) },
            "overlays are drawn over the composited clip, so they come last",
        )
        assertEquals(
            listOf("first"),
            graph.overlayLayers.filterIsInstance<RenderLayer.Text>().map { it.content.content },
        )
    }

    @Test
    fun `a document-scoped overlay is clamped to the video`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                textEffect("e1", EffectScope.Document, TimeRange(4 * SEC, 9 * SEC), "late"),
            ),
        )
        val layer = TimelineCompiler.compile(doc).overlayLayers.single()

        assertEquals(
            TimeRange(4 * SEC, 7 * SEC),
            layer.timeRange,
            "an overlay must not claim to exist after the video ended",
        )
    }

    @Test
    fun `a clip-scoped overlay is rebased onto the compiled clip start`() {
        val doc = document(
            clips = listOf(
                clip("c1", "s1", 0L, 2 * SEC, enabled = false),
                clip("c2", "s1", 2 * SEC, 4 * SEC),
            ),
            effects = listOf(
                textEffect("e1", EffectScope.Clip("c2"), TimeRange(0L, SEC), "caption"),
            ),
        )
        val layer = TimelineCompiler.compile(doc).overlayLayers.single()

        assertEquals(
            TimeRange(0L, SEC),
            layer.timeRange,
            "c2 now starts at 0 because c1 is gone: the overlay must follow the " +
                "rippled timeline, not the document's stale position",
        )
    }

    @Test
    fun `an image overlay whose source is gone is dropped`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                imageEffect("e1", EffectScope.Document, TimeRange(0L, SEC), "ghost"),
            ),
        )

        assertTrue(TimelineCompiler.compile(doc).overlayLayers.isEmpty())
    }

    @Test
    fun `an overlay on an empty document is dropped`() {
        val doc = document(
            clips = emptyList(),
            effects = listOf(textEffect("e1", EffectScope.Document, TimeRange(0L, SEC), "t")),
        )

        assertTrue(TimelineCompiler.compile(doc).overlayLayers.isEmpty())
    }

    // --- Fades and audio (rule 6, rule 8) ---------------------------------

    @Test
    fun `a fade pair longer than its clip is scaled to fill it`() {
        val doc = document(
            clips = listOf(clip("c1", "s1", 0L, 4 * SEC, fadeInMs = 3000L, fadeOutMs = 3000L)),
        )
        val layer = videoLayer(TimelineCompiler.compile(doc), "c1")

        assertEquals(FadeSpec(2000L, 2000L), layer.fades, "both edges scale, neither vanishes")
        assertEquals(4000L, layer.fades.totalMs, "the pair exactly fills the clip")
    }

    @Test
    fun `a fade pair that fits is passed through untouched`() {
        val doc = document(
            clips = listOf(clip("c1", "s1", 0L, 4 * SEC, fadeInMs = 500L, fadeOutMs = 750L)),
        )
        val layer = videoLayer(TimelineCompiler.compile(doc), "c1")

        assertEquals(FadeSpec(500L, 750L), layer.fades)
    }

    @Test
    fun `audio carries gain, mute and the same fade pair as the video edge`() {
        val doc = document(
            clips = listOf(
                clip(
                    "c1",
                    "s1",
                    0L,
                    2 * SEC,
                    volume = 0.5f,
                    muted = true,
                    fadeInMs = 200L,
                ),
            ),
        )
        val layer = videoLayer(TimelineCompiler.compile(doc), "c1")

        assertEquals(AudioSpec(gain = 0.5f, muted = true, fades = FadeSpec(200L, 0L)), layer.audio)
        assertEquals(FadeSpec(200L, 0L), layer.fades)
    }

    @Test
    fun `volume outside 0 to 2 is coerced and a NaN volume falls back to unity`() {
        val loud = document(clips = listOf(clip("c1", "s1", 0L, SEC, volume = 3f)))
        assertEquals(2f, videoLayer(TimelineCompiler.compile(loud), "c1").audio.gain)

        // Clip.volume has no require(), so NaN is constructible — and AudioSpec's own
        // require() would turn it into a crash rather than a compilation.
        val nan = document(clips = listOf(clip("c1", "s1", 0L, SEC, volume = Float.NaN)))
        assertEquals(1f, videoLayer(TimelineCompiler.compile(nan), "c1").audio.gain)
    }

    @Test
    fun `a document with no audio lane reports no music bed`() {
        val graph = TimelineCompiler.compile(sampleDocument())

        assertEquals(1f, graph.audio.masterGain)
        // The bed is the document's AUDIO lane and nothing else, so a project that has never imported a
        // song has no music at all. An invented one would be a music track the user never added.
        assertNull(graph.audio.music, "only an AUDIO lane is a music bed (FR-1.6)")
    }

    // --- A document with no picture is legal (WS D, D4) -------------------

    @Test
    fun `an audio-only document compiles to no video layers and keeps its music bed`() {
        val graph = TimelineCompiler.compile(audioOnlyDocument())

        // Nothing may require a video track to exist. An AUDIO lane's clip is SOUND, and emitting it as
        // a video layer would ask the mapper to decode a song as footage — so the render is empty video.
        assertTrue(graph.videoLayers.isEmpty(), "an audio lane contributes no picture")
        assertTrue(graph.overlayLayers.isEmpty())
        assertTrue(graph.transitions.isEmpty())
        assertEquals(0L, graph.durationUs)
        // ...and the sound is not thrown away for it. This is the half that makes the empty video a
        // music bed rather than a silent one: an empty render with the audio intact.
        val bed = requireNotNull(graph.audio.music) { "the audio lane is the music bed (FR-1.6)" }
        assertEquals("a1", bed.source.id)
        assertEquals(TimeRange(0L, 3 * SEC), bed.sourceRange)
        assertEquals(1f, graph.audio.masterGain)
    }

    @Test
    fun `a music bed is carried with the clip's gain, mute and fades`() {
        val doc = audioOnlyDocument(
            clips = listOf(
                clip("a1", "a1", 0L, 2 * SEC, volume = 0.5f, muted = true, fadeInMs = 200L),
            ),
        )

        val bed = requireNotNull(TimelineCompiler.compile(doc).audio.music)

        // The bed is a clip like any other where the mix is concerned, read by the same helpers the
        // video layer's AudioSpec is — one rule for both rather than a second one for the music path.
        assertEquals(0.5f, bed.gain)
        assertEquals(true, bed.muted)
        assertEquals(FadeSpec(200L, 0L), bed.fades)
    }

    @Test
    fun `an audio lane beside a video lane adds no layer and does not move the video`() {
        val doc = twoLaneDocument(
            clips = listOf(clip("c1", "s1", 0L, 2 * SEC), clip("c2", "s1", 2 * SEC, 4 * SEC)),
            audioClips = listOf(clip("a1", "a1", 0L, 3 * SEC)),
        )

        val graph = TimelineCompiler.compile(doc)

        // The video render is EXACTLY what the video lane alone produced: a bed plays BESIDE the picture,
        // not in it, and a layer list that grew by the bed's clip would stretch the export by its length.
        assertEquals(listOf("c1", "c2"), graph.videoLayers.map { it.clipId })
        assertEquals(4 * SEC, graph.durationUs)
        assertEquals("a1", graph.audio.music?.source?.id)
    }

    @Test
    fun `the first audio lane is the bed and a second one does not become a second one`() {
        val second = audioTrack(clips = listOf(clip("a2", "a1", 0L, 2 * SEC)), id = "track-audio-2")
        val doc = audioOnlyDocument().let { base -> base.copy(tracks = base.tracks + second) }

        val bed = requireNotNull(TimelineCompiler.compile(doc).audio.music)

        // The MVP is ONE bed, so `AudioGraph.music` is one value and the named cost is that a second lane
        // compiles to nothing: filling it with whichever clip happened to be second would be a music
        // track the user never added. The bed is the FIRST lane's clip, which runs the longer of the two.
        assertEquals(TimeRange(0L, 3 * SEC), bed.sourceRange)
    }

    @Test
    fun `a bed whose source is gone leaves no music rather than failing the compile`() {
        val doc = audioOnlyDocument().let { it.copy(sources = emptyList()) }

        val graph = TimelineCompiler.compile(doc)

        // Totality: a dangling source is a MISSING bed, not a crash. The clip is still in the document,
        // so undoing whatever removed the source brings the music back with it.
        assertNull(graph.audio.music)
        assertTrue(graph.videoLayers.isEmpty())
    }

    // --- Dissolves (rule 7) -----------------------------------------------

    @Test
    fun `a document-scoped dissolve on an interior seam becomes a transition`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    durationMs = 500L,
                ),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(listOf(Transition.Dissolve(0, 1, 2 * SEC, 500 * MS)), graph.transitions)
        assertGraphInvariants(graph)
    }

    @Test
    fun `a clip-scoped dissolve resolves to that clip's end`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Clip("c1"),
                    TimeRange(0L, SEC),
                    durationMs = 400L,
                ),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(listOf(Transition.Dissolve(0, 1, 2 * SEC, 400 * MS)), graph.transitions)
    }

    @Test
    fun `a dissolve is capped by the shorter neighbouring layer`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    durationMs = 9000L,
                ),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        assertEquals(
            2 * SEC,
            graph.transitions.single().durationUs,
            "a 9 s dissolve over two 2 s clips can only be 2 s long",
        )
    }

    @Test
    fun `a dissolve below the floor is dropped rather than shortened`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    durationMs = 50L,
                ),
            ),
        )

        assertTrue(TimelineCompiler.compile(doc).transitions.isEmpty())
    }

    @Test
    fun `a dissolve that is not on a seam is dropped`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(SEC, 2 * SEC),
                    durationMs = 500L,
                ),
            ),
        )

        assertTrue(TimelineCompiler.compile(doc).transitions.isEmpty())
    }

    @Test
    fun `a document-scoped dissolve resolves to the seam at that time, not one later`() {
        // 4 s is the start of the LAST layer, i.e. the seam between c2 and c3 — a real
        // seam, so a dissolve there belongs to layers 1 -> 2. Resolving the seam's own
        // index (2) rather than the outgoing layer's (1) would emit a transition
        // pointing past the end of the video.
        val atLastSeam = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(4 * SEC, 5 * SEC),
                    durationMs = 500L,
                ),
            ),
        )

        assertEquals(
            listOf(Transition.Dissolve(1, 2, 4 * SEC, 500 * MS)),
            TimelineCompiler.compile(atLastSeam).transitions,
        )
    }

    @Test
    fun `a dissolve at the head of the video is dropped`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(0L, SEC),
                    durationMs = 500L,
                ),
            ),
        )

        assertTrue(
            TimelineCompiler.compile(doc).transitions.isEmpty(),
            "layer 0's start is the head of the video: there is nothing to dissolve from",
        )
    }

    @Test
    fun `a dissolve addressed to the last clip is dropped`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Clip("c3"),
                    TimeRange(0L, SEC),
                    durationMs = 500L,
                ),
            ),
        )

        assertTrue(
            TimelineCompiler.compile(doc).transitions.isEmpty(),
            "c3 has nothing after it, and folding the dissolve backwards would move it",
        )
    }

    @Test
    fun `only the first dissolve on a seam is emitted`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Dissolve(
                    "e1",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    durationMs = 500L,
                ),
                AppliedEffect.Dissolve(
                    "e2",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    durationMs = 300L,
                ),
            ),
        )

        assertEquals(1, TimelineCompiler.compile(doc).transitions.size)
        assertEquals(500 * MS, TimelineCompiler.compile(doc).transitions.single().durationUs)
    }

    // --- Properties (rule 9) ----------------------------------------------

    /**
     * Random documents, compiled twice, checked against the graph invariants.
     *
     * Composing and reordering are pure list operations, so most of the ways a
     * document can be strange are cheap to generate: a dangling `sourceId`, a clip
     * disabled in the middle, a fade pair longer than its clip, an effect scoped to a
     * clip that was deleted, a dissolve on the last seam. The compiler's contract is
     * that none of them can throw, and that the graph it produces is one the renderer
     * can consume.
     */
    @Test
    fun `random documents compile reproducibly and keep the graph invariants`() {
        val rng = Random(SEED)
        var nonEmptyGraphs = 0

        repeat(ROUNDS) { round ->
            val doc = randomDocument(rng, round)

            val first = TimelineCompiler.compile(doc)
            val second = TimelineCompiler.compile(doc)
            assertEquals(first, second, "round $round: compilation must be deterministic")

            assertGraphInvariants(first, context = "round=$round")
            assertEquals(
                doc.clips.filter { it.enabled && doc.sourceById(it.sourceId) != null }
                    .sumOf { it.timelineDurationUs },
                first.durationUs,
                "round $round: the graph is as long as the live clips",
            )
            if (first.videoLayers.isNotEmpty()) nonEmptyGraphs++
        }

        assertTrue(nonEmptyGraphs > ROUNDS / 2, "the generator should mostly render something")
    }

    // --- Helpers ----------------------------------------------------------

    private fun colorAdjust(): ColorAdjustSpec = ColorAdjustSpec(brightness = 0.4f)

    private fun textEffect(
        id: String,
        scope: EffectScope,
        range: TimeRange,
        content: String,
    ): AppliedEffect = AppliedEffect.Text(id, scope, range, TextSpec(content))

    private fun imageEffect(
        id: String,
        scope: EffectScope,
        range: TimeRange,
        sourceId: String,
    ): AppliedEffect = AppliedEffect.Image(id, scope, range, sourceId)

    private fun kind(layer: RenderLayer): String = when (layer) {
        is RenderLayer.Video -> "Video"
        is RenderLayer.Text -> "Text"
        is RenderLayer.Image -> "Image"
    }

    private fun randomDocument(rng: Random, round: Int): com.redcut.domain.document.EditDocument {
        val sourceCount = rng.nextInt(1, 3)
        val sources = (0 until sourceCount).map { source("s$it") }

        val clips = (0 until rng.nextInt(0, 6)).map { i ->
            val inUs = rng.nextInt(0, 5) * SEC
            val outUs = inUs + rng.nextInt(1, 40) * 100_000L
            clip(
                id = "c$i",
                sourceId = if (rng.nextInt(0, 8) == 0) {
                    "ghost"
                } else {
                    "s${rng.nextInt(
                        0,
                        sourceCount,
                    )}"
                },
                inUs = inUs,
                outUs = outUs,
                speed = SPEEDS[rng.nextInt(0, SPEEDS.size)],
                enabled = rng.nextInt(0, 6) != 0,
                volume = VOLUMES[rng.nextInt(0, VOLUMES.size)],
                muted = rng.nextBoolean(),
                fadeInMs = FADES[rng.nextInt(0, FADES.size)],
                fadeOutMs = FADES[rng.nextInt(0, FADES.size)],
            )
        }

        val effects = (0 until rng.nextInt(0, 5)).map { i -> randomEffect(rng, clips, i) }

        return document(
            clips = clips,
            sources = sources,
            effects = effects,
            revision = round.toLong(),
        )
    }

    private fun randomEffect(
        rng: Random,
        clips: List<com.redcut.domain.document.Clip>,
        index: Int,
    ): AppliedEffect {
        val scope = if (clips.isNotEmpty() && rng.nextBoolean()) {
            // ~1 in 4 lands on an id that is not in the document any more, which is
            // the dangling-reference case rule 9 is about.
            val clipId = if (rng.nextInt(0, 4) == 0) {
                "ghost"
            } else {
                clips[
                    rng.nextInt(
                        0,
                        clips.size,
                    ),
                ].id
            }
            EffectScope.Clip(clipId)
        } else {
            EffectScope.Document
        }
        val startUs = rng.nextInt(0, 8) * 500_000L
        val range = TimeRange(startUs, startUs + rng.nextInt(1, 6) * 500_000L)
        val enabled = rng.nextInt(0, 4) != 0
        val id = "e$index"

        return when (rng.nextInt(0, 5)) {
            0 -> AppliedEffect.Lut(
                id,
                scope,
                range,
                LutRef("vivid"),
                enabled,
                strength = STRENGTHS[rng.nextInt(0, STRENGTHS.size)],
            )

            1 -> AppliedEffect.Adjust(
                id,
                scope,
                range,
                ColorAdjustSpec(brightness = BRIGHTNESSES[rng.nextInt(0, BRIGHTNESSES.size)]),
                enabled,
            )

            2 -> AppliedEffect.Text(id, scope, range, TextSpec("caption"), enabled)

            3 -> AppliedEffect.Image(
                id,
                scope,
                range,
                if (rng.nextInt(0, 3) == 0) "ghost" else "s0",
                enabled,
            )

            else -> AppliedEffect.Dissolve(
                id,
                scope,
                range,
                durationMs = DISSOLVES[rng.nextInt(0, DISSOLVES.size)],
                enabled = enabled,
            )
        }
    }

    private companion object {
        const val SEED = 20260912L
        const val ROUNDS = 60

        val SPEEDS = listOf(0.5f, 1f, 2f, 4f)
        val VOLUMES = listOf(0f, 0.5f, 1f, 2f)
        val FADES = listOf(0L, 200L, 3000L)
        val STRENGTHS = listOf(0f, 0.5f, 1f)
        val BRIGHTNESSES = listOf(0f, 0.25f, -0.5f)

        /** Below the floor, ordinary, and longer than any clip the generator makes. */
        val DISSOLVES = listOf(50L, 400L, 5000L)
    }
}
