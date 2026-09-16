package com.redcut.engine.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.TimeRange
import com.redcut.domain.render.AudioSpec
import com.redcut.domain.render.OutputSpec
import com.redcut.domain.render.RenderGraph
import com.redcut.domain.render.RenderLayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric runs these tests against API 34; file-scoped so the class annotation can name it. */
private const val ROBOLECTRIC_SDK = 34

/**
 * The graph → Composition mapping, read back off the Media3 values themselves (§12.3's structural
 * half: the preview and the export start from the same graph, so what the export ENCODES is what
 * this file asserts the mapping produces).
 *
 * It runs under Robolectric because the mapping's inputs are real Media3 values — a `MediaItem`
 * parses its `Uri` at construction — and the assertion is about the constructed values, not about
 * pure math.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class RenderGraphMapperTest {

    @Test
    fun `each video layer becomes an item carrying the layer's ranges in order`() {
        val graph = twoLayerGraph()
        val first = graph.videoLayers[0]
        val second = graph.videoLayers[1]

        val composition = RenderGraphMapper.toComposition(graph)

        assertThat(composition.sequences).hasSize(1)
        val items = composition.sequences[0].editedMediaItems
        assertThat(items).hasSize(2)
        // The timeline duration is what Media3 is told the item spends on screen (with speed
        // applied it is not the source range's length), and the clipping is the SOURCE range:
        // a trim must arrive unrounded.
        assertThat(items[0].durationUs).isEqualTo(first.timeRange.durationUs)
        assertThat(items[0].mediaItem.clippingConfiguration.startPositionUs)
            .isEqualTo(first.sourceRange.startUs)
        assertThat(items[0].mediaItem.clippingConfiguration.endPositionUs)
            .isEqualTo(first.sourceRange.endUs)
        assertThat(items[1].durationUs).isEqualTo(second.timeRange.durationUs)
    }

    @Test
    fun `a silent layer encodes without audio and an audible one keeps it`() {
        val graph = twoLayerGraph()

        val composition = RenderGraphMapper.toComposition(graph)

        val items = composition.sequences[0].editedMediaItems
        assertThat(items[0].removeAudio).isFalse()
        assertThat(items[1].removeAudio).isTrue()
    }

    @Test
    fun `the export effects the output spec asks for land on every item`() {
        val graph = twoLayerGraph()
        val effects = RenderGraphMapper.outputVideoEffects(graph.output)

        val composition = RenderGraphMapper.toComposition(graph, effects)

        val items = composition.sequences[0].editedMediaItems
        assertThat(items[0].effects.videoEffects).containsExactlyElementsIn(effects)
        assertThat(items[1].effects.videoEffects).containsExactlyElementsIn(effects)
    }

    @Test
    fun `without export effects the items carry no effects at all`() {
        val composition = RenderGraphMapper.toComposition(twoLayerGraph())

        val items = composition.sequences[0].editedMediaItems
        assertThat(items[0].effects.videoEffects).isEmpty()
    }

    /**
     * Two adjacent layers — the shape [RenderGraph] itself requires — where the second is muted so
     * both sides of the audio rule are visible in one fixture.
     */
    private fun twoLayerGraph(): RenderGraph {
        val first = RenderLayer.Video(
            clipId = "first",
            source = source("first"),
            sourceRange = TimeRange(0L, FIRST_US),
            timeRange = TimeRange(0L, FIRST_US),
        )
        val second = RenderLayer.Video(
            clipId = "second",
            source = source("second"),
            sourceRange = TimeRange(SECOND_SOURCE_IN_US, SECOND_SOURCE_IN_US + SECOND_US),
            timeRange = TimeRange(FIRST_US, FIRST_US + SECOND_US),
            audio = AudioSpec(muted = true),
        )
        return RenderGraph(
            revision = 1L,
            canvas = CanvasSpec.LANDSCAPE_1080,
            layers = listOf(first, second),
            output = OutputSpec(
                width = CanvasSpec.LANDSCAPE_1080.width,
                height = CanvasSpec.LANDSCAPE_1080.height,
            ),
        )
    }

    private fun source(id: String): SourceRef = SourceRef(
        id = id,
        uri = "content://fixture/$id",
        displayName = "$id.mp4",
        durationUs = SOURCE_DURATION_US,
        width = CanvasSpec.LANDSCAPE_1080.width,
        height = CanvasSpec.LANDSCAPE_1080.height,
        hasAudio = true,
    )

    private companion object {
        const val SOURCE_DURATION_US = 10_000_000L
        const val FIRST_US = 2_000_000L
        const val SECOND_US = 3_000_000L
        const val SECOND_SOURCE_IN_US = 1_000_000L
    }
}
