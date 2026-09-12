package com.redcut.domain.document

/**
 * Which end of a clip an edit means (FR-2.1).
 *
 * A domain type rather than a UI one, and separate from the timeline's `EdgeSide` on purpose: the
 * timeline's enum says "the left or right side of a rectangle on screen", which is a fact about
 * pixels; this says "the in-point or the out-point of a clip", which is a fact about the document.
 * They map onto each other one-to-one today, and the day a reversed clip draws its in-point on the
 * right, the two will disagree — which is exactly when having conflated them would hurt.
 */
enum class ClipEdge {
    /** The clip's in-point: where in the source it starts. */
    IN,

    /** The clip's out-point: where in the source it ends. */
    OUT,
}

/**
 * The in/out points a drag of [edge] to [sourceTimeUs] means, as an INTENT.
 *
 * The other edge is held fixed — a trim moves one end, never both — which is the whole reason this
 * is a function rather than something the caller assembles: getting the pair the wrong way round
 * (moving the out-point while reporting the in-point) is a silent two-sided trim.
 *
 * ### The clamping is deliberately NOT here
 *
 * [TrimClip] owns "how far a trim may actually go" (source bounds, the 100 ms floor, and the
 * coerceIn range guard for a source shorter than a clip). This returns what the user ASKED for, so
 * there is one place that decides what they GET — and so a drag that runs past the end of the source
 * keeps reporting the intended value instead of silently freezing, which is what tells the UI to
 * stop following the finger. Callers that need the clamped result (to show the frame at the edge)
 * get it by applying the command: `TrimClip(...).apply(document)`, not by re-deriving it.
 */
fun Clip.trimmedTo(edge: ClipEdge, sourceTimeUs: Long): Pair<Long, Long> = when (edge) {
    ClipEdge.IN -> sourceTimeUs to sourceOutUs
    ClipEdge.OUT -> sourceInUs to sourceTimeUs
}

/**
 * The source time an edge drag lands on, given a timeline position within this clip.
 *
 * The conversion matters for a speed-changed or reversed clip: the user drags along the TIMELINE,
 * and the value a trim command needs is a SOURCE time. [sourceTimeAt] is the mapping the renderer
 * uses, so a trim cannot end up disagreeing with playback about which frame a position means.
 */
fun Clip.sourceTimeFor(edgeTimeUs: Long): Long = sourceTimeAt(offsetUs = edgeTimeUs)
