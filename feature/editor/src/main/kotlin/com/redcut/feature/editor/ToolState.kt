package com.redcut.feature.editor

import com.redcut.core.common.timeline.EdgeSide
import com.redcut.domain.document.ClipEdge

/**
 * What the user is currently doing (spec §7.2's `tool`).
 *
 * In the one state object rather than in the Canvas's own `remember`, and the difference matters:
 * the timeline's zoom and scroll are how the user LOOKS at the document (view state, and no other
 * screen cares), while a trim in flight is an edit that is already applied to the document as a
 * preview. A screen that showed the document without knowing a gesture was open on it could not
 * explain why the clip is 300 ms shorter than a moment ago.
 */
sealed interface ToolState {

    /** No tool is active: taps select, drags scroll. */
    data object Idle : ToolState

    /**
     * A trim drag is in flight (FR-2.1).
     *
     * [sourceTimeUs] is where the edge currently is, in SOURCE time — the value the command needs,
     * not the timeline position the finger is at. Keeping it here is what lets the stage body show
     * the frame at the edge being dragged (FR-2.1's "live preview of the frame at the edge").
     */
    data class Trimming(
        val clipId: String,
        val edge: ClipEdge,
        val sourceTimeUs: Long,
    ) : ToolState
}

/**
 * The domain edge a timeline side means.
 *
 * The two enums are separate on purpose (see [ClipEdge]): one is about pixels on a screen, the other
 * about a clip's in- and out-points. This is the one place they are translated, so the day they
 * disagree — a reversed clip drawn with its in-point on the right — there is exactly one function to
 * change instead of a scattering of `if (side == LEFT)`.
 */
fun EdgeSide.toClipEdge(): ClipEdge = when (this) {
    EdgeSide.LEFT -> ClipEdge.IN
    EdgeSide.RIGHT -> ClipEdge.OUT
}
