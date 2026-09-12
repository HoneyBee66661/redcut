package com.redcut.domain.render

import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.EffectScope
import com.redcut.domain.document.LutRef
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import com.redcut.domain.document.TransformSpec
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The invariants [RenderGraph] enforces on itself, the small value types around it,
 * and the serialized form of the whole hierarchy.
 *
 * The constructor checks matter more than they look: [RenderGraph] is the only value
 * that crosses from the pure domain into the render engines, and both engines assume
 * the layer list is a gapless sequence they can hand to Media3 as one composition. A
 * graph that broke that assumption would not fail here — it would fail as a frozen
 * frame in a preview and a truncated export, in code that is nowhere near the bug.
 *
 * The round-trip test is the other half: §10 persists projects as JSON and §12.1 asks
 * for generated documents to survive it, so a `@Serializable` hierarchy that silently
 * loses a nullable field is a data-loss bug that only shows up on a user's second
 * launch.
 */
class RenderGraphTest {

    // --- Layer list invariants --------------------------------------------

    @Test
    fun `a contiguous graph is accepted and reports its own shape`() {
        val graph = graph(
            videoLayer("c1", 0L, SEC),
            videoLayer("c2", SEC, 2 * SEC),
            textLayer(TimeRange(0L, 2 * SEC)),
            transition = Transition.Dissolve(0, 1, SEC, 200 * MS),
        )

        assertEquals(listOf("c1", "c2"), graph.videoLayers.map { it.clipId })
        assertEquals(1, graph.overlayLayers.size)
        assertEquals(2 * SEC, graph.durationUs)
    }

    @Test
    fun `a gap between video layers is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            graph(videoLayer("c1", 0L, SEC), videoLayer("c2", 2 * SEC, 3 * SEC))
        }

        assertTrue(
            error.message.orEmpty().contains("contiguous"),
            "the message must name the rule, was: ${error.message}",
        )
    }

    @Test
    fun `a video layer shorter than the floor is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            graph(videoLayer("c1", 0L, 50 * MS))
        }
    }

    @Test
    fun `a transition that does not start at the seam is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            graph(
                videoLayer("c1", 0L, SEC),
                videoLayer("c2", SEC, 2 * SEC),
                transition = Transition.Dissolve(0, 1, 500 * MS, 100 * MS),
            )
        }
    }

    @Test
    fun `a transition between non-adjacent layers is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            graph(
                videoLayer("c1", 0L, SEC),
                videoLayer("c2", SEC, 2 * SEC),
                videoLayer("c3", 2 * SEC, 3 * SEC),
                transition = Transition.Dissolve(0, 2, 2 * SEC, 100 * MS),
            )
        }
    }

    @Test
    fun `a transition onto a missing layer is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            graph(
                videoLayer("c1", 0L, SEC),
                transition = Transition.Dissolve(0, 1, SEC, 100 * MS),
            )
        }
    }

    @Test
    fun `two transitions on one seam are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            graph(
                videoLayer("c1", 0L, SEC),
                videoLayer("c2", SEC, 2 * SEC),
                transitions = listOf(
                    Transition.Dissolve(0, 1, SEC, 100 * MS),
                    Transition.Dissolve(0, 1, SEC, 200 * MS),
                ),
            )
        }
    }

    // --- Value types ------------------------------------------------------

    @Test
    fun `FadeSpec knows when it would do nothing and rejects negative edges`() {
        assertTrue(FadeSpec().isIdentity)
        assertFalse(FadeSpec(fadeInMs = 1L).isIdentity)
        assertEquals(500L, FadeSpec(200L, 300L).totalMs)
        assertThrows(IllegalArgumentException::class.java) { FadeSpec(fadeInMs = -1L) }
        assertThrows(IllegalArgumentException::class.java) { FadeSpec(fadeOutMs = -1L) }
    }

    @Test
    fun `AudioSpec holds 0 to 200 percent and knows when it is silent`() {
        assertFalse(AudioSpec().isSilent)
        assertTrue(AudioSpec(gain = 0f).isSilent)
        assertTrue(AudioSpec(muted = true).isSilent)
        assertEquals(2f, AudioSpec.MAX_GAIN)
        assertThrows(IllegalArgumentException::class.java) { AudioSpec(gain = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { AudioSpec(gain = 2.1f) }
        assertThrows(IllegalArgumentException::class.java) { AudioGraph(masterGain = 3f) }
    }

    @Test
    fun `OutputSpec derives geometry and refuses nonsense`() {
        val preview = OutputSpec.preview(CanvasSpec.PORTRAIT_1080)

        assertEquals(1080, preview.width)
        assertEquals(1920, preview.height)
        assertEquals(30, preview.fps)
        assertTrue(preview.isPortrait)
        assertNull(preview.videoBitrate, "preview does not encode, so it has no bitrate")
        assertNull(preview.audioBitrate)

        assertThrows(IllegalArgumentException::class.java) { OutputSpec(0, 1080) }
        assertThrows(IllegalArgumentException::class.java) { OutputSpec(1080, 1920, fps = 0) }
        assertThrows(IllegalArgumentException::class.java) { OutputSpec(1080, 1920, videoBitrate = 0) }
        assertThrows(IllegalArgumentException::class.java) { OutputSpec(1080, 1920, audioBitrate = -1) }
    }

    @Test
    fun `AudioBed validates its own parameters`() {
        val bed = AudioBed(source = source("music"), timelineStartUs = SEC)

        assertEquals(SEC, bed.timelineStartUs)
        assertNull(bed.sourceRange, "no range means the whole source plays")
        assertThrows(IllegalArgumentException::class.java) {
            AudioBed(source = source("music"), timelineStartUs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBed(source = source("music"), gain = 2.5f)
        }
    }

    // --- Serialization (§10, §12.1) ---------------------------------------

    @Test
    fun `a fully populated graph survives a JSON round trip`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Lut("e1", EffectScope.Document, TimeRange(0L, 5 * SEC), LutRef("vivid")),
                AppliedEffect.Adjust(
                    "e2",
                    EffectScope.Clip("c2"),
                    TimeRange(0L, SEC),
                    com.redcut.domain.document.ColorAdjustSpec(brightness = 0.3f),
                ),
                AppliedEffect.Text(
                    "e3",
                    EffectScope.Document,
                    TimeRange(0L, 2 * SEC),
                    TextSpec("caption"),
                ),
                AppliedEffect.Image(
                    "e4",
                    EffectScope.Document,
                    TimeRange(0L, SEC),
                    "s1",
                    opacity = 0.8f,
                ),
                AppliedEffect.Dissolve(
                    "e5",
                    EffectScope.Document,
                    TimeRange(2 * SEC, 3 * SEC),
                    durationMs = 500L,
                ),
            ),
        )
        val graph = TimelineCompiler.compile(doc, OutputSpec(1920, 1080, 30, 8_000_000, 192_000))
        assertTrue(graph.transitions.isNotEmpty(), "the fixture must exercise transitions")
        assertTrue(graph.overlayLayers.isNotEmpty(), "the fixture must exercise overlays")

        val encoded = Json.encodeToString(RenderGraph.serializer(), graph)
        val decoded = Json.decodeFromString(RenderGraph.serializer(), encoded)

        assertEquals(graph, decoded, "a round trip must not change the graph")
        assertTrue(encoded.contains("dissolve") || encoded.contains("Dissolve"))
    }

    @Test
    fun `an empty graph survives a JSON round trip with its nulls intact`() {
        val graph = TimelineCompiler.compile(document(clips = emptyList()))
        val decoded = Json.decodeFromString(
            RenderGraph.serializer(),
            Json.encodeToString(RenderGraph.serializer(), graph),
        )

        assertEquals(graph, decoded)
        assertTrue(decoded.videoLayers.isEmpty())
        assertNull(decoded.audio.music, "an absent music bed must not come back as a default one")
    }

    // --- Helpers ----------------------------------------------------------

    private fun graph(
        vararg layers: RenderLayer,
        transitions: List<Transition> = emptyList(),
        transition: Transition? = null,
    ): RenderGraph = RenderGraph(
        revision = 0L,
        canvas = CanvasSpec.PORTRAIT_1080,
        layers = layers.toList(),
        output = OutputSpec.preview(CanvasSpec.PORTRAIT_1080),
        transitions = transition?.let { listOf(it) } ?: transitions,
    )

    private fun videoLayer(clipId: String, startUs: Long, endUs: Long): RenderLayer.Video =
        RenderLayer.Video(
            clipId = clipId,
            source = source("s1"),
            sourceRange = TimeRange(startUs, endUs),
            timeRange = TimeRange(startUs, endUs),
        )

    private fun textLayer(range: TimeRange): RenderLayer.Text =
        RenderLayer.Text(TextSpec("caption"), TransformSpec(), range)
}
