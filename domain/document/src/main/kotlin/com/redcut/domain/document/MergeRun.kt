package com.redcut.domain.document

/**
 * The merge precondition (FR-2.4), in one place.
 *
 * ### Why this lives in its own file
 *
 * The rule was already implemented — privately, inside `EditCommand.kt`, where [MergeClips] used it to
 * decide whether to do anything. The Cut toolbar needs to ask the SAME question ("may I merge this?")
 * to decide whether to enable its button, and a second copy of a four-part precondition is a second
 * copy that will drift: the button would offer a merge the command then refuses silently, which is the
 * exact failure mode FR-2 asks to avoid ("disable the button and explain why").
 *
 * So the rule moved here, both callers read it, and the explanations the UI shows are the same strings
 * the rule is written in.
 */
internal data class MergeRun(val startIndex: Int, val clips: List<Clip>)

/**
 * Why [right] cannot fuse onto [left], or null when it can.
 *
 * Four reasons, in the order a user would notice them: a different file is obvious, a different speed
 * is visible in the picture, a reversed clip is visible in the picture, and "not continuous in the
 * source" is the one that needs explaining — it is invisible on the timeline, where the two clips sit
 * next to each other, and only exists in the file.
 *
 * The speed and direction clauses are deliberate: clips with different speeds cover different amounts
 * of timeline per unit of source, so merging them would silently change how one of them plays.
 * Refusing is better than quietly altering the edit.
 */
internal fun mergeBlocker(left: Clip, right: Clip): String? = when {
    left.sourceId != right.sourceId -> DIFFERENT_SOURCE
    left.speed != right.speed -> DIFFERENT_SPEED
    left.reverse != right.reverse -> DIFFERENT_DIRECTION
    left.sourceOutUs != right.sourceInUs -> NOT_CONTINUOUS
    else -> null
}

/**
 * The maximal run starting at [clipId] that could fuse, in timeline order, ON ONE TRACK.
 *
 * Walking forwards rather than asking for a fixed selection is what makes FR-2.4's "two or more"
 * work: a clip split into five pieces and never retrimmed fuses back in one action, and the first
 * clip that cannot join stops the run.
 *
 * The walk never leaves [trackId]: a run is a stretch of one lane, so the clip after the last one on
 * a track is nothing, not the first clip of the next track.
 */
fun EditDocument.mergeRunFrom(trackId: String, clipId: String): List<Clip> {
    val clips = trackById(trackId)?.clips ?: return emptyList()
    val start = clips.indexOfFirst { it.id == clipId }
    if (start < 0) return emptyList()
    val run = mutableListOf(clips[start])
    var index = start
    while (index + 1 < clips.size && mergeBlocker(clips[index], clips[index + 1]) == null) {
        index++
        run += clips[index]
    }
    return run
}

/**
 * May the clip at the playhead be merged with what follows it on [trackId]?
 *
 * Separate reasons for the three ways it can fail, because they have nothing in common from the user's
 * side: there is no clip on that lane, there is nothing after it, or the next clip cannot join and the
 * specific clause above says why. An unknown track reads as the first of those: there is nothing there
 * to merge, which is what the toolbar's disabled button should say.
 */
fun EditDocument.mergeAvailability(trackId: String, clipId: String): CutAvailability {
    val clips = trackById(trackId)?.clips ?: return CutAvailability.Unavailable(NOTHING_TO_MERGE)
    if (clips.isEmpty()) return CutAvailability.Unavailable(NOTHING_TO_MERGE)
    val start = clips.indexOfFirst { it.id == clipId }
    if (start < 0) return CutAvailability.Unavailable(NOTHING_TO_MERGE)
    val next = clips.getOrNull(start + 1)
        ?: return CutAvailability.Unavailable(NOTHING_AFTER)

    return mergeBlocker(clips[start], next)
        ?.let { CutAvailability.Unavailable(it) }
        ?: CutAvailability.Available
}

/**
 * The run [ids] selects on [trackId], or null when the selection is not a legal merge (FR-2.4).
 *
 * Two steps, named and separate, because they are two different questions: WHERE the clips are (a
 * contiguous run of real clips on ONE lane) and whether the PICTURE allows them to fuse
 * (source-adjacent, same speed and direction). The command and the toolbar both read the answer —
 * which is the whole point of the file, and why the two steps are not inlined into one block of guards.
 */
internal fun EditDocument.mergeRunOf(trackId: String, ids: List<String>): MergeRun? {
    val run = contiguousRun(trackId, ids) ?: return null
    return if (run.isMergeable()) run else null
}

/**
 * The contiguous run [ids] selects on [trackId], or null when the selection is not the run it claims.
 *
 * One question asked once: take the clip the selection STARTS at, take as many clips as the selection
 * named, and the slice is the run exactly when it is those ids in that order. "Every id exists", "the
 * run does not overrun the track" and "the ids are adjacent" are all the same failure seen three ways,
 * and asking them as separate guards is what made this read as a list of unrelated checks rather than
 * as one rule about a run.
 *
 * The order is compared as well as the membership, deliberately: a "selection" that names a run
 * backwards is not the run, and `take` past the end of a track yields a SHORT slice rather than the
 * exception `subList` would throw from a path a finger can reach.
 */
private fun EditDocument.contiguousRun(trackId: String, ids: List<String>): MergeRun? {
    val clips = trackById(trackId)?.clips ?: return null
    val unique = ids.distinct()
    if (unique.size < 2 || unique.size != ids.size) return null

    val start = clips.indexOfFirst { it.id == unique.first() }
    val slice = if (start < 0) emptyList() else clips.drop(start).take(unique.size)

    return if (slice.map { it.id } == unique) MergeRun(start, slice) else null
}

/** Whether every neighbouring pair may fuse — the source-side precondition, pair by pair. */
private fun MergeRun.isMergeable(): Boolean =
    (0 until clips.size - 1).none { mergeBlocker(clips[it], clips[it + 1]) != null }

private const val DIFFERENT_SOURCE =
    "Merge needs clips from the same video; these come from different files."

private const val DIFFERENT_SPEED =
    "Merge needs clips at the same speed; these play at different speeds."

private const val DIFFERENT_DIRECTION =
    "Merge needs clips pointing the same way; one of these is reversed."

private const val NOT_CONTINUOUS =
    "Merge needs clips that are continuous in the source: " +
        "one must end exactly where the other starts."

private const val NOTHING_TO_MERGE = "Select a clip to merge."

private const val NOTHING_AFTER =
    "This is the last clip; there is nothing after it to merge with."
