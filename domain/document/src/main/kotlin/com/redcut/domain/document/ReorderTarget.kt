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
 * The other clips are laid out contiguously (the timeline has no gaps — MVP is ripple-only), and the
 * drop lands in the slot before the first clip whose MIDPOINT is past the drop point. So dropping on
 * the left half of a clip puts the dragged clip before it and the right half puts it after, which is
 * what the insertion marker under the user's finger has to show.
 *
 * The returned index is an index into the TRACK's clip list, ready for [ReorderClip], because the
 * command removes the dragged clip from its old slot before inserting it — which means the insertion
 * index among the OTHERS is the same number.
 *
 * A drag happens along ONE lane ([trackId]): the marker under the finger is a position in that lane's
 * own order. An unknown track has no slots, so the answer is 0 — the same answer an empty lane gives,
 * which is the honest one for "where would this land": nowhere.
 */
fun EditDocument.reorderTargetIndex(trackId: String, clipId: String, dropTimelineUs: Long): Int {
    val others = trackById(trackId)?.clips?.filterNot { it.id == clipId } ?: return 0
    if (others.isEmpty()) return 0

    var slotStart = 0L
    others.forEachIndexed { index, clip ->
        val midpoint = slotStart + clip.timelineDurationUs / 2
        if (dropTimelineUs < midpoint) return index
        slotStart += clip.timelineDurationUs
    }
    return others.size
}

/** Where the dragged clip's marker goes within [trackId], in microseconds, for [targetIndex]. */
fun EditDocument.reorderMarkerUs(trackId: String, clipId: String, targetIndex: Int): Long {
    val others = trackById(trackId)?.clips?.filterNot { it.id == clipId } ?: return 0L
    val clamped = targetIndex.coerceIn(0, others.size)
    return others.take(clamped).sumOf { it.timelineDurationUs }
}
