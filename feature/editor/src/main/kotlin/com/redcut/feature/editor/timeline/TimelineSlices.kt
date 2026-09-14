package com.redcut.feature.editor.timeline

import com.redcut.core.media.ThumbnailKey
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the thumbnails go: which frames a clip shows, and at which pixels.
 *
 * Split from [TimelineThumbnails] (which owns the loading) because this half is arithmetic, and
 * arithmetic is what can be checked in CI without a device, a `Bitmap` or a decoder. The same
 * reason the timeline's geometry lives in `:core:common`: the rules get tests, the I/O gets a thin
 * wrapper.
 *
 * ### The slice grid belongs to the CLIP (device pass)
 *
 * The user: *"saat split, height gambar clip mengecil seperti zoom out"*. The cause was that the
 * number of slices came from the clip's own WIDTH and was capped: above 1920 px the cap bound, so
 * every thumbnail was drawn `clipWidth / 12` wide, and a split — which halves the width — halved
 * the drawn frame with it. A split is a document edit, and it must not change the scale footage is
 * drawn at. So the grid is now a CONSTANT pitch of [SLICE_WIDTH_PX] anchored at the clip's own left
 * edge: cut a clip in two and every frame stays on the pixel it was on, at the size it was.
 *
 * What bounds the work is the WINDOW rather than a per-clip cap. A pass is handed the pixels it is
 * drawing (the [ClipSliceInput] window, which is the visible window plus the one viewport of margin
 * `TimelineGeometry.visibleRects` already culls clips by) and asks only for the slices that
 * intersect it. Zooming into a ten-minute clip therefore costs a screenful of decodes rather than
 * the clip's whole length — which is what the old cap was standing in for, and why it is no longer
 * the policy: all that is left of it is [MAX_SLICES_PER_CLIP], a net under one clip's contribution
 * (NFR-8).
 */
internal object TimelineSlices {

    /** Spec §9.3: thumbnails are decoded at ≤ 160 px wide, so one covers 160 px of clip. */
    const val SLICE_WIDTH_PX = 160f

    /**
     * The most slices ONE CLIP may contribute. A safety net, not the policy the filmstrip obeys.
     *
     * The policy is the window: one viewport of margin on each side means a pass covers three
     * viewports of pixels, which at 160 px a slice is about twenty slices on a phone however long
     * the clip is. This constant exists only so that a caller which passes no window at all — or a
     * document whose widths are nonsense — cannot turn one clip into an unbounded list of decodes.
     * It is set well above what the window rule can reach, so a clip that hits it is a caller to
     * fix rather than a strip behaving as designed.
     *
     * It bounds ONE CLIP and not the whole pass, and that is not a detail of where the `take` sits:
     * a pass-wide net is spent in document order, so with four lanes on screen the first three
     * would consume it and the fourth would come back with no thumbnails at all — a strip that
     * reads as a broken loader rather than as a strip at its limit. Per clip, no clip can starve
     * another, whatever the document holds. What is over the net is DROPPED rather than stretched:
     * a thumbnail that is missing is honest, and one rescaled to fit is exactly the bug above.
     */
    const val MAX_SLICES_PER_CLIP = 64

    /**
     * How many slices cover [widthPx] of clip: one per [SLICE_WIDTH_PX], rounded UP, never zero.
     *
     * This count is the denominator the SOURCE sampling is divided by ([sliceOffsetUs]), and
     * rounding up is what makes it an honest one: a 161 px clip is two slices, the second of them a
     * 1 px sliver, so the whole clip is sampled instead of all but its last pixel. It is NOT a
     * drawing count any more — how many slices are DRAWN is the window's answer — and what it does
     * not depend on is the VIEWPORT: neither where the window is nor how wide it is enters this
     * arithmetic, so a scroll cannot change which frame a slice shows. It does follow the ZOOM,
     * because [widthPx] is the clip's DRAWN width and the zoom is what scales it — a pinch samples
     * the same footage more finely, which is the point of pinching. Zero would divide by nothing
     * and draw an empty rectangle, which at minimum zoom is every short clip.
     */
    fun totalSlices(widthPx: Float): Int = ceil(widthPx / SLICE_WIDTH_PX).toInt().coerceAtLeast(1)

    /**
     * The offset within a clip, in microseconds, that slice [index] of [count] samples.
     *
     * Samples the MIDDLE of each slice (`index * 2 + 1` over `count * 2`) rather than its start:
     * a frame taken at a slice boundary shows the frame the NEXT slice begins with, so the strip
     * reads as one frame out of step — the classic off-by-half that makes a filmstrip feel wrong
     * without anyone being able to say why.
     */
    fun sliceOffsetUs(durationUs: Long, index: Int, count: Int): Long =
        (durationUs * (index * 2 + 1)) / (count * 2)

    /**
     * Every slice the visible clips need, with the source time to decode and where to draw it.
     *
     * The distinction that matters, and that an earlier draft of this file got wrong: [SliceRequest
     * .positionUs] is a time in the SOURCE FILE, because that is what a decoder takes, while
     * [SliceRequest.leftPx] is a TIMELINE position. For a trimmed clip — the
     * normal case, since trimming is the point of the app — those two are different numbers, and
     * asking the decoder for a timeline time returns the wrong frame or nothing at all. The
     * crossing is [ClipSliceInput.sourceTimeAt], which is the clip's own mapping, so the speed and
     * reverse handling stays in the domain where it is already tested.
     *
     * The window is the CLIP's own ([ClipSliceInput.windowStartPx], [ClipSliceInput.windowEndPx])
     * and is never read from a viewport here: a pass deciding for itself what is on screen would be
     * a second answer to a question the geometry has already answered, and the two would drift.
     */
    fun requests(clips: List<ClipSliceInput>): List<SliceRequest> = clips.flatMap { clip ->
        val total = totalSlices(clip.widthPx)
        val indices = sliceIndices(clip, total).take(MAX_SLICES_PER_CLIP)
        indices.map { index -> sliceRequest(clip, index, total) }
    }

    /**
     * The indices of a clip's slices that this clip's window can see, ascending; empty if none can.
     *
     * The bounds are the window's, converted into the CLIP's grid and clamped to it: the first
     * slice is the one covering the window's left edge, the last the one covering its right edge.
     * Both are floored against `clip.leftPx` rather than against the window, and that is the whole
     * reason the grid is stable: a pitch anchored to the viewport would slide every thumbnail
     * sideways by the remainder of a slice on each scroll, so the strip would crawl as the user
     * scrolled it.
     *
     * Empty means the clip is off the window entirely — the caller's rect list carries clips the
     * window's margin only just reaches, and asking for nothing is the right answer past its end.
     * The last index is clamped to the clip's own last slice, so a window that runs past the clip
     * cannot invent slices for footage that is not there.
     */
    private fun sliceIndices(clip: ClipSliceInput, total: Int): IntRange {
        val first = floor((clip.windowStartPx - clip.leftPx) / SLICE_WIDTH_PX).toInt()
        val last = floor((clip.windowEndPx - clip.leftPx) / SLICE_WIDTH_PX).toInt()
        return first.coerceAtLeast(0)..last.coerceAtMost(total - 1)
    }

    /**
     * One slice: the frame to decode, and the pixel rectangle to draw it in.
     *
     * [index] is the CLIP's index, not the window's, and that is what keeps [SliceRequest
     * .positionUs] — and therefore [SliceRequest.key] — the same before and after a split: slice
     * *n* of a clip is the same instant whether the clip is whole or is the left half of what it
     * was, so an edit that changed no pixels re-decodes no thumbnails.
     */
    private fun sliceRequest(clip: ClipSliceInput, index: Int, total: Int): SliceRequest {
        val positionUs = clip.sourceTimeAt(sliceOffsetUs(clip.durationUs, index, total))
        return SliceRequest(
            clipId = clip.clipId,
            key = ThumbnailKey(clip.sourceId, positionUs),
            sourceId = clip.sourceId,
            uri = clip.uri,
            positionUs = positionUs,
            leftPx = sliceLeftPx(clip, index),
            widthPx = sliceWidthPx(clip, index),
        )
    }

    /**
     * Where slice [index] starts: the CLIP's left edge plus whole pitches, never the window's.
     *
     * Index 0 therefore begins exactly where the clip begins at every zoom, which is what makes the
     * filmstrip start on the clip's in-point rather than on whatever the scroll happens to be.
     */
    private fun sliceLeftPx(clip: ClipSliceInput, index: Int): Float =
        clip.leftPx + index * SLICE_WIDTH_PX

    /**
     * How wide slice [index] is drawn: the pitch, shortened so that it stops at the clip's own end.
     *
     * Only the LAST slice of a clip is ever short, and it has to be, because [totalSlices] rounds
     * up: a 500 px clip is three 160 px slices and one of 20. The bound is the clip's END and not
     * the window's — `drawClip` clips to the clip's rectangle, so a slice running past it would
     * paint a neighbour's pixels under this clip's id: a request set that lies about what it is
     * asking for, even where the drawing hides the lie.
     */
    private fun sliceWidthPx(clip: ClipSliceInput, index: Int): Float =
        minOf(SLICE_WIDTH_PX, clip.leftPx + clip.widthPx - sliceLeftPx(clip, index))
}

/**
 * One visible clip, as the slice arithmetic needs it.
 *
 * [sourceTimeAt] maps an offset inside this clip to an absolute source time. It is a lambda rather
 * than a computation here because the clip's own model (`Clip.sourceTimeAt`) already knows about
 * speed and reverse, and a second implementation of that mapping in the UI would be a second answer
 * to the same question.
 *
 * ### The window, and why it is added rather than required
 *
 * [windowStartPx] and [windowEndPx] are the content pixels a draw pass is asking about, in the same
 * coordinate space as [leftPx]: the visible window plus one viewport of margin on each side, which
 * is what the caller's geometry already holds. Only the slices intersecting them are requested (see
 * [TimelineSlices.requests]).
 *
 * They are the LAST parameters, and their defaults are the clip's own rectangle, which means "the
 * whole clip": every constructor call written before the window existed still compiles and still
 * means exactly what it meant. That is the additive rule this repo lands capabilities by — the new
 * reading beside the old one — and it is why a caller with no viewport is not asked to invent one.
 */
internal data class ClipSliceInput(
    val clipId: String,
    val sourceId: String,
    val uri: String,
    val leftPx: Float,
    val widthPx: Float,
    val durationUs: Long,
    val sourceTimeAt: (offsetUs: Long) -> Long,
    /** Left edge of the window this pass draws, in content pixels. Defaults to the clip's own left. */
    val windowStartPx: Float = leftPx,
    /** Right edge of that window. Defaults to the clip's own right, so the default is all of it. */
    val windowEndPx: Float = leftPx + widthPx,
)

/** One thumbnail the canvas should draw, and where. */
internal data class SliceRequest(
    /** The clip this slice belongs to: two clips of one source share slices, and share a key. */
    val clipId: String,
    /** What the thumbnail cache is keyed by: the source, and a time inside it. */
    val key: ThumbnailKey,
    val sourceId: String,
    val uri: String,
    /** The time to decode, in the SOURCE file (not a timeline position — see [TimelineSlices.requests]). */
    val positionUs: Long,
    /** Where it is drawn, in content pixels (the timeline's own coordinate space). */
    val leftPx: Float,
    val widthPx: Float,
)
