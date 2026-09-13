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
    fun `the scroll range is a fact about content versus viewport`() {
        // `maxScrollPx` survives the fixed-centre model as an answer to "how much content is off screen",
        // which is what a minimap or a "fit to view" button would ask. Nothing positions the viewport with
        // it any more: the viewport follows the playhead, and `scrollCentering` says where that is.
        val geometry = geometry(spans = threeClips)

        assertThat(geometry.maxScrollPx).isWithin(0.001f).of(84f)
    }

    @Test
    fun `content shorter than the viewport has nothing off screen`() {
        val geometry = geometry(viewportWidthPx = 1_000f, spans = threeClips)

        assertThat(geometry.maxScrollPx).isEqualTo(0f)
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
        // Scroll 60: `playheadPx` maps a time to its screen position, which is the expression the draw pass
        // uses. (It no longer says anything about the scroll being "held inside" the content — since UI
        // revision 1 nothing clamps the scroll, and the viewport follows the playhead.)
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
    fun `a clip narrower than two touch targets still has a body to drag the playhead in`() {
        // The device pass's bug, as a test. A freshly imported clip is drawn short at the default zoom,
        // and its two 48 dp edge targets met in the MIDDLE: every drag inside it trimmed, so the clip
        // shrank under the user's finger when they meant to move the playhead.
        //
        // A 2 s clip at 60 px/s is 120 px wide, so a third per side is 40 px — the cap bites before the
        // touch target does, and the middle 40 px can only be the body.
        val spans = listOf(ClipSpan("short", 0, 2 * oneSecond))
        val geometry = geometry(spans = spans, zoom = TimelineZoom(60f))

        assertThat(geometry.hitTest(5f))
            .isEqualTo(TimelineHit.Edge("short", EdgeSide.LEFT, withinClip = true))
        assertThat(geometry.hitTest(60f)).isEqualTo(TimelineHit.Body("short"))
        assertThat(geometry.hitTest(115f))
            .isEqualTo(TimelineHit.Edge("short", EdgeSide.RIGHT, withinClip = true))
    }

    @Test
    fun `a very short clip keeps a third of itself for the body`() {
        // 100 ms at minimum zoom is 3 px wide, the minimum drawn width. The old rule gave each end half
        // of that and called the clip "all edge, and that is stated rather than hidden" — honest, and
        // still wrong: nobody trims from the middle of a 3 px sliver on purpose, and they CAN zoom in.
        // What they could not do was drag the playhead across it.
        val sliver = geometry(
            spans = listOf(ClipSpan("tiny", 0, 100_000)),
            zoom = TimelineZoom.MINIMUM,
        )

        // Each end reaches a third of 3 px; the middle is the body.
        assertThat(sliver.hitTest(1.5f)).isEqualTo(TimelineHit.Body("tiny"))
        assertThat(sliver.hitTest(0.5f))
            .isEqualTo(TimelineHit.Edge("tiny", EdgeSide.LEFT, withinClip = true))
    }

    // --- The centred playhead (UI revision 1) ------------------------------

    @Test
    fun `centering puts the playhead at the middle of the viewport`() {
        // 10 s at 60 px/s = 600 px of content in a 360 px viewport: the playhead can be centred from 3 s to
        // 7 s, and 5 s is in that range.
        val geometry = geometry(
            spans = listOf(ClipSpan("a", 0, 10 * oneSecond)),
            zoom = TimelineZoom(60f),
        )

        // 5 s is 300 px in; centred means the viewport starts 180 px earlier (half of 360).
        assertThat(geometry.scrollCentering(5 * oneSecond)).isEqualTo(120f)
        assertThat(geometry.centredPlayheadPx(5 * oneSecond)).isEqualTo(180f)
    }

    @Test
    fun `a playhead at the start sits at the centre, not at the left edge`() {
        // THE device bug, pinned. With the clamp the line rendered at x = 0 for a new project, because a
        // timeline shorter than the viewport has no scroll range to be centred by. The user's words:
        // "apakah redline playhead secara default ada di left mentok layar? harusnya center horizontal fixed".
        val geometry = geometry(
            spans = listOf(ClipSpan("a", 0, 10 * oneSecond)),
            zoom = TimelineZoom(60f),
        )

        // 0 s is 0 px in, so centring it means scrolling half a viewport to the LEFT: empty space before the
        // first frame, which is what a fixed-centre line requires.
        assertThat(geometry.scrollCentering(0)).isEqualTo(-180f)
        assertThat(geometry.centredPlayheadPx(0)).isEqualTo(180f)
    }

    @Test
    fun `a timeline shorter than the viewport is centred too`() {
        // The same bug in its worst form: 1 s of content (60 px) in a 360 px viewport. There is no scrolling
        // to be had at all, and the old code therefore drew the line wherever the time fell.
        val geometry = geometry(
            spans = listOf(ClipSpan("a", 0, oneSecond)),
            zoom = TimelineZoom(60f),
        )

        assertThat(geometry.maxScrollPx).isEqualTo(0f)
        assertThat(geometry.scrollCentering(oneSecond / 2)).isEqualTo(-150f)
        assertThat(geometry.centredPlayheadPx(oneSecond / 2)).isEqualTo(180f)
        assertThat(geometry.centredPlayheadPx(0)).isEqualTo(180f)
    }

    @Test
    fun `the line stays at the centre at the end of the timeline too`() {
        val geometry = geometry(
            spans = listOf(ClipSpan("a", 0, 10 * oneSecond)),
            zoom = TimelineZoom(60f),
        )

        // 600 px of content, so the last moment is centred by scrolling to 420 — and the empty space is now
        // AFTER the last frame rather than the line drifting right to meet the content's edge.
        assertThat(geometry.scrollCentering(10 * oneSecond)).isEqualTo(420f)
        assertThat(geometry.centredPlayheadPx(10 * oneSecond)).isEqualTo(180f)
    }

    @Test
    fun `the centred line is where the geometry's own mapping says the playhead is`() {
        // The invariant the Canvas relies on, stated as an equality rather than as two similar formulas.
        val geometry =
            geometry(spans = listOf(ClipSpan("a", 0, 10 * oneSecond)), zoom = TimelineZoom(60f))
        val us = 4 * oneSecond
        val scrolled = geometry.copy(scrollPx = geometry.scrollCentering(us))

        assertThat(
            scrolled.centredPlayheadPx(us),
        ).isEqualTo(geometry.pxFor(us) - scrolled.visibleStartPx)
    }

    // --- Zoom --------------------------------------------------------------

    @Test
    fun `zoom is clamped into the range the timeline can draw`() {
        // The floor is 0.1 px/s (an hour on one screen) and the ceiling 480 (a frame at 8 px), both set by
        // the user's answers in UI revision 2. The values below are OUTSIDE the range, which is the point.
        assertThat(TimelineZoom(0.01f).clamped().pixelsPerSecond).isEqualTo(0.1f)
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
    fun `the zoom reaches from an hour down to a frame`() {
        // The user's two answers, as arithmetic: "shrink max per 1 hr, incase video is hours long" and
        // "paling coarse 1 detik, 0.5, 0.1, per frame". An hour must fit a 360 px viewport at the coarsest
        // end, and a 60 fps frame must be a comfortable number of pixels at the tight end.
        val anHourPx = 3_600f * TimelineZoom.MINIMUM.pixelsPerSecond
        assertThat(anHourPx).isAtMost(360f)

        val oneFramePx = TimelineZoom.MAXIMUM.pixelsPerSecond * (1f / 60f)
        assertThat(oneFramePx).isAtLeast(4f)
    }

    @Test
    fun `the ruler's ladder includes every granularity the user named`() {
        // A frame, 0.1 s, 0.5 s, 1 s — and ascending, because `rulerIntervalUs` picks the first interval that
        // does not crowd: a list out of order would silently pick a coarser one than the zoom allows.
        val ladder = TimelineGeometry.TICK_INTERVALS_US

        assertThat(ladder).containsAtLeast(
            TimelineGeometry.FRAME_INTERVAL_US,
            100_000L,
            500_000L,
            1_000_000L,
        )
        assertThat(ladder).isInStrictOrder()
    }

    @Test
    fun `the zoom reports when it is at an end of its range`() {
        assertThat(TimelineZoom.MINIMUM.isAtMinimum).isTrue()
        assertThat(TimelineZoom.MINIMUM.isAtMaximum).isFalse()
        assertThat(TimelineZoom.MAXIMUM.isAtMaximum).isTrue()
        assertThat(TimelineZoom.DEFAULT.isAtMinimum).isFalse()
    }

    // --- The track body (UI revision 2) ------------------------------------

    @Test
    fun `a track is a fixed height, and density is the only thing that scales it`() {
        // The user: "tinggi track body timeline fixed, tidak fitting container". The geometry has no viewport
        // HEIGHT — it maps x — so the constant lives here where the fast tier can test it, and the draw pass
        // reads it. A height that depended on the canvas would show a taller clip instead of more track.
        assertThat(geometry().trackHeightPx).isEqualTo(56f)
        assertThat(geometry(density = 2f).trackHeightPx).isEqualTo(112f)
    }

    @Test
    fun `the first clip's head stops at the playhead when the body is scrolled right`() {
        // Said twice by the user: "left track body max mentok playhead", then "left clip head saat clip
        // discroll ke kanan, berhenti di playhead. jadi gak ilang ke off screen". Both are this invariant:
        // time 0 can reach the playhead and no further, so the beginning of the timeline never leaves the
        // screen on the right. It holds because a drag moves the PLAYHEAD (clamped to 0..duration) and the
        // viewport follows it — the whole point of deriving the scroll instead of storing it.
        val geometry = geometry(
            spans = listOf(ClipSpan("a", 0, 10 * oneSecond)),
            zoom = TimelineZoom(60f),
        )
        val scrolled = geometry.copy(scrollPx = geometry.scrollCentering(0))

        assertThat(scrolled.visibleStartPx).isEqualTo(-geometry.viewportWidthPx / 2f)
        // The screen position, which is the expression the draw pass uses: content pixels minus the
        // viewport's start. `pxFor` alone is a CONTENT position, and time 0's content position is 0 — the
        // distinction is the whole reason the line lands at the centre rather than at the clip's pixel.
        assertThat(scrolled.pxFor(0) - scrolled.visibleStartPx)
            .isEqualTo(geometry.viewportWidthPx / 2f)
    }

    // --- Zoom model (pinch) ------------------------------------------------

    @Test
    fun `a zoom cannot move the playhead, because the scroll is derived from it`() {
        // The anchor arithmetic is gone with the fixed-centre model, and this test is what replaces it:
        // there is no point that "stays still" during a zoom, because the playhead is the only fixed thing
        // on the screen and the scroll is computed from it. Zooming changes how many pixels a second
        // occupies, and nothing else.
        val playhead = 3 * oneSecond
        val before = geometry(spans = threeClips, zoom = TimelineZoom(60f))
        val after = before.copy(zoom = TimelineZoom(120f))

        assertThat(after.centredPlayheadPx(playhead)).isEqualTo(before.centredPlayheadPx(playhead))
        assertThat(after.scrollCentering(playhead)).isNotEqualTo(before.scrollCentering(playhead))
    }

    @Test
    fun `a negative scroll still reports the rects that are on screen`() {
        // A centred playhead means empty space at the start of a timeline, and empty space must not turn
        // into an empty draw pass: the first clip is half a viewport to the RIGHT and has to be drawn.
        val geometry = geometry(spans = threeClips, scrollPx = -180f)

        assertThat(geometry.visibleRects()).isNotEmpty()
        assertThat(geometry.visibleRects().first().startPx - geometry.visibleStartPx)
            .isGreaterThan(0f)
    }

    // --- Ruler -------------------------------------------------------------

    @Test
    fun `the ruler interval is the smallest round one that does not crowd`() {
        // 60 px/s: a 1 s label would be 60 px apart, tighter than the 64 px floor, so the ruler
        // steps up to 5 s.
        assertThat(geometry(zoom = TimelineZoom(60f)).rulerIntervalUs()).isEqualTo(5_000_000L)
        // 480 px/s: a 1 s label would be comfortable, but a 0.5 s one is 240 px and the ladder now reaches
        // it, so the ruler reads half-seconds at the tight end — which is what frame-accurate cutting wants.
        assertThat(geometry(zoom = TimelineZoom(480f)).rulerIntervalUs()).isEqualTo(500_000L)
        // Zoomed all the way out (0.1 px/s): 1 s is 0.1 px apart, so the ruler steps to minutes.
        assertThat(geometry(zoom = TimelineZoom.MINIMUM).rulerIntervalUs()).isEqualTo(600_000_000L)
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
