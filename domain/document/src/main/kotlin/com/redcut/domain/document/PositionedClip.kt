package com.redcut.domain.document

/**
 * A clip and the timeline start it actually has.
 *
 * The start is DERIVED, never stored — the same reason [TimelineSlot] exists, and
 * [EditDocument.timeline] is what turns these into slots. It is a type of its own because a start on
 * its own is not a position: a caller holding one has to be able to ask which clip it belongs to, and a
 * pair of two loose values cannot say which half is which.
 */
data class PositionedClip(val clip: Clip, val startUs: Long) {
    /** Where this clip's own room ends. A gap after it is room the LANE spends, not this clip. */
    val endUs: Long get() = startUs + clip.timelineDurationUs
}

/**
 * How long this lane's contents run: the end of its LAST item, gaps included.
 *
 * The end of the last ITEM rather than of the last clip, because a lane that finishes with a gap still
 * spends that room — the timeline has to show it, and a lane whose last clip is trimmed to nothing
 * would otherwise shorten the document without anything having changed about what the user sees.
 */
val Track.contentEndUs: Long get() = items.sumOf { it.timelineExtentUs }

/**
 * Every clip on this lane with the start it actually has, in order.
 *
 * ### Why one walk answers both cases
 *
 * The cursor advances over EVERY item, so a [Gap] moves everything after it — and a lane with no gaps
 * is not a second branch: with nothing but clips the same cursor produces exactly the starts a plain
 * prefix sum over [Track.clips] produced, which is what lets every reader written before gaps existed
 * keep its old answer while a gapped document gets the right one. A rule written once cannot disagree
 * with itself; this sum used to live in four places, and each of the four was blind to a gap the same
 * way.
 *
 * The result holds CLIPS only, because a gap has no identity to hand back and nothing to draw. What it
 * changes is where the clips after it start, and that is the one line the loop below shares.
 */
fun Track.positionedClips(): List<PositionedClip> = items.positionedClips()

/**
 * The same walk over ANY item list.
 *
 * Its own overload rather than "put the items on a Track and call the other one", because the caller that
 * needs it has a list that is deliberately NOT a track's own: [EditDocument.reorderTargetIndex] lays the
 * lane out with the dragged clip REMOVED — the marker under the finger is where that clip would land among
 * the others — and a throwaway Track for that would have to invent an id, a kind and every default, and
 * would answer a different `contentEndUs` than the list it was built from.
 *
 * One walk, two entry points: [Track.positionedClips] is this function with a track's own items, which is
 * what keeps the gap rule in one place instead of two that could drift.
 */
fun List<TrackItem>.positionedClips(): List<PositionedClip> {
    val placed = mutableListOf<PositionedClip>()
    var cursor = 0L
    forEach { item ->
        if (item is Clip) placed += PositionedClip(item, cursor)
        cursor += item.timelineExtentUs
    }
    return placed
}

/**
 * How much timeline room an item spends: a clip's trimmed length, or a gap's own.
 *
 * `internal` rather than `private` because the layout sums live in two files — this one and
 * [EditDocument.reorderMarkerUs] — and the alternative is that the second one re-states the rule for
 * gaps and gets it wrong for the kind nobody has thought about yet.
 */
internal val TrackItem.timelineExtentUs: Long
    get() = when (this) {
        is Clip -> timelineDurationUs
        is Gap -> durationUs
    }
