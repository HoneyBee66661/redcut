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
        /**
         * 0.1 px/s: an HOUR is 360 px, so an hour-long project fits one phone screen.
         *
         * The user's word for this end was *"shrink max per 1 hr, incase video is hours long"*, and it
         * replaced the old 2 px/s ("a five-minute project is wider than a phone"). The old bound was chosen
         * for a five-minute project; a user with footage measured in hours could not zoom out far enough to
         * see it, which is the one thing zooming out is for.
         */
        val MINIMUM = TimelineZoom(0.1f)

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

/**
 * One lane of a document: a track, and the clips it holds, in the order the document keeps them.
 *
 * A projection rather than a `Track`, for the reason [ClipSpan] is one: the geometry is asked about
 * lane spans, so it can be exercised with two hand-written lists instead of a document with sources,
 * effect stacks and history behind it — and it cannot start depending on a field the drawing code has
 * no business reading.
 *
 * [trackId] travels with the clips because which clips overlap in TIME is a per-track fact, and that
 * is the whole difference between this reading and the flat one: two lanes' clips start at 0 side by
 * side instead of one lane's being laid after the other's.
 */
data class LaneSpans(val trackId: String, val spans: List<ClipSpan>)

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

/**
 * The band one lane occupies on screen, in content pixels: its horizontal extent, and its vertical one.
 *
 * [trackId] is the document's track when the caller had a document, and NULL for the flat reading — the
 * single lane a caller without one draws. Null therefore does not mean "unknown"; it means the one lane,
 * and there is exactly one of them, which is why the flat case needs no id to tell lanes apart. A lane is
 * still not a document entity — nothing selects it and no command edits it — but which track is under a
 * touch is a question the hit test has to answer, and the id is what answers it.
 *
 * Horizontally this is the VISIBLE WINDOW rather than the content: see `TimelineGeometry.laneRects` for why
 * the empty part of the timeline has to read as track. Vertically it is the band [topPx] to [bottomPx],
 * which is one track tall and starts where the lane above it ended.
 *
 * The defaults describe a lane one track tall at density 1, which is what the flat reading IS, so a
 * hand-written lane can be written the way it reads (`LaneRect(0f, 360f)`). The geometry never relies on
 * them: every lane it derives from `TimelineGeometry.lanes` carries its own density-scaled band.
 */
data class LaneRect(
    val startPx: Float,
    val endPx: Float,
    val trackId: String? = null,
    val topPx: Float = 0f,
    val bottomPx: Float = TimelineGeometry.TRACK_HEIGHT_DP,
) {
    val widthPx: Float get() = endPx - startPx
}

/**
 * What a draw pass draws in one lane: the band, and the clips in it that are on screen.
 *
 * The two are one value because they are one question. A [ClipRect] says nothing about where it is
 * drawn vertically, so a draw pass holding only rects would have to ask again which lane it was
 * iterating — and could answer that differently from the pass that built them.
 */
data class LaneClips(val lane: LaneRect, val rects: List<ClipRect>)

/** What is under a touch, which is the whole reason the geometry exists. */
sealed interface TimelineHit {

    /**
     * Nothing at all: a point in no lane's band — above the first track, below the last — or, in the
     * flat reading, the empty area past the end of the last clip or a gap between them.
     *
     * The second half is not an oversight: the flat reading draws ONE lane and has no track to name, so
     * it answers [None] where a lane reading answers [Track]. Same point, same rules — the difference is
     * whether there is a track id to put in the answer.
     */
    data object None : TimelineHit

    /**
     * The clip's body: a TAP seeks the playhead to that point and selects the clip, while a DRAG
     * scrolls the timeline (and a long press picks the clip up to reorder, FR-2.7).
     *
     * The body is the whole of a clip that is not within reach of an edge, and the two must not be
     * confused: see `edgeReach` for the cap that guarantees a body exists at every zoom, which is what
     * makes "a drag on a clip scrolls" true rather than almost true.
     */
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

    /**
     * The BACKGROUND of a lane: a point inside a track's band that is on none of that track's clips.
     *
     * This is a place the user can act on, which is why it is an answer rather than a failure to find
     * one. Most of an empty track, and everything past a track's last clip, is this — and while it was
     * [None] a caller could not tell "empty video track at 3 s" from "below the last track", so a tap on
     * a track with nothing under the finger was a dead tap. What the difference buys is the gesture: a
     * tap here selects the track, or adds a clip to it.
     *
     * It is NOT a clip and NOT a clip's edge. The rules above it still win — an edge zone, then a body,
     * then this — because those are what the finger actually landed on; this is reached only where the
     * lane holds no clip under the point at all. And it is answered by the LANE reading only: the flat
     * [hitTest] has no tracks, so it keeps answering [None] rather than inventing an id, or a sentinel
     * string, for the single lane a caller without a document draws.
     *
     * [trackId] is the track whose band the touch landed in, and it is the whole of the answer: a lane's
     * background is one place at every x, the same way `laneRects` draws the empty track across the
     * whole visible window.
     */
    data class Track(val trackId: String) : TimelineHit
}

enum class EdgeSide { LEFT, RIGHT }

/**
 * The visible window of a timeline, expressed in pixels.
 *
 * [viewportWidthPx] and [scrollPx] come from the Canvas; [zoom] and [density] from the user and
 * the device. Everything else is derived, and nothing is stored twice: [totalWidthPx] is a
 * function of the clips the geometry was given and the zoom, and [maxScrollPx] of the total and
 * the viewport.
 */
data class TimelineGeometry(
    val viewportWidthPx: Float,
    /**
     * The clips this geometry is about, in document order — the ONE-LANE reading.
     *
     * This is what a caller without track information passes, and it is what the editor passes today:
     * every clip in it occupies the single lane [laneRects] returns. A caller that HAS the document's
     * tracks passes [lanes] instead — one or the other, never both — and this is not deprecated by
     * that: a timeline with one track is a real thing to draw, and this is its reading.
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
    /**
     * The document's tracks as lanes (schema v3), for a caller that HAS a document.
     *
     * ### Which reading a caller passes
     *
     * [spans] is the one-lane reading — what a caller with no track information has, and what the editor
     * passes today. This is the reading a caller with a document has. A caller passes ONE of them, never
     * both: a caller that has the tracks passes [lanes] and stops passing [spans].
     *
     * Where both are set, `lanes` is what the lane reading uses, and [spans] is left to the one function
     * defined on it, the flat [hitTest] — which is a contract violation rather than a supported state,
     * and is why no member below reads both to decide anything.
     *
     * It is the LAST parameter so that the positional call a caller already makes —
     * `TimelineGeometry(width, spans, zoom, scroll, density)` — keeps compiling and keeps meaning what it
     * meant. Adding a parameter is not a change to the reading that was there before it.
     */
    val lanes: List<LaneSpans> = emptyList(),
) {

    /**
     * This geometry's lanes as (track id, clips), with the flat reading as one lane that has no id.
     *
     * The ONE place that decides how many lanes there are, so that [laneRects], [visibleRectsByLane], the
     * lane [hitTest] and the content end cannot disagree about it. A draw pass iterating rects built from
     * one rule while the touch path resolves bands from another is a bug that only appears on the first
     * project with two tracks — which is the project this change is for.
     */
    private val laneReadings: List<Pair<String?, List<ClipSpan>>>
        get() = if (lanes.isEmpty()) listOf(null to spans) else lanes.map { it.trackId to it.spans }

    /**
     * Where the content ends, in microseconds: the furthest of the lanes' last clips, or null when no
     * lane holds a clip at all.
     *
     * With lanes the timeline is as long as its LONGEST one, because the lanes run in PARALLEL — a second
     * track's clips are laid BESIDE the first's, not after them. Summing the lanes would be the flat
     * reading's arithmetic, and it is exactly the arithmetic this reading exists to replace. The flat case
     * is the same expression over one lane, so a caller that passes [spans] gets the length it always got.
     */
    private val contentEndUs: Long?
        get() = laneReadings.mapNotNull { it.second.lastOrNull()?.endUs }.maxOrNull()

    /** Total width of the timeline content, in pixels: the longest lane's end, plus the end padding. */
    val totalWidthPx: Float
        get() = (contentEndUs?.let { pxFor(it) } ?: 0f) + END_PADDING_PX

    /** How far the content can be scrolled before its end reaches the viewport's right edge. */
    val maxScrollPx: Float get() = (totalWidthPx - viewportWidthPx).coerceAtLeast(0f)

    /**
     * The scroll offset that puts [us] under the horizontal centre of the viewport (UI revision 1).
     *
     * ### What changed, and why this is a function rather than a setter
     *
     * The editor used to draw the playhead wherever its time fell — `pxFor(playhead) - visibleStartPx` —
     * and the user scrolled the viewport independently of it. The revision inverts that: the playhead is
     * the FIXED line at the centre, and the scroll offset is what encodes it, so scrolling and seeking
     * stop being two things. The scroll is therefore DERIVED from the playhead rather than stored, which is
     * why this returns a value instead of mutating one.
     *
     * ### Not clamped, and the bug that taught us (device pass, after the layout)
     *
     * The first version clamped this to the content's scroll range, on the reasoning that a viewport cannot
     * scroll past its content. On a device that put the line at the LEFT EDGE of the screen for a new
     * project: a timeline shorter than the viewport has no scroll range at all, so the clamp pinned the
     * viewport to 0 and the playhead — centred *within the content* — rendered at its own pixel position,
     * which for a playhead at 0 is the left edge. The user's report was exactly that: "apakah redline
     * playhead secara default ada di left mentok layar? harusnya center horizontal fixed".
     *
     * So there is no clamp, and that is the model rather than a relaxation: a fixed-centre playhead REQUIRES
     * empty space at both ends, because with an empty project or the first frame under the line, half the
     * viewport has nothing to show. Negative scroll means empty space before the first frame; scrolling past
     * the content's end means empty space after the last one. [maxScrollPx] stays as a fact about content
     * versus viewport, but nothing needs it to position the viewport any more.
     */
    fun scrollCentering(us: Long): Float = pxFor(us) - viewportWidthPx / 2f

    /**
     * The playhead's screen x: the viewport's centre, always.
     *
     * Which is a property of the model, not a coincidence of the arithmetic — [scrollCentering] is defined
     * as the offset that puts [us] there, so this is the identity that proves it. It is kept as a function
     * because the Canvas draws the line here and a reader should be able to see the claim rather than
     * derive it.
     */
    fun centredPlayheadPx(us: Long): Float = pxFor(us) - scrollCentering(us)

    /**
     * Left edge of the visible window in content pixels.
     *
     * NOT clamped: see [scrollCentering] for why a negative value is meaningful, and why the scroll is no
     * longer something a gesture can push out of range.
     */
    val visibleStartPx: Float get() = scrollPx

    /** Right edge of the visible window in content pixels. */
    val visibleEndPx: Float get() = visibleStartPx + viewportWidthPx

    /**
     * The window a draw pass actually works with: the visible one, plus one viewport of margin.
     *
     * Named rather than left inline in [visibleRects], because a second reader needed the same
     * number: the filmstrip asks its own arithmetic (`TimelineSlices.requests`) for the slices
     * inside this window, and a margin re-derived at that call site would be a second answer to
     * "what is being drawn" — which drifts the moment either end changes, and drifts silently,
     * because a strip that decodes one viewport too few looks exactly like a strip that is merely
     * slow.
     */
    val cullStartPx: Float get() = visibleStartPx - viewportWidthPx

    /** The window's right edge; see [cullStartPx] for why it is named. */
    val cullEndPx: Float get() = visibleEndPx + viewportWidthPx

    /** The half-width of a touch zone around a boundary: the spec's 48 dp target, total. */
    val edgeTouchTargetPx: Float get() = EDGE_TOUCH_TARGET_DP * density

    /**
     * A track row's height, in pixels (UI revision 2).
     *
     * FIXED, and deliberately not a share of the canvas: the user's words were *"tinggi track body timeline
     * fixed, tidak fitting container"*. The reason survives the wording — a track that stretched with the
     * canvas would show a TALLER clip rather than more track, which is the opposite of what a taller screen
     * is for, and once there are several tracks (video, audio, overlay) a height that depends on the total
     * makes every lane's proportion depend on how many lanes exist.
     *
     * It lives HERE, beside [edgeTouchTargetPx], rather than in the drawing layer: the constant is a fact
     * about the timeline's geometry, so the fast tier can test it, and the draw pass reads it. Same rule as
     * the ranges in `ClipRanges` — one number, one place, both readers.
     */
    val trackHeightPx: Float get() = TRACK_HEIGHT_DP * density

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
     *
     * ### The culling contract (UI revision 2, task A5)
     *
     * A clip whose whole span lies outside that window, margin included, is NOT returned. The user's words
     * for why: *"clip body off screen not rendered for optimization"* — and the same revision's zoom range is
     * what makes it matter, because at 0.1 px/s an HOUR is one screen, so a project can hold hundreds of
     * clips of which a handful are on it. A clip that overlaps the margin is returned exactly ONCE, however
     * much of it is off screen: the rect is the clip's own, in content pixels, and nothing about it depends
     * on where the viewport happens to be — the draw pass is what clips it to the window.
     */
    fun visibleRects(): List<ClipRect> =
        clipRects().filter { it.endPx >= cullStartPx && it.startPx <= cullEndPx }

    /**
     * The lane bands to draw, in content pixels (UI revision 2, task A5).
     *
     * The lane SPANS THE VISIBLE WINDOW rather than being derived from the clips, and that is the decision
     * this function exists to write down. It follows from the fixed track height — *"tinggi track body
     * timeline fixed, tidak fitting container"* — because a canvas taller than a track leaves space that has
     * to read as empty TRACK: the user's contrast is between more track and a void. With the playhead centred
     * the same is true horizontally, where half a viewport of the body is empty at each end of the timeline.
     * A lane clamped to the content would instead leave the area past the last clip looking like a hole in
     * the timeline, and that edge is one the user drags a clip towards.
     *
     * One rect per lane, in the document's order. An EMPTY lane gets one too, and that is the point rather
     * than an oversight: a track waiting for its first clip is drawn as empty track, which is the same
     * reason the band spans the window instead of the content.
     *
     * ### Vertically: lanes stack from the top, and there is nothing to scroll
     *
     * Each lane is [trackHeightPx] tall and starts where the one above it ended — [laneTopPx] is the whole
     * of that layout — so the canvas is a window onto the TOP of the stack. The lanes start at the top of
     * the canvas and a lane past its bottom is simply not drawn: this geometry maps x and knows no canvas
     * height, and a vertical scroll model with no gesture behind it would be a number nobody can change.
     * The height lives in the draw pass, which is where the clipping belongs.
     *
     * The flat reading ([lanes] empty) is ONE lane with a null [LaneRect.trackId], and that is what the
     * editor draws today.
     */
    fun laneRects(): List<LaneRect> = laneReadings.mapIndexed { index, lane ->
        val top = laneTopPx(index)
        LaneRect(visibleStartPx, visibleEndPx, lane.first, top, top + trackHeightPx)
    }

    /**
     * The top of the lane at [index], in pixels from the top edge of the canvas.
     *
     * This is the whole of the vertical layout: lanes are stacked in the document's order, one track
     * height apart, and the first starts at the canvas's own top. It is a function rather than a field
     * because a lane's position is DERIVED from how many lanes are above it, the same way a clip's start
     * is a prefix sum rather than a stored time — a stored `y` is the second source of truth that goes
     * stale the moment a track is added above it.
     */
    fun laneTopPx(index: Int): Float = index * trackHeightPx

    /**
     * The lanes to draw and the clips each holds, culled exactly as [visibleRects] culls (UI revision 2).
     *
     * This is what a draw pass iterates: one entry per lane, in document order, each carrying its band and
     * the rects to draw inside it. The culling is [visibleRects]'s own rule — one viewport of margin on each
     * side, applied per lane instead of re-derived — because a second culling rule is a second answer to
     * "what is on screen", and it would drift from the first the moment either changed. A lane whose clips
     * are a scroll away culls to an EMPTY list and is still returned: the empty track is the thing the lane
     * exists to draw.
     *
     * One entry in the flat case, which is what makes this safe to call from a Canvas that has no document
     * yet: it is [visibleRects] again, with the lane those rects belong to named.
     */
    fun visibleRectsByLane(): List<LaneClips> {
        val bands = laneRects()
        return laneReadings.mapIndexed { index, lane ->
            LaneClips(bands[index], copy(spans = lane.second).visibleRects())
        }
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
            val leftReach = minOf(target, rects.getOrNull(index - 1)?.edgeReach() ?: target)
            if (x >= rect.startPx - leftReach && x < rect.startPx) {
                return TimelineHit.Edge(rect.clipId, EdgeSide.LEFT, withinClip = false)
            }
            val rightReach = minOf(target, rects.getOrNull(index + 1)?.edgeReach() ?: target)
            if (x > rect.endPx && x <= rect.endPx + rightReach) {
                return TimelineHit.Edge(rect.clipId, EdgeSide.RIGHT, withinClip = false)
            }
        }

        rects.forEach { rect ->
            val zone = rect.edgeReach()
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

    /**
     * What a touch at ([screenX], [screenY]) hits, when the geometry was given [lanes] (FR-2.1).
     *
     * The lane is resolved from the y FIRST — the band that contains it, top edge inclusive and bottom edge
     * exclusive — and only then do the three rules above apply, to THAT lane's clips. The rules themselves
     * are written once, in [hitTest]: a lane's touch is the flat reading restricted to the lane's clips, so
     * the lane is asked the question in the shape the answer already has rather than answered twice.
     *
     * Two consequences fall out of the ORDER, rather than out of a second rule:
     *
     * - **A y in no lane's band is [TimelineHit.None]**, however many clips that x crosses. The lanes start
     *   at the top of the canvas and the area below the last track is not a track.
     * - **A lane boundary is not a clip edge.** An edge target is 48 dp wide and would otherwise reach
     *   across the boundary into the lane below, where the user is pointing at a different track entirely:
     *   a clip's out-point would be grabbed by a finger that is one lane down from it. Resolving y first is
     *   what stops that, and it is the reason this overload exists at all.
     * - **A lane's background is [TimelineHit.Track].** Where the three rules find no clip of the lane the
     *   touch landed in, the answer is the lane itself, not [TimelineHit.None]: it is still a track, and
     *   which one is the thing the UI acts on. Only the flat reading keeps answering [TimelineHit.None]
     *   there, because the one lane it draws has no id — and so does a y outside every band, since the
     *   background is INSIDE a lane and past the last track there is no lane at all.
     *
     * The flat reading is the same arithmetic with one band: a y inside the first track height is the whole
     * timeline, and a y outside it is nothing.
     */
    fun hitTest(screenX: Float, screenY: Float): TimelineHit {
        val bands = laneRects()
        val index = bands.indexOfFirst { screenY >= it.topPx && screenY < it.bottomPx }
        val lane = laneReadings.getOrNull(index) ?: return TimelineHit.None
        val hit = copy(spans = lane.second).hitTest(screenX)
        if (hit != TimelineHit.None) return hit
        // No clip under the touch, so the lane's own background is what is there — when it has an id.
        return lane.first?.let { TimelineHit.Track(it) } ?: TimelineHit.None
    }

    /**
     * How far a clip's edge target reaches, which is the touch target BOUNDED BY THE CLIP ITSELF.
     *
     * The bound is a fraction of the clip rather than half of it, and the difference is a bug the device
     * pass found: at 48 dp a target is about 2.5 mm, and a clip drawn narrower than twice that — a short
     * clip, or any clip at a low zoom, which is exactly what a freshly imported clip is — had its left
     * target and its right target meet in the middle. Every drag inside it was then an edge drag, so the
     * clip TRIMMED when the user meant to move the playhead, and shrank under their finger.
     *
     * A third per side leaves a third that can only ever be the body, at every zoom. Narrow clips are
     * still trimmable (a 3 px sliver gets a 1 px target at each end); what they no longer are is
     * untouchable in the middle.
     */
    private fun ClipRect.edgeReach(): Float =
        minOf(edgeTouchTargetPx, widthPx * EDGE_TARGET_MAX_CLIP_FRACTION)

    /**
     * Where to draw ruler ticks, in microseconds, inside the visible window.
     *
     * Bounded by the CONTENT, not by the viewport: a tick past the end of the last clip is a time
     * that does not exist in this project, and drawing it invites the user to scrub to nowhere. An
     * empty timeline therefore has an empty ruler.
     *
     * The interval comes from the zoom so labels never collide and never thin out into a solid
     * line: the smallest round interval (seconds, then minutes) whose on-screen width is at least
     * [MIN_TICK_SPACING_PX]. Pure arithmetic again — the ruler needs no font measurement and no
     * Context.
     *
     * With lanes the content ends at the LONGEST lane's last clip, which is what [contentEndUs] is: the
     * ruler measures the times the project has, and a lane that stops earlier does not shorten it.
     */
    fun rulerTicks(): List<Long> {
        val endUs = contentEndUs ?: return emptyList()
        val intervalUs = rulerIntervalUs()
        val firstTick = ceilToInterval(usFor(visibleStartPx), intervalUs)
        val limitPx = minOf(visibleEndPx, pxFor(endUs))

        val ticks = mutableListOf<Long>()
        var tick = firstTick
        while (pxFor(tick) <= limitPx) {
            ticks += tick
            tick += intervalUs
        }
        return ticks
    }

    /** The tick interval for the current zoom, in microseconds. Never zero. */
    fun rulerIntervalUs(): Long {
        val wantedUs = usFor(MIN_TICK_SPACING_PX).coerceAtLeast(1L)
        return TICK_INTERVALS_US.firstOrNull { it >= wantedUs } ?: TICK_INTERVALS_US.last()
    }

    /**
     * The ruler's ticks that land on one clip, in microseconds (UI revision 2, task A3).
     *
     * The user: *"beri ruler indikator waktu juga di top clip untuk memudahkan cut ops dan keyframing"* — the
     * time reference has to be readable where the cut is made, not only in the strip above the body. The
     * marks on a clip are the RULER's ticks, filtered to the clip's own span, rather than a ladder of their
     * own: a second answer to "how often is often enough" is a second ladder to keep in step, and the two
     * would drift apart the first time one of them changed. Reusing it is also what makes the two readings
     * comparable — zooming in crowds the marks on the clip in exactly the step it crowds the strip above it.
     *
     * A tick on the boundary two adjacent clips share is returned by BOTH, deliberately: that is one pixel
     * drawn twice, which nobody can see, instead of a rule about which neighbour owns a shared instant.
     */
    fun ticksForClipTop(ticks: List<Long>, rect: ClipRect): List<Long> =
        ticks.filter { pxFor(it) in rect.startPx..rect.endPx }

    /**
     * The first multiple of [intervalUs] at or after [us].
     *
     * Rounded UP, so the first tick is drawn just inside the left edge rather than one interval
     * off-screen: a tick that is never visible would cost a draw call per frame for nothing.
     */
    private fun ceilToInterval(us: Long, intervalUs: Long): Long {
        if (us <= 0L) return 0L
        return ((us + intervalUs - 1) / intervalUs) * intervalUs
    }

    /**
     * The clip the playhead is inside, which is what FR-2.2/FR-2.3/FR-2.5 act on.
     *
     * Defined on the flat [spans] reading, and left that way deliberately: with lanes, a playhead at a
     * time that two lanes both cover belongs to whichever lane the UI has decided is being worked on,
     * and which track that is has no answer in this class. A caller that passes [lanes] resolves the
     * lane first — the order the lane [hitTest] already uses — and asks this of that lane's clips. The
     * consequence to know about: called on a geometry given [lanes], this answers null, because the
     * flat reading it is defined on is empty.
     */
    fun clipAt(playheadUs: Long): ClipSpan? =
        spans.firstOrNull { playheadUs >= it.startUs && playheadUs < it.endUs }

    companion object {
        /** Microseconds in a second — the constant every conversion above divides by. */
        const val MICROS_PER_SECOND = 1_000_000f

        /** The spec's touch target around a clip edge (§7.1), in dp. */
        const val EDGE_TOUCH_TARGET_DP = 48f

        /**
         * A track row's height, in dp (UI revision 2).
         *
         * 56 dp holds a clip's thumbnail strip and the labels drawn on it, and it is the height a Material
         * list row would use for something the user taps. The user asked for it to be FIXED.
         */
        const val TRACK_HEIGHT_DP = 56f

        /**
         * The most of a clip's own width an edge target may claim, per side.
         *
         * See `edgeReach`: at a half per side the two targets met in the middle of any clip narrower
         * than twice the touch target, so the whole clip trimmed and nothing inside it could be dragged
         * to move the playhead. A third leaves a body at every zoom.
         */
        const val EDGE_TARGET_MAX_CLIP_FRACTION = 1f / 3f

        /** Room past the last clip, so the end of the timeline is not flush with the frame. */
        const val END_PADDING_PX = 24f

        /** Closest two ruler labels may be drawn before the interval is stepped up. */
        const val MIN_TICK_SPACING_PX = 64f

        /**
         * The intervals a ruler is allowed to use, smallest first.
         *
         * Round numbers only: a frame, 0.1 s, 0.5 s, 1 s, 5 s, 10 s, 30 s, then minutes. A ruler that
         * reads "3.7 s" is a ruler nobody can scan, and the intervals people trim by are round ones.
         *
         * The tight end was added by UI revision 2, from the user's ladder: *"paling coarse 1 detik, 0.5,
         * 0.1, per frame"* — so a frame, 0.1 s and 0.5 s are all reachable granularities, and the ruler
         * keeps its round-number shape below them.
         */
        val TICK_INTERVALS_US = listOf(
            FRAME_INTERVAL_US,
            100_000L,
            500_000L,
            1_000_000L,
            5_000_000L,
            10_000_000L,
            30_000_000L,
            60_000_000L,
            300_000_000L,
            600_000_000L,
        )

        /**
         * One frame at 60 fps, in microseconds.
         *
         * The finest granularity the ruler offers, and the number [TimelineZoom.MAXIMUM]'s comment already
         * reasons about: it is the step a frame-accurate cut lands on (FR-2.9, FR-2.1).
         */
        const val FRAME_INTERVAL_US = 16_667L
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
