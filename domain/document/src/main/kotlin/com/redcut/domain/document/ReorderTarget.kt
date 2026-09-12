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
 * The returned index is an index into the FULL clip list, ready for [ReorderClip], because the command
 * removes the dragged clip from its old slot before inserting it — which means the insertion index
 * among the OTHERS is the same number.
 */
fun EditDocument.reorderTargetIndex(clipId: String, dropTimelineUs: Long): Int {
    val others = clips.filterNot { it.id == clipId }
    if (others.isEmpty()) return 0

    var slotStart = 0L
    others.forEachIndexed { index, clip ->
        val midpoint = slotStart + clip.timelineDurationUs / 2
        if (dropTimelineUs < midpoint) return index
        slotStart += clip.timelineDurationUs
    }
    return others.size
}

/** Where the dragged clip's insertion marker goes, in timeline microseconds, for [targetIndex]. */
fun EditDocument.reorderMarkerUs(clipId: String, targetIndex: Int): Long {
    val others = clips.filterNot { it.id == clipId }
    val clamped = targetIndex.coerceIn(0, others.size)
    return others.take(clamped).sumOf { it.timelineDurationUs }
}
