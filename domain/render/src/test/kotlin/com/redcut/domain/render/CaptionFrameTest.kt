package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.EffectScope
import com.redcut.domain.document.TextOverlayBox
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import com.redcut.domain.document.toOverlayBox
import com.redcut.domain.document.toTransform
import org.junit.jupiter.api.Test

/**
 * The caption resolver (FR-4.3) — the read the preview and the export burn-in share.
 *
 * The clauses under test are the resolver's whole surface, and each one exists because the two consumers
 * must not be able to disagree about it: WHICH captions are visible (the document's own filter, so the
 * test pins the delegation rather than a re-derivation) and WHAT a visible caption resolves to (the box
 * and the spec carried verbatim, because the burn-in composites the same ink the preview does).
 */
class CaptionFrameTest {

    private fun caption(
        id: String,
        timeRange: TimeRange = TimeRange(0L, SEC),
        scope: EffectScope = EffectScope.Document,
        enabled: Boolean = true,
    ) = AppliedEffect.Text(
        id = id,
        scope = scope,
        timeRange = timeRange,
        spec = TextSpec(content = "word"),
        enabled = enabled,
        transform = TextOverlayBox.DEFAULT.toTransform(),
    )

    @Test
    fun `a caption covering the playhead resolves to its words, box and range`() {
        val doc = document(clips = emptyList(), effects = listOf(caption("t1")))

        val frames = doc.captionFramesAt(SEC / 2)

        assertThat(frames).hasSize(1)
        val frame = frames.single()
        assertThat(frame.effectId).isEqualTo("t1")
        assertThat(frame.spec.content).isEqualTo("word")
        // Verbatim, not re-derived: the burn-in paints the same ink at the same fractions the preview
        // does, which is only provable if the resolver passes the values through untouched.
        assertThat(frame.box).isEqualTo(TextOverlayBox.DEFAULT)
        assertThat(frame.timeRange).isEqualTo(TimeRange(0L, SEC))
    }

    @Test
    fun `a caption outside its range a clip scope or a disabled effect resolves to nothing`() {
        val doc = document(
            clips = emptyList(),
            effects = listOf(
                caption("later", timeRange = TimeRange(2 * SEC, 4 * SEC)),
                caption("clipScoped", scope = EffectScope.Clip("c1")),
                caption("disabled", enabled = false),
            ),
        )

        assertThat(doc.captionFramesAt(SEC)).isEmpty()
    }

    @Test
    fun `the range test is half open like the compiled timeline's`() {
        val doc = document(
            clips = emptyList(),
            effects = listOf(
                caption("ending", timeRange = TimeRange(0L, SEC)),
                caption("starting", timeRange = TimeRange(SEC, 2 * SEC)),
            ),
        )

        // A caption ending exactly at the playhead is over; one starting exactly at it is on.
        assertThat(doc.captionFramesAt(SEC).map { it.effectId }).containsExactly("starting")
    }

    @Test
    fun `the captions come back in stack order`() {
        val doc = document(
            clips = emptyList(),
            effects = listOf(caption("under"), caption("over")),
        )

        // Render order IS stack order; a burn-in that drew them in any other order would composite two
        // overlapping captions the wrong way round. Queried mid-range: the default caption spans (0, SEC)
        // and the range is HALF-OPEN, so SEC is the first instant it is over (see the half-open test).
        assertThat(doc.captionFramesAt(SEC / 2).map { it.effectId })
            .containsExactly("under", "over")
            .inOrder()
    }

    @Test
    fun `the box is the transform's crop rect read as an overlay box`() {
        // The box a DRAG wrote (SetTextTransform) is the box the renderer reads: pinned through the same
        // toOverlayBox conversion the preview's hit test uses, so a moved caption burns where it was
        // dragged. Mid-range query, for the half-open reason above.
        val moved = TextOverlayBox.DEFAULT.movedToCentre(0.5f, 0.2f).toTransform()
        val doc = document(
            clips = emptyList(),
            effects = listOf(
                AppliedEffect.Text(
                    id = "t1",
                    scope = EffectScope.Document,
                    timeRange = TimeRange(0L, SEC),
                    spec = TextSpec(content = "word"),
                    transform = moved,
                ),
            ),
        )

        assertThat(doc.captionFramesAt(SEC / 2).single().box).isEqualTo(moved.toOverlayBox())
    }
}
