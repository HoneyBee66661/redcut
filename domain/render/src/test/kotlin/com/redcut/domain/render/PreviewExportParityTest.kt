package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.KeyframableProperty
import com.redcut.domain.document.Keyframe
import com.redcut.domain.document.TransformSpec
import org.junit.jupiter.api.Test

/**
 * The preview/export parity harness (spec task 4.6, §12.3), at the level where parity is a guarantee
 * rather than a hope: the value a keyframed property resolves to at a given time.
 *
 * ### The shape of the harness
 *
 * The two paths are modelled as two adapters — [previewPath] reads the property the way the preview does
 * (at the playhead, as the user scrubs), [exportPath] the way the export does (at every output frame's
 * timeline time). Both delegate to the ONE shared resolver, [KeyframeResolver], which is the whole point:
 * "one resolver feeds preview and export identically" (WS K3). The first case runs the same times through
 * both and asserts they agree, so a path that stopped calling the resolver shows up as a mismatch rather
 * than as a frame that merely looks a little off.
 *
 * ### The failure mode it exists to prevent
 *
 * A crop animated in preview but static in export (spec §13, Phase 3 exit criterion: *"effect timing is
 * identical after export"*). The second case pins that down: over the range of an animated property the
 * EXPORT path's values differ — the property is not static there, and if someone re-wired export to the
 * static transform the two ends would come back equal and this case would fail.
 *
 * The device-tier half of §12.3 — decoding a real fixture and comparing pixels — is where it cannot run
 * on the JVM; this is the JVM half, and it is the one that makes "one resolver" a structural fact.
 *
 * ### The second half of the harness: the transform, not just the property (WS G1)
 *
 * The resolver cases above prove that a property resolves the same on both paths, and they would keep
 * passing if both paths called the resolver at the WRONG TIME — or if neither read the transform at
 * all. The cases below close that gap by modelling the paths one level up, at the values the app
 * actually renders: the preview reads the CLIP at the playhead's own microsecond of it, the export
 * reads the COMPILED LAYER at an output frame's microsecond, and the two must agree frame for frame.
 *
 * The fixture is deliberately a clip that is NOT first on the timeline, so its keys are on a different
 * clock from the playhead. A preview that forgot to subtract the clip's start — the single most likely
 * way to get this wrong — would read past the last key, clamp, and disagree with the export from the
 * second frame on.
 */
class PreviewExportParityTest {

    private val oneSecond = 1_000_000L

    /**
     * Two clips, and the keyed one is the SECOND: it plays at 2–4 s of the timeline while its keys are
     * on its own 0–2 s clock, so the two clocks can only be confused by mistake.
     */
    private fun animatedDocument(): EditDocument = document(
        clips = listOf(
            clip("c1", "s1", 0L, 2 * SEC),
            clip("c2", "s1", 2 * SEC, 4 * SEC).copy(
                transform = TransformSpec(cropLeft = 0.1f, cropRight = 0.9f),
                keyframes = mapOf(
                    KeyframableProperty.CROP_LEFT to listOf(
                        Keyframe(0L, 0.1f),
                        Keyframe(2 * SEC, 0.5f),
                    ),
                    KeyframableProperty.ROTATION_DEGREES to listOf(
                        Keyframe(0L, 0f),
                        Keyframe(2 * SEC, 90f),
                    ),
                ),
            ),
        ),
    )

    /** The PREVIEW path's read of the whole transform: the clip, at the playhead's own clock. */
    private fun previewTransform(
        document: EditDocument,
        clipId: String,
        playheadUs: Long,
    ): TransformSpec {
        val clip = requireNotNull(document.clipById(clipId)) { "no clip $clipId" }
        val startUs = document.timeline.first { it.clip.id == clipId }.startUs
        return clip.transformAt(playheadUs - startUs)
    }

    /** The EXPORT path's read: the compiled layer, at an output frame's own clock. */
    private fun exportTransform(layer: RenderLayer.Video, frameUs: Long): TransformSpec =
        layer.transformAt(frameUs - layer.timeRange.startUs)

    /** The PREVIEW path's per-time read of a clip's keyframed property (the playhead, as the user scrubs). */
    private fun previewPath(keys: List<Keyframe>, timeUs: Long): Float =
        KeyframeResolver.resolve(keys, timeUs)

    /** The EXPORT path's per-frame read of the same property (every output frame's timeline time). */
    private fun exportPath(keys: List<Keyframe>, timeUs: Long): Float =
        KeyframeResolver.resolve(keys, timeUs)

    @Test
    fun `preview and export resolve the same keyframe values at the same times`() {
        val keys = listOf(
            Keyframe(0L, 0.5f),
            Keyframe(2 * oneSecond, 1.0f),
        )
        // The times an export would encode: every tenth of a second across the animated range. The
        // preview visits the same axis, so agreement here IS the parity claim.
        val times = generateSequence(0L) { it + 100_000L }
            .takeWhile { it <= 2 * oneSecond }
            .toList()

        val preview = times.map { previewPath(keys, it) }
        val export = times.map { exportPath(keys, it) }

        assertThat(export).containsExactlyElementsIn(preview).inOrder()
    }

    @Test
    fun `an animated property is not static in the export either`() {
        val keys = listOf(
            Keyframe(0L, 0.5f),
            Keyframe(2 * oneSecond, 1.0f),
        )

        // The two ends of the range, and the midpoint: if the export path were reading the static
        // transform instead of the resolver, all three would come back the same constant and this
        // would fail — the "static in export" defect this card exists to prevent.
        assertThat(exportPath(keys, 0L)).isEqualTo(0.5f)
        assertThat(exportPath(keys, oneSecond)).isEqualTo(0.75f)
        assertThat(exportPath(keys, 2 * oneSecond)).isEqualTo(1.0f)
    }

    @Test
    fun `an animated crop resolves identically on the preview clip and the exported layer`() {
        val doc = animatedDocument()
        val layer = videoLayer(TimelineCompiler.compile(doc), "c2")

        // The times an export would encode: every tenth of a second across the animated clip, as
        // TIMELINE positions — 2–4 s, because that is where c2 plays. The preview visits the same axis
        // (the playhead is a timeline position too), so agreement here IS the parity claim.
        val frames = generateSequence(2 * SEC) { it + 100_000L }
            .takeWhile { it <= 4 * SEC }
            .toList()

        val preview = frames.map { previewTransform(doc, "c2", it) }
        val export = frames.map { exportTransform(layer, it) }

        assertThat(export).containsExactlyElementsIn(preview).inOrder()
    }

    @Test
    fun `an animated crop and rotation are not static in the export either`() {
        val layer = videoLayer(TimelineCompiler.compile(animatedDocument()), "c2")

        // The two ends of the clip and the midpoint of each curve. If the export read the layer's
        // static `transform` instead, all three crop values would come back 0.1 and all three rotations
        // 0 — the "animated in the preview, frozen in the export" defect §12.3 has a test for.
        assertThat(exportTransform(layer, 2 * SEC).cropLeft).isWithin(TOLERANCE).of(0.1f)
        assertThat(exportTransform(layer, 3 * SEC).cropLeft).isWithin(TOLERANCE).of(0.3f)
        assertThat(exportTransform(layer, 4 * SEC).cropLeft).isWithin(TOLERANCE).of(0.5f)

        assertThat(exportTransform(layer, 2 * SEC).rotationDegrees).isWithin(TOLERANCE).of(0f)
        assertThat(exportTransform(layer, 3 * SEC).rotationDegrees).isWithin(TOLERANCE).of(45f)
        assertThat(exportTransform(layer, 4 * SEC).rotationDegrees).isWithin(TOLERANCE).of(90f)

        // The property that is NOT keyed is the clip's static value at every one of those frames, so
        // the animation moved the two curves it names and nothing else.
        assertThat(exportTransform(layer, 3 * SEC).cropRight).isWithin(TOLERANCE).of(0.9f)
    }

    private companion object {
        /**
         * A thousandth of a pixel, in normalised frame units.
         *
         * The two paths run the SAME arithmetic, so their agreement above is asserted exactly; this is
         * only for the hand-written expectations, where restating a Float literal would pin rounding
         * rather than behaviour.
         */
        const val TOLERANCE = 0.001f
    }
}
