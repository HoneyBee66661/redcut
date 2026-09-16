package com.redcut.domain.document

/**
 * Where a clip lands if it is dropped at [dropTimelineUs] (FR-2.7).
 *
 * ### Why this is arithmetic and not gesture code
 *
 * "Drag a clip along the timeline to change its order" hides a question with more edge cases than it
 * looks: what does dropping on top of another clip mean, what does dropping past the end mean, and
 * what happens to the clip's own slot while it is being dragged. Answering those in a drag handler
 * would put them behind a finger, where no test can reach them; answering them here puts them in the
 * fast tier, and leaves the gesture as "ask the document where this would land, then say so".
 *
 * ### The rule
 *
 * The OTHER items are laid out in order — the dragged clip taken out, and [Gap]s left where they are —
 * and the drop lands in the slot before the first clip whose MIDPOINT is past the drop point. So
 * dropping on the left half of a clip puts the dragged clip before it and the right half puts it after,
 * which is what the insertion marker under the user's finger has to show.
 *
 * ### Why the dragged clip is taken OUT first, and why that is not a detail
 *
 * This layout is the OTHERS-ONLY one, and the two sums this function and [reorderMarkerUs] used to be
 * are the reason it is spelled out here: "the clips, in order" reads as a plain prefix sum, and a plain
 * prefix sum happens to be right for a lane with no gaps. It is wrong twice over once a lane can hold a
 * [Gap], and the two errors point in OPPOSITE directions:
 *
 * - a sum over clips alone is SHORT by every gap before the drop point, so the marker is drawn early
 *   and the index it returns can be one slot out;
 * - walking the track's REAL items instead ([Track.positionedClips]) is LONG by the dragged clip itself
 *   whenever the drop is at the end. For a lane of a(2 s) + b(3 s) with a dragged to the end, the marker
 *   belongs at 3 s — right after b — but the real list still holds a, so its walk has nothing at index 1
 *   and the end of the walk answers 5 s: the whole lane, including the clip being moved.
 *
 * So the layout is the items WITH the dragged clip removed and gaps kept, and it is built once per call
 * ([othersIn]) and walked by the one gap-aware walk in the domain
 * ([List.positionedClips]). Both readers use it, which is what stops the index and the marker from
 * disagreeing about a gap.
 *
 * ### The index
 *
 * The returned index is an index into the track's CLIP list, ready for [ReorderClip], because the command
 * removes the dragged clip from its old slot before inserting it — which means the insertion index among
 * the OTHERS is the same number. It is an index over clips even when the lane holds a gap: a gap is not
 * something the command can insert before, since it has no identity to name.
 *
 * A drag happens along ONE lane ([trackId]): the marker under the finger is a position in that lane's own
 * order. An unknown track has no slots, so the answer is 0 — the same answer an empty lane gives, which is
 * the honest one for "where would this land": nowhere.
 */
fun EditDocument.reorderTargetIndex(trackId: String, clipId: String, dropTimelineUs: Long): Int {
    val placed = othersIn(trackId, clipId).positionedClips()
    if (placed.isEmpty()) return 0

    placed.forEachIndexed { index, slot ->
        val midpoint = slot.startUs + slot.clip.timelineDurationUs / 2
        if (dropTimelineUs < midpoint) return index
    }
    return placed.size
}

/**
 * Where the dragged clip's marker goes within [trackId], in microseconds, for [targetIndex].
 *
 * Two answers, and the second one is the case the doc comment above warns about: before the clip at
 * [targetIndex] — at ITS start, which a [Gap] before it has already pushed right — or, past the last one,
 * at the end of the OTHERS-ONLY layout. That end is the sum of the others' own extents, gaps included, and
 * NOT [Track.contentEndUs]: the lane's end still holds the room the dragged clip occupies, so using it
 * would draw the marker past the end of everything the user can see moving.
 */
fun EditDocument.reorderMarkerUs(trackId: String, clipId: String, targetIndex: Int): Long {
    val others = othersIn(trackId, clipId)
    val placed = others.positionedClips()
    val clamped = targetIndex.coerceIn(0, placed.size)
    return placed.getOrNull(clamped)?.startUs ?: others.sumOf { it.timelineExtentUs }
}

/**
 * The lane's items with the dragged clip taken out, and its [Gap]s left where they are.
 *
 * The one place the others-only layout is built, so the index and the marker cannot be computed from two
 * different lists. An unknown track, or a clip the lane does not hold, leaves every clip in — which is the
 * honest answer for a drag that named something the document does not have: the layout is then the lane as
 * it is, and the command is what refuses the move (see [ReorderClip]).
 */
private fun EditDocument.othersIn(trackId: String, clipId: String): List<TrackItem> =
    trackById(trackId)?.items?.filterNot { it is Clip && it.id == clipId } ?: emptyList()
