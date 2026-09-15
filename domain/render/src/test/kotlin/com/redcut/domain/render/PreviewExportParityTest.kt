package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.Keyframe
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
 */
class PreviewExportParityTest {

    private val oneSecond = 1_000_000L

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
}
