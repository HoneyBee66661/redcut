package com.redcut.engine.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.redcut.domain.render.RenderGraph
import com.redcut.domain.render.RenderLayer

/**
 * `RenderGraph -> Composition`, and the only place that translation happens (spec §6.8 rule D3:
 * *"All `RenderGraph → Composition` translation lives in `RenderGraphMapper`. UI code never constructs
 * a Media3 effect inline."*).
 *
 * Rule D3's stated cost of scattering this is what the file is shaped around: *"Effect construction
 * scattered across the UI means the native swap touches dozens of files."* So the mapper is an
 * `internal object` in the one module that owns Media3, and its whole input is a domain value.
 *
 * ### What it maps today, and what it does not
 *
 * Mapped, per video layer: the source file, the **source range** it reads (`sourceRange`, carried
 * exactly — Media3's clipping setters take microseconds, so a trim is not rounded to the platform's
 * millisecond unit), the **timeline duration** the layer occupies (`timeRange`), the clip's speed, and
 * audio removed for a layer the graph reports as silent.
 *
 * Not mapped yet, and named here rather than discovered later: `RenderLayer.Video.transform`,
 * `.keyframes`, `.effects`, `.reverse`, `.fades`, the `Transition`s, and the `Text`/`Image` overlay
 * layers. Each belongs to the phase that gives it meaning (FR-4 for effects and overlays, FR-4.5 for
 * dissolves) and each is a *declarative value already on the graph* — which is rule D2's whole point:
 * the chain can be compiled later, by this mapper or by the C++ core, because it was never a
 * constructed shader object. Until then the preview shows the base video track, which is what the Cut
 * stage edits.
 *
 * The transform is the one entry on that list that is a *function of time* rather than a value, and it
 * is labelled here because the difference is a trap: a mapping that read `layer.transform` per frame
 * would render every keyframed clip at its static crop — animated in the preview, frozen in the
 * export, which is the exact defect WS K exists to close (§12.3). A frame's transform is
 * `layer.transformAt(frameUs - layer.timeRange.startUs)`, the only place the static half and the keyed
 * half are folded together; `layer.keyframes` is carried on the layer for that function and nothing
 * else.
 *
 * That the graph carries them and the mapping is one file is also what keeps §12.3 honest: when the
 * export path is built it starts from this same graph, and anything this mapper gets wrong is wrong in
 * both paths at once instead of in one of them.
 */
@OptIn(UnstableApi::class)
internal object RenderGraphMapper {

    /**
     * The graph as a Media3 [Composition].
     *
     * One sequence: MVP is a single video track (FR-2), and the spec's own mapping table (§8.2) makes
     * "ordered clips" one `EditedMediaItemSequence`.
     */
    fun toComposition(graph: RenderGraph): Composition {
        val video = EditedMediaItemSequence.withAudioAndVideoFrom(
            graph.videoLayers.map { toEditedMediaItem(it) },
        )
        return Composition.Builder(video).build()
    }

    /**
     * One video layer as an `EditedMediaItem`.
     *
     * [EditedMediaItem.Builder.setDurationUs] is set from the layer's **timeline** range and not from
     * its source range: with `speed` applied, the timeline range is the duration the finished video
     * spends on this clip, and that is the number Media3 needs. Deriving it here from the source range
     * divided by the speed would be a second implementation of arithmetic the graph has already done.
     */
    private fun toEditedMediaItem(layer: RenderLayer.Video): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(layer.source.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(layer.sourceRange.startUs)
                    .setEndPositionUs(layer.sourceRange.endUs)
                    .build(),
            )
            .build()

        return EditedMediaItem.Builder(item)
            .setDurationUs(layer.timeRange.durationUs)
            .setSpeed(speedProvider(layer.speed))
            .setRemoveAudio(layer.audio.isSilent)
            .build()
    }

    /**
     * The layer's speed as Media3's provider form.
     *
     * [`SpeedProvider.DEFAULT`] rather than a constant provider at 1f: the default is the one the
     * player already assumes, and handing it back is how "this clip plays at normal speed" stays a
     * statement the player can act on cheaply.
     */
    private fun speedProvider(speed: Float): SpeedProvider =
        if (speed == DEFAULT_SPEED) SpeedProvider.DEFAULT else ConstantSpeed(speed)

    /** Normal speed, which is what the document model starts a clip at. */
    private const val DEFAULT_SPEED = 1f
}

/**
 * One constant speed for the whole item.
 *
 * Media3's `SpeedProvider` is a *function of time*, which the MVP does not need and the graph cannot
 * express: `Clip.speed` is one number per clip (FR-3.1), so this reports it for every timestamp and
 * says there is no next change. `C.TIME_UNSET` is Media3's word for that, not a magic value.
 */
@OptIn(UnstableApi::class)
private class ConstantSpeed(private val speed: Float) : SpeedProvider {

    override fun getSpeed(timeUs: Long): Float = speed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}
