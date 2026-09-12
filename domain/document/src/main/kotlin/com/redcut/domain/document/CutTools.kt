package com.redcut.domain.document

/**
 * The Cut stage's tools (FR-2.2–2.6).
 *
 * The commands themselves have existed since Phase 1.2 ([SplitClip], [CutLeft], [CutRight],
 * [DeleteClip]); what this file adds is the answer to "may the user do that HERE, and if not, why" —
 * which is a question about the document, not about a button, and so belongs next to the rules it
 * depends on rather than in a screen.
 */
enum class CutTool {
    /** Split the clip at the playhead into two (FR-2.5). */
    SPLIT,

    /** Remove everything in the clip before the playhead (FR-2.2). */
    CUT_LEFT,

    /** Remove everything in the clip after the playhead (FR-2.3). */
    CUT_RIGHT,

    /** Delete the clip at the playhead and ripple-close the gap (FR-2.6). */
    DELETE,

    /** Fuse the clip at the playhead with the run of source-adjacent clips after it (FR-2.4). */
    MERGE,

    /** Copy the clip at the playhead immediately after itself (FR-2.8). */
    DUPLICATE,
}

/**
 * Whether a tool can run, and when it cannot, WHY.
 *
 * A reason rather than a bare boolean, and the reason is a full sentence the UI shows. The spec asks
 * for exactly this shape for merge ("disable the button and explain why") and the same argument holds
 * here: a greyed-out button with no explanation is the most common way an editor feels broken.
 * Modelling it in the domain also means the explanation is tested, not typed into a composable.
 */
sealed interface CutAvailability {

    data object Available : CutAvailability

    data class Unavailable(val reason: String) : CutAvailability
}

/**
 * How long the whole timeline is, in microseconds.
 *
 * Derived, never stored (spec §5.1): every position in the editor comes from prefix-summing the clips'
 * own durations, and this is where that sum lives. It used to exist only in the editor's projection,
 * which meant the domain's frame-stepping could not ask how far the timeline went — a rule with two
 * homes is a rule that disagrees with itself eventually.
 */
val EditDocument.timelineDurationUs: Long get() = clips.sumOf { it.timelineDurationUs }

/**
 * The clip the playhead is inside, or null when it is past the end of the timeline.
 *
 * Boundaries belong to the clip on their RIGHT, the same rule the timeline's hit-testing uses for a
 * clip edge: at a boundary the cut lands on the clip that is about to start, which is what a playhead
 * parked on a cut point means when the user presses "cut right".
 */
fun EditDocument.clipAt(playheadUs: Long): Clip? {
    var start = 0L
    clips.forEach { clip ->
        val end = start + clip.timelineDurationUs
        if (playheadUs >= start && playheadUs < end) return clip
        start = end
    }
    return null
}

/** Where [clipId] starts on the timeline, or null when the document has no such clip. */
fun EditDocument.timelineStartOf(clipId: String): Long? {
    var start = 0L
    clips.forEach { clip ->
        if (clip.id == clipId) return start
        start += clip.timelineDurationUs
    }
    return null
}

/**
 * How far into [clipId] the playhead is, in TIMELINE time, or null when the playhead is not on it.
 *
 * Timeline time, not source time: turning this into a source time is the clip's own job
 * ([sourceTimeFor]), and doing it here would put a sped-up or reversed clip's mapping in two places.
 */
fun EditDocument.offsetIntoClip(clipId: String, playheadUs: Long): Long? {
    val start = timelineStartOf(clipId) ?: return null
    val clip = clips.firstOrNull { it.id == clipId } ?: return null
    val offset = playheadUs - start
    return if (offset >= 0 && offset < clip.timelineDurationUs) offset else null
}

/**
 * May [tool] run at [playheadUs]?
 *
 * The rules are the commands' own, read back: a split needs two halves that both survive the 100 ms
 * floor (which is why [SplitClip] refuses without saying so), and delete needs something left to
 * render, because an empty document cannot be played and undo is the only way back ([DeleteClip]
 * refuses to empty the timeline).
 */
fun EditDocument.availabilityFor(tool: CutTool, playheadUs: Long): CutAvailability {
    if (clips.isEmpty()) return CutAvailability.Unavailable(NOTHING_TO_CUT)
    val clip = clipAt(playheadUs) ?: return CutAvailability.Unavailable(PLAYHEAD_PAST_END)

    return when (tool) {
        CutTool.SPLIT -> splitAvailability(clip, playheadUs)
        CutTool.CUT_LEFT -> availabilityInsideClip(clip.id, playheadUs)
        CutTool.CUT_RIGHT -> availabilityInsideClip(clip.id, playheadUs)
        CutTool.DELETE ->
            if (clips.size <= 1) {
                CutAvailability.Unavailable(LAST_CLIP)
            } else {
                CutAvailability.Available
            }

        // Merge asks a different question (is the NEXT clip fusable?) and has its own four reasons,
        // which is why its rule lives in MergeRun.kt and this just forwards the clip at the playhead.
        CutTool.MERGE -> mergeAvailability(clip.id)

        // Duplicate needs only a clip to copy, and reaching this line means the playhead is on one.
        // Its command refuses nothing else: the id comes from the same source as a split's, so a
        // collision is impossible by construction.
        CutTool.DUPLICATE -> CutAvailability.Available
    }
}

/**
 * A split needs room on BOTH sides, which is the one rule of the four that can surprise: the playhead
 * being inside the clip is not enough if either half would fall under the floor — that would be a
 * trim wearing a split's name, and [SplitClip] would refuse it silently.
 */
private fun EditDocument.splitAvailability(clip: Clip, playheadUs: Long): CutAvailability {
    val offset = offsetIntoClip(clip.id, playheadUs) ?: return AT_THE_BOUNDARY
    val remaining = clip.timelineDurationUs - offset
    return if (offset < Clip.MIN_DURATION_US || remaining < Clip.MIN_DURATION_US) {
        CutAvailability.Unavailable(TOO_CLOSE_TO_EDGE)
    } else {
        CutAvailability.Available
    }
}

/**
 * Cut left and cut right accept a playhead near an edge, because there the command DELETES the clip
 * (FR-2's floor rule) — which is a real action rather than a refusal: the user asked to remove
 * everything on one side of the playhead, and removing all of it satisfies that.
 */
private fun EditDocument.availabilityInsideClip(clipId: String, playheadUs: Long): CutAvailability =
    if (offsetIntoClip(clipId, playheadUs) == null) {
        AT_THE_BOUNDARY
    } else {
        CutAvailability.Available
    }

/**
 * The command [tool] means at [playheadUs], or null when it is not available.
 *
 * [newClipId] is a supplier rather than a value because only a split needs an id, and generating one
 * for the other three would make this function impure for their sake. Calling it exactly once, inside
 * the split branch, keeps the command the caller gets as comparable as the commands in this package
 * are (see [SplitClip]).
 */
fun EditDocument.commandFor(
    tool: CutTool,
    playheadUs: Long,
    newClipId: () -> String,
): EditCommand? {
    if (availabilityFor(tool, playheadUs) !is CutAvailability.Available) return null
    val clip = clipAt(playheadUs) ?: return null
    val offsetUs = offsetIntoClip(clip.id, playheadUs) ?: return null
    val atSourceUs = clip.sourceTimeFor(offsetUs)

    return when (tool) {
        CutTool.SPLIT -> SplitClip(
            clipId = clip.id,
            atSourceUs = atSourceUs,
            newClipId = newClipId(),
        )
        CutTool.CUT_LEFT -> CutLeft(clipId = clip.id, atSourceUs = atSourceUs)
        CutTool.CUT_RIGHT -> CutRight(clipId = clip.id, atSourceUs = atSourceUs)
        CutTool.DELETE -> DeleteClip(clipId = clip.id)
        // The whole run, not just the next clip: FR-2.4 says "two or more", and a clip split into
        // five pieces comes back in one action. mergeRunFrom stops at the first clip that cannot join.
        CutTool.MERGE -> MergeClips(mergeRunFrom(clip.id).map { it.id })
        CutTool.DUPLICATE -> DuplicateClip(clipId = clip.id, newClipId = newClipId())
    }
}

private const val NOTHING_TO_CUT = "Import a video to start cutting."

private const val PLAYHEAD_PAST_END =
    "Move the playhead onto a clip; it is past the end of the timeline."

private const val TOO_CLOSE_TO_EDGE =
    "The playhead is too close to the clip's edge; both halves must be at least 100 ms."

private const val LAST_CLIP =
    "The timeline must keep at least one clip; delete is unavailable on the last one."

private val AT_THE_BOUNDARY = CutAvailability.Unavailable(
    "The playhead is on a cut point; move it into the clip you want to change.",
)
