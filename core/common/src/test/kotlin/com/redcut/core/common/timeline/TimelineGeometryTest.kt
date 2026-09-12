package com.redcut.core.common.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The timeline's arithmetic (spec §7.1, FR-2.1, A1, NFR-8).
 *
 * Every rule the timeline draws with is here, with pixel values a reader can check by hand: one
 * second is 60 px at the default zoom, a 100 ms clip at minimum zoom is a sliver, a boundary
 * belongs to the clip on its right. The Canvas above this has nothing left to get wrong except
 * drawing the rectangles these functions return.
 */
class TimelineGeometryTest {

    private val oneSecond = 1_000_000L

    private fun geometry(
        viewportWidthPx: Float = 360f,
        spans: List<ClipSpan> = emptyList(),
        zoom: TimelineZoom = TimelineZoom.DEFAULT,
        scrollPx: Float = 0f,
        density: Float = 1f,
    ) = TimelineGeometry(viewportWidthPx, spans, zoom, scrollPx, density)

    /** Three clips of 4 s, 2 s and 1 s, laid end to end from 0. */
    private val threeClips = spansOf(
        listOf(
            ClipTiming("a", 4 * oneSecond),
            ClipTiming("b", 2 * oneSecond),
            ClipTiming("c", oneSecond),
        ),
    )

    // --- Conversions -------------------------------------------------------

    @Test
    fun `a second is the zoom's width in pixels`() {
        val geometry = geometry(zoom = TimelineZoom(60f))

        assertThat(geometry.pxFor(oneSecond)).isWithin(0.001f).of(60f)
        assertThat(geometry.pxFor(oneSecond / 2)).isWithin(0.001f).of(30f)
        assertThat(geometry.pxFor(0)).isEqualTo(0f)
    }

    @Test
    fun `pixels convert back to microseconds`() {
        val geometry = geometry(zoom = TimelineZoom(60f))

        assertThat(geometry.usFor(60f)).isEqualTo(oneSecond)
        assertThat(geometry.usFor(30f)).isEqualTo(oneSecond / 2)
    }

    @Test
    fun `a drag that lands mid-pixel still produces a whole microsecond`() {
        // A trim drag produces a time from a pixel; a lossy conversion here would drift the
        // in-point by a frame every few drags.
        val geometry = geometry(zoom = TimelineZoom(60f))

        assertThat(geometry.usFor(geometry.pxFor(2_500_000L))).isEqualTo(2_500_000L)
    }

    @Test
    fun `a screen position becomes a content position through the scroll offset`() {
        // A long timeline, because a short one cannot scroll: the offset is clamped to what the
        // content allows, and a test that ignored that would be asserting a scroll position the
        // app can never be in.
        val tenClips = spansOf((1..10).map { ClipTiming("clip-$it", 10 * oneSecond) })
        val geometry = geometry(spans = tenClips, scrollPx = 120f)

        assertThat(geometry.contentPxFor(0f)).isWithin(0.001f).of(120f)
        assertThat(geometry.contentPxFor(40f)).isWithin(0.001f).of(160f)
    }

    // --- Spans -------------------------------------------------------------

    @Test
    fun `spans are prefix-summed from the durations`() {
        // Spec §5.1: timeline position is derived, never stored. This is the derivation.
        assertThat(threeClips.map { it.startUs })
            .containsExactly(0L, 4 * oneSecond, 6 * oneSecond)
            .inOrder()
        assertThat(threeClips.map { it.endUs })
            .containsExactly(4 * oneSecond, 6 * oneSecond, 7 * oneSecond)
            .inOrder()
    }

    @Test
    fun `an empty timeline has no spans and no clips`() {
        assertThat(spansOf(emptyList())).isEmpty()
    }

    // --- Rects -------------------------------------------------------------

    @Test
    fun `clip rects sit where their start times say`() {
        val rects = geometry(spans = threeClips).clipRects()

        assertThat(rects.map { it.clipId }).containsExactly("a", "b", "c").inOrder()
        assertThat(rects[0].startPx).isWithin(0.001f).of(0f)
        assertThat(rects[0].endPx).isWithin(0.001f).of(240f)
        assertThat(rects[1].startPx).isWithin(0.001f).of(240f)
        assertThat(rects[2].endPx).isWithin(0.001f).of(420f)
    }

    @Test
    fun `a clip is never drawn wider than time says, and never narrower than the minimum`() {
        // The one exception to "the timeline never distorts time": a 100 ms clip at the most
        // zoomed-out setting would be 0.2 px, which is a clip the user cannot see exists.
        val sliver =
            geometry(spans = listOf(ClipSpan("tiny", 0, 100_000)), zoom = TimelineZoom.MINIMUM)

        val rect = sliver.clipRects().single()
        assertThat(rect.widthPx).isEqualTo(ClipRect.MIN_DRAWN_WIDTH_PX)
        assertThat(rect.isBelowMinimumWidth).isTrue()
    }

    @Test
    fun `the total width is the last clip's end plus padding, not the sum of the rects`() {
        val geometry = geometry(spans = threeClips)

        assertThat(geometry.totalWidthPx)
            .isWithin(0.001f)
            .of(420f + TimelineGeometry.END_PADDING_PX)
    }

    // --- Scroll ------------------------------------------------------------

    @Test
    fun `scroll is held inside what the content can scroll to`() {
        val geometry = geometry(spans = threeClips)

        assertThat(geometry.maxScrollPx).isWithin(0.001f).of(84f)
        assertThat(geometry.scrollClampedTo(-50f)).isEqualTo(0f)
        assertThat(geometry.scrollClampedTo(1_000f)).isWithin(0.001f).of(84f)
        assertThat(geometry.scrollClampedTo(40f)).isWithin(0.001f).of(40f)
    }

    @Test
    fun `content shorter than the viewport does not scroll at all`() {
        val geometry = geometry(viewportWidthPx = 1_000f, spans = threeClips)

        assertThat(geometry.maxScrollPx).isEqualTo(0f)
        assertThat(geometry.scrollClampedTo(200f)).isEqualTo(0f)
    }

    @Test
    fun `only the rects near the viewport are returned, with a screen of margin`() {
        // Ten 10-second clips at 60 px/s are 6000 px of content, and the draw pass must not build
        // rects for the ones a scroll away (NFR-8's jank budget is about exactly this).
        val tenClips = spansOf((1..10).map { ClipTiming("clip-$it", 10 * oneSecond) })

        val visible = geometry(spans = tenClips).visibleRects()

        assertThat(visible.map { it.clipId }).containsExactly("clip-1", "clip-2").inOrder()
    }

    // --- Playhead ----------------------------------------------------------

    @Test
    fun `the playhead is drawn where its time is, when it is on screen`() {
        // Scroll 60, not 100: this timeline's content allows 84 px of scroll, and the geometry
        // holds the offset inside that (the view cannot be scrolled past its own content).
        val geometry = geometry(spans = threeClips, scrollPx = 60f)

        assertThat(geometry.playheadPx(2 * oneSecond)).isWithin(0.001f).of(60f)
    }

    @Test
    fun `a playhead scrolled out of view is not drawn`() {
        val tenClips = spansOf((1..10).map { ClipTiming("clip-$it", 10 * oneSecond) })

        assertThat(geometry(spans = tenClips, scrollPx = 1_000f).playheadPx(oneSecond)).isNull()
    }

    // --- Hit testing (FR-2.1) ----------------------------------------------

    @Test
    fun `a touch in the middle of a clip selects it`() {
        val hit = geometry(spans = threeClips).hitTest(100f)

        assertThat(hit).isEqualTo(TimelineHit.Body("a"))
    }

    @Test
    fun `a touch near a boundary belongs to the clip on the right of it`() {
        // x = 230 is inside clip "a" (0..240) and within the touch target of "b"'s in-point.
        // Resolving this as "a's right edge" would make trimming the second clip's start
        // impossible without zooming, because the user cannot touch a boundary from its right.
        val hit = geometry(spans = threeClips).hitTest(230f)

        assertThat(
            hit,
        ).isEqualTo(TimelineHit.Edge(clipId = "b", side = EdgeSide.LEFT, withinClip = false))
    }

    @Test
    fun `a touch past the last clip's touch target hits nothing`() {
        val geometry = geometry(spans = threeClips)

        // 420 is the last clip's end; the target reaches 420 + 48 = 468.
        assertThat(geometry.hitTest(460f))
            .isEqualTo(TimelineHit.Edge(clipId = "c", side = EdgeSide.RIGHT, withinClip = false))
        assertThat(geometry.hitTest(480f)).isEqualTo(TimelineHit.None)
    }

    @Test
    fun `an empty timeline hits nothing`() {
        assertThat(geometry().hitTest(100f)).isEqualTo(TimelineHit.None)
    }

    @Test
    fun `an edge zone does not reach its full target into a narrow neighbour`() {
        // A 10-second clip followed by a 333 ms one (20 px at 60 px/s). Without the cap, the wide
        // clip's out-point target would cover the narrow clip's whole body: a clip you can trim
        // but never select.
        val spans =
            spansOf(listOf(ClipTiming("wide", 10 * oneSecond), ClipTiming("narrow", 333_000)))
        val geometry = geometry(spans = spans)

        // 5 px in: inside the narrow clip's half-width, so the wide clip's out-point reaches here.
        assertThat(geometry.hitTest(605f))
            .isEqualTo(TimelineHit.Edge(clipId = "wide", side = EdgeSide.RIGHT, withinClip = false))
        // 15 px in: past the cap, so the narrow clip's own edge zone wins instead.
        assertThat(geometry.hitTest(615f))
            .isEqualTo(
                TimelineHit.Edge(clipId = "narrow", side = EdgeSide.RIGHT, withinClip = true),
            )
    }

    @Test
    fun `the edge target scales with density, because 48 is a dp figure`() {
        // At density 3 the target is 144 px: a touch 100 px from a boundary is an edge there and
        // a body at density 1.
        val spans = listOf(ClipSpan("a", 0, 10 * oneSecond))

        assertThat(geometry(spans = spans).hitTest(100f)).isEqualTo(TimelineHit.Body("a"))
        assertThat(geometry(spans = spans, density = 3f).hitTest(100f))
            .isEqualTo(TimelineHit.Edge(clipId = "a", side = EdgeSide.LEFT, withinClip = true))
    }

    @Test
    fun `a very short clip is all edge, and that is stated rather than hidden`() {
        // A 100 ms clip at minimum zoom is 3 px wide with a 48 px target. There is no arithmetic
        // in which its centre is a "body"; the honest behaviour is the edge, and the user zooms
        // in. What the cap on the zone DOES buy: it cannot reach past its own half-width, so a
        // neighbour's body is never stolen by it.
        val sliver = geometry(
            spans = listOf(ClipSpan("tiny", 0, 100_000)),
            zoom = TimelineZoom.MINIMUM,
        )

        assertThat(sliver.hitTest(1.5f))
            .isEqualTo(TimelineHit.Edge(clipId = "tiny", side = EdgeSide.LEFT, withinClip = true))
    }

    // --- Zoom --------------------------------------------------------------

    @Test
    fun `zoom is clamped into the range the timeline can draw`() {
        assertThat(TimelineZoom(0.1f).clamped().pixelsPerSecond).isEqualTo(2f)
        assertThat(TimelineZoom(1_000f).clamped().pixelsPerSecond).isEqualTo(480f)
        assertThat(TimelineZoom(60f).clamped().pixelsPerSecond).isEqualTo(60f)
    }

    @Test
    fun `a pinch that produces nonsense falls back to the default zoom`() {
        // Pinch gestures divide by a span that can be zero; a NaN zoom would propagate into every
        // rect on the timeline and draw nothing at all.
        assertThat(TimelineZoom(Float.NaN).clamped()).isEqualTo(TimelineZoom.DEFAULT)
        assertThat(TimelineZoom(Float.POSITIVE_INFINITY).clamped()).isEqualTo(TimelineZoom.DEFAULT)
    }

    @Test
    fun `at maximum zoom a frame is wider than a pixel, so trimming can be frame-accurate`() {
        // FR-2.9 and FR-2.1: frame-accurate cuts are only honest if the user can see the frame
        // they are landing on. 16.667 ms at 480 px/s is 8 px.
        val frameAt60Fps = 16_667L

        assertThat(geometry(zoom = TimelineZoom.MAXIMUM).pxFor(frameAt60Fps)).isGreaterThan(1f)
    }

    @Test
    fun `the zoom reports when it is at an end of its range`() {
        assertThat(TimelineZoom.MINIMUM.isAtMinimum).isTrue()
        assertThat(TimelineZoom.MINIMUM.isAtMaximum).isFalse()
        assertThat(TimelineZoom.MAXIMUM.isAtMaximum).isTrue()
        assertThat(TimelineZoom.DEFAULT.isAtMinimum).isFalse()
    }

    // --- Zoom model (pinch) ------------------------------------------------

    @Test
    fun `zooming keeps the moment under the fingers in place`() {
        // The difference between a pinch that feels like the timeline is a physical object and one
        // that feels like it is sliding away: the frame under the gesture must not move.
        val before = geometry(spans = threeClips, zoom = TimelineZoom(60f))
        val anchorScreenX = 180f
        val timeUnderTheFingers = before.usFor(before.contentPxFor(anchorScreenX))

        val after = before.zoomedAround(anchorScreenX, TimelineZoom(120f))

        assertThat(after.zoom.pixelsPerSecond).isEqualTo(120f)
        assertThat(after.usFor(after.contentPxFor(anchorScreenX))).isEqualTo(timeUnderTheFingers)
    }

    @Test
    fun `zooming out about the left edge puts the start of the timeline back at the edge`() {
        val before = geometry(spans = threeClips, scrollPx = 60f)

        val after = before.zoomedAround(0f, TimelineZoom(10f))

        assertThat(after.visibleStartPx).isEqualTo(0f)
    }

    @Test
    fun `zooming out can leave a scroll offset out of range, and reads clamp it`() {
        val before = geometry(spans = threeClips, scrollPx = 84f) // scrolled to the end

        // Anchor at the right edge, so zooming out pulls the content leftwards past its own start.
        val zoomedOut = before.zoomedAround(360f, TimelineZoom(10f))

        assertThat(zoomedOut.scrollPx).isLessThan(0f)
        assertThat(zoomedOut.visibleStartPx).isEqualTo(0f)
        assertThat(zoomedOut.visibleRects()).isNotEmpty()
    }

    // --- Ruler -------------------------------------------------------------

    @Test
    fun `the ruler interval is the smallest round one that does not crowd`() {
        // 60 px/s: a 1 s label would be 60 px apart, tighter than the 64 px floor, so the ruler
        // steps up to 5 s.
        assertThat(geometry(zoom = TimelineZoom(60f)).rulerIntervalUs()).isEqualTo(5_000_000L)
        // 480 px/s: 1 s is 480 px apart, comfortable.
        assertThat(geometry(zoom = TimelineZoom(480f)).rulerIntervalUs()).isEqualTo(1_000_000L)
        // 2 px/s (zoomed all the way out): 1 s is 2 px apart, so the ruler steps to minutes.
        assertThat(geometry(zoom = TimelineZoom(2f)).rulerIntervalUs()).isEqualTo(60_000_000L)
    }

    @Test
    fun `ruler ticks land on round times inside the visible window`() {
        val ticks = geometry(spans = threeClips, zoom = TimelineZoom(60f)).rulerTicks()

        // 5 s interval, 360 px of viewport at 60 px/s = 6 s visible: ticks at 0 and 5 s.
        assertThat(ticks).containsExactly(0L, 5_000_000L).inOrder()
    }

    @Test
    fun `the ruler follows the scroll position, and never draws past the content`() {
        // Ten 10-second clips: 6000 px of content, so 240 px of scroll is reachable (the short
        // three-clip timeline clamps at 84 px). Scrolled to 4 s, the visible window is 4 s to 10 s
        // at 60 px/s, and the ruler starts at the first ROUND time inside it.
        val tenClips = spansOf((1..10).map { ClipTiming("clip-$it", 10 * oneSecond) })
        val geometry = geometry(spans = tenClips, zoom = TimelineZoom(60f), scrollPx = 240f)

        assertThat(geometry.rulerTicks()).containsExactly(5_000_000L, 10_000_000L).inOrder()
    }

    @Test
    fun `the ruler stops at the end of the content`() {
        // A 7-second timeline scrolled to its end shows no tick past 7 s, even though there is
        // empty viewport to the right of it: a tick there is a time this project does not have.
        val geometry = geometry(spans = threeClips, zoom = TimelineZoom(60f), scrollPx = 84f)

        assertThat(geometry.rulerTicks()).containsExactly(5_000_000L)
    }

    @Test
    fun `an empty timeline has no ruler ticks`() {
        assertThat(geometry().rulerTicks()).isEmpty()
    }

    // --- The playhead's clip ----------------------------------------------

    @Test
    fun `the clip at the playhead is the one whose span contains it`() {
        val geometry = geometry(spans = threeClips)

        assertThat(geometry.clipAt(0L)?.clipId).isEqualTo("a")
        assertThat(geometry.clipAt(4 * oneSecond - 1)?.clipId).isEqualTo("a")
        assertThat(geometry.clipAt(4 * oneSecond)?.clipId).isEqualTo("b")
        assertThat(geometry.clipAt(6 * oneSecond + 500_000)?.clipId).isEqualTo("c")
    }

    @Test
    fun `a playhead past the end of the timeline is in no clip`() {
        // FR-2.2: a cut with the playhead outside the clip is a no-op with an explanation, so
        // this has to answer "no clip" rather than clamping to the last one.
        val geometry = geometry(spans = threeClips)

        assertThat(geometry.clipAt(7 * oneSecond)).isNull()
        assertThat(geometry.clipAt(9 * oneSecond)).isNull()
    }
}
