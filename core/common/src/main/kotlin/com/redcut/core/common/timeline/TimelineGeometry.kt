package com.redcut.core.common.timeline

/**
 * Timeline geometry: the arithmetic that turns a document into pixel rectangles.
 *
 * ### Why this is here, and why it is pure
 *
 * Spec §7.1 decides the timeline is a custom Compose `Canvas` because "clip edge hit-testing
 * needs pixel precision and a fixed 48 dp touch target independent of clip width". Those two
 * sentences are arithmetic — pixels per second, a minimum width, a hit-test that has to resolve
 * two overlapping touch zones deterministically — and arithmetic is the part of a timeline that
 * goes wrong silently. Putting it in the pure tier (spec §12.1) means every rule below has a
 * test that runs in milliseconds on a JVM, while the Canvas above it stays a thin draw call with
 * nothing in it to get wrong.
 *
 * It lives in `:core:common` rather than a module of its own because the spec's module map (§2.1)
 * has no `:core:timeline`, and adding a module would mean new rules in `tools/check-architecture.sh`
 * for a package of pure functions. The `timeline` package is the marker: nothing here may import
 * an Android or Compose type. If the timeline grows into its own module later (waveforms, overlay
 * layers), this file moves without a single call site changing.
 *
 * ### What the geometry is NOT allowed to do
 *
 * Distort time. Zoom changes how much of the timeline is visible, never the proportion between
 * time and the drawn width of a clip: a 4-second clip is twice as wide as a 2-second one at every
 * zoom level. The minimum width is the one exception, and it only ever makes a clip WIDER than
 * time says — never narrower — so a 100 ms clip at a far-out zoom stays visible and tappable
 * instead of collapsing to nothing.
 */

/**
 * How wide a second is, in pixels. The single knob zoom changes.
 *
 * Bounds are not arbitrary. [MAXIMUM] is set so that a frame at 60 fps is comfortably wider than
 * a pixel — trimming to a frame boundary (FR-2.9, FR-2.1) is only honest if the user can see the
 * frames they are landing on. [MINIMUM] is set so a five-minute project is wider than a phone
 * screen, which is the point at which "zoom out to see everything" stops being useful.
 */
@JvmInline
value class TimelineZoom(val pixelsPerSecond: Float) {

    val isAtMinimum: Boolean get() = pixelsPerSecond <= MINIMUM.pixelsPerSecond
    val isAtMaximum: Boolean get() = pixelsPerSecond >= MAXIMUM.pixelsPerSecond

    /** A zoom the app can draw with: NaN and infinities from a pinch gesture do not survive. */
    fun clamped(): TimelineZoom =
        if (!pixelsPerSecond.isFinite()) DEFAULT else TimelineZoom(coerce())

    private fun coerce(): Float =
        pixelsPerSecond.coerceIn(MINIMUM.pixelsPerSecond, MAXIMUM.pixelsPerSecond)

    companion object {
        /** 2 px/s: a five-minute project is 600 px, wider than a phone. */
        val MINIMUM = TimelineZoom(2f)

        /** 8 px per 60 fps frame (16.67 ms) ≈ 480 px/s, so frame edges are visible. */
        val MAXIMUM = TimelineZoom(480f)

        /** Zoomed out far enough to see a whole short project. */
        val DEFAULT = TimelineZoom(60f)

        /** A pinch multiplies the current zoom; this is how much one gesture step may change it. */
        const val STEP_FACTOR = 1.5f
    }
}

/**
 * One clip as the timeline needs it: an id, where it sits, and how long it is.
 *
 * A projection rather than a [Clip] or an [EditDocument], deliberately. The geometry is asked
 * about a list of spans, which means it can be exercised with three hand-written values instead
 * of a document with sources, effect stacks and history behind it — and it cannot silently start
 * depending on a field that the drawing code has no business reading.
 */
data class ClipSpan(
    val clipId: String,
    val startUs: Long,
    val durationUs: Long,
) {
    val endUs: Long get() = startUs + durationUs
}

/** Where one clip lands on screen, in pixels, relative to the timeline's left edge. */
data class ClipRect(
    val clipId: String,
    val startPx: Float,
    val endPx: Float,
) {
    val widthPx: Float get() = endPx - startPx

    /** True when the clip is narrow enough that its drawn width came from the minimum, not time. */
    val isBelowMinimumWidth: Boolean get() = widthPx <= MIN_DRAWN_WIDTH_PX

    fun contains(x: Float): Boolean = x in startPx..endPx

    companion object {
        /**
         * How wide a clip is drawn at minimum.
         *
         * Not a touch target — that is `TimelineGeometry.EDGE_TOUCH_TARGET_DP`'s job and it is
         * sixteen times bigger. This is only so a very short clip does not become an invisible
         * sliver that the user cannot see is there at all.
         */
        const val MIN_DRAWN_WIDTH_PX = 3f
    }
}

/** What is under a touch, which is the whole reason the geometry exists. */
sealed interface TimelineHit {

    /** Nothing: the empty area past the end of the last clip, or a gap. */
    data object None : TimelineHit

    /** The clip's body: a tap selects it, a drag moves the playhead. */
    data class Body(val clipId: String) : TimelineHit

    /**
     * Within the touch target of one edge: a drag trims (FR-2.1).
     *
     * [withinClip] says which side of the boundary the touch landed on. It matters for a drag
     * that starts on the edge of clip B's left: the user meant B's in-point, but the touch is
     * physically inside A, and resolving that by "whichever clip owns the pixels" is wrong half
     * the time.
     */
    data class Edge(val clipId: String, val side: EdgeSide, val withinClip: Boolean) : TimelineHit
}

enum class EdgeSide { LEFT, RIGHT }

/**
 * The visible window of a timeline, expressed in pixels.
 *
 * [viewportWidthPx] and [scrollPx] come from the Canvas; [zoom] and [density] from the user and
 * the device. Everything else is derived, and nothing is stored twice: [totalWidthPx] is a
 * function of the spans and the zoom, and [maxScrollPx] of the total and the viewport.
 */
data class TimelineGeometry(
    val viewportWidthPx: Float,
    /**
     * The clips this geometry is about, in document order.
     *
     * A constructor property rather than a `var` filled in later: the whole value is compared,
     * copied and recreated on every recomposition, so hidden mutable state would make two
     * geometries that draw differently compare equal — the kind of bug that shows up as a
     * timeline that stops updating.
     */
    val spans: List<ClipSpan> = emptyList(),
    val zoom: TimelineZoom = TimelineZoom.DEFAULT,
    val scrollPx: Float = 0f,
    /** Device density, for the dp-sized touch target. Passed in, never read from a Context. */
    val density: Float = 1f,
) {

    /** Total width of the timeline content, in pixels. */
    val totalWidthPx: Float
        get() = spans.foldWidth() + END_PADDING_PX

    /** How far the content can be scrolled before its end reaches the viewport's right edge. */
    val maxScrollPx: Float get() = (totalWidthPx - viewportWidthPx).coerceAtLeast(0f)

    /** The scroll position, held inside what the content can actually scroll to. */
    fun scrollClampedTo(px: Float): Float = px.coerceIn(0f, maxScrollPx)

    /** Left edge of the visible window in content pixels. */
    val visibleStartPx: Float get() = scrollClampedTo(scrollPx)

    /** Right edge of the visible window in content pixels. */
    val visibleEndPx: Float get() = visibleStartPx + viewportWidthPx

    /** The half-width of a touch zone around a boundary: the spec's 48 dp target, total. */
    val edgeTouchTargetPx: Float get() = EDGE_TOUCH_TARGET_DP * density

    /** Microseconds to content pixels. The one conversion everything else is built on. */
    fun pxFor(us: Long): Float = us / MICROS_PER_SECOND * zoom.pixelsPerSecond

    /** Content pixels back to microseconds, for a drag that has to produce a time. */
    fun usFor(px: Float): Long = (px / zoom.pixelsPerSecond * MICROS_PER_SECOND).toLong()

    /** A screen x (relative to the viewport) as a content pixel. */
    fun contentPxFor(screenX: Float): Float = visibleStartPx + screenX

    /** Where the playhead is drawn, or null when it is scrolled out of view. */
    fun playheadPx(playheadUs: Long): Float? {
        val px = pxFor(playheadUs)
        return if (px in visibleStartPx..visibleEndPx) px - visibleStartPx else null
    }

    /** Every clip's rectangle, in content pixels, in document order. */
    fun clipRects(): List<ClipRect> = spans.map { span ->
        val start = pxFor(span.startUs)
        // A clip is never drawn narrower than the minimum, and never wider than time says.
        val width = pxFor(span.durationUs).coerceAtLeast(ClipRect.MIN_DRAWN_WIDTH_PX)
        ClipRect(span.clipId, start, start + width)
    }

    /**
     * The rects a draw pass actually needs, with one viewport of margin on each side.
     *
     * The margin is what keeps a scroll smooth: a clip just off-screen is already drawn on the
     * frame it enters, instead of appearing one frame late (NFR-8's "0 frames > 32 ms" is about
     * scroll, and a clip that pops in is a frame the user notices).
     */
    fun visibleRects(): List<ClipRect> {
        val from = visibleStartPx - viewportWidthPx
        val to = visibleEndPx + viewportWidthPx
        return clipRects().filter { it.endPx >= from && it.startPx <= to }
    }

    /**
     * What a touch at [screenX] hits (FR-2.1).
     *
     * Three rules, in this order:
     *
     * 1. **A boundary wins over a body.** The zone AROUND a clip — to its left and right, where a
     *    touch is physically inside its neighbour — is checked first. This is what makes the spec's
     *    fixed 48 dp target real: the user cannot aim at a one-pixel boundary, so the pixels around
     *    it have to mean "this edge". Which of the two clips the boundary belongs to is decided by
     *    which side the touch landed on: left of the boundary is the right clip's IN-point (trim
     *    its start), right of it is the left clip's OUT-point (trim its end).
     * 2. **That reach is capped by the neighbour it reaches into.** How far a clip's edge zone may
     *    extend into the adjacent clip is `min(target, neighbour half-width)`, so a short clip keeps
     *    a usable body instead of being swallowed by its neighbours' touch targets. Where there is
     *    no neighbour — the open end of the timeline — the full target applies, because there is
     *    nothing to steal.
     * 3. **Inside a clip, the edge zones are capped at its own half-width.** The honest consequence
     *    at an extreme zoom-out: a 100 ms clip is 3 px wide with a 48 px target, so its centre is
     *    inside an edge zone. That is the arithmetic of a fixed target on a 3 px object, not
     *    something to paper over — the user zooms in to work on a clip that short.
     */
    fun hitTest(screenX: Float): TimelineHit {
        val x = contentPxFor(screenX)
        val rects = clipRects()
        val target = edgeTouchTargetPx

        rects.forEachIndexed { index, rect ->
            val leftReach = minOf(target, rects.getOrNull(index - 1)?.halfWidth() ?: target)
            if (x >= rect.startPx - leftReach && x < rect.startPx) {
                return TimelineHit.Edge(rect.clipId, EdgeSide.LEFT, withinClip = false)
            }
            val rightReach = minOf(target, rects.getOrNull(index + 1)?.halfWidth() ?: target)
            if (x > rect.endPx && x <= rect.endPx + rightReach) {
                return TimelineHit.Edge(rect.clipId, EdgeSide.RIGHT, withinClip = false)
            }
        }

        rects.forEach { rect ->
            val zone = minOf(target, rect.halfWidth())
            if (x in rect.startPx..(rect.startPx + zone)) {
                return TimelineHit.Edge(rect.clipId, EdgeSide.LEFT, withinClip = true)
            }
            if (x in (rect.endPx - zone)..rect.endPx) {
                return TimelineHit.Edge(rect.clipId, EdgeSide.RIGHT, withinClip = true)
            }
        }

        return rects.firstOrNull { it.contains(x) }
            ?.let { TimelineHit.Body(it.clipId) }
            ?: TimelineHit.None
    }

    private fun ClipRect.halfWidth(): Float = widthPx / 2f

    /** The clip the playhead is inside, which is what FR-2.2/FR-2.3/FR-2.5 act on. */
    fun clipAt(playheadUs: Long): ClipSpan? =
        spans.firstOrNull { playheadUs >= it.startUs && playheadUs < it.endUs }

    private fun List<ClipSpan>.foldWidth(): Float = lastOrNull()?.let { pxFor(it.endUs) } ?: 0f

    companion object {
        /** Microseconds in a second — the constant every conversion above divides by. */
        const val MICROS_PER_SECOND = 1_000_000f

        /** The spec's touch target around a clip edge (§7.1), in dp. */
        const val EDGE_TOUCH_TARGET_DP = 48f

        /** Room past the last clip, so the end of the timeline is not flush with the frame. */
        const val END_PADDING_PX = 24f
    }
}

/**
 * The least a clip can tell the geometry: what it is called, and how long it occupies the timeline.
 *
 * This type exists so that `:core:common` never imports `:domain:document`. Dependencies in this
 * repo point one way — the domain depends on core modules, never the reverse — and a geometry
 * helper is not a reason to reverse them. The caller that has both (the editor feature) maps its
 * clips across in one line, and the mapping is a CI-checked test rather than a convention.
 */
data class ClipTiming(val clipId: String, val timelineDurationUs: Long)

/**
 * Clip timings as positioned spans, laid end to end.
 *
 * Start times are PREFIX-SUMMED from the durations rather than passed in: spec §5.1 makes
 * timeline position *derived*, never stored, and this is where the derivation happens on the way
 * to the screen. A stored position would be a second source of truth — and the first thing to go
 * stale after a ripple edit, which is exactly the bug the spec's rule exists to prevent.
 */
fun spansOf(timings: List<ClipTiming>): List<ClipSpan> {
    var cursorUs = 0L
    return timings.map { timing ->
        val span = ClipSpan(timing.clipId, cursorUs, timing.timelineDurationUs)
        cursorUs += timing.timelineDurationUs
        span
    }
}
