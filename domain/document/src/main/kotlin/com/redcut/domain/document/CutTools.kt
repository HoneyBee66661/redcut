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
 * [EditDocument.durationUs], under the name the editor's projection has always used. It used to live
 * only in that projection, which meant the domain's frame-stepping could not ask how far the timeline
 * went — and a rule with two homes is a rule that disagrees with itself eventually, which is why this
 * is now a second NAME for the one sum rather than a second sum: every position here comes from
 * [EditDocument.timeline], whose walk advances over a lane's items, so a [Gap] takes the room it shows.
 *
 * The timeline is as long as its LONGEST lane, because a second track plays ALONGSIDE the first and
 * the lanes are not added up. The flat reading of [EditDocument.timeline] does lay them end to end,
 * so with more than one lane the last slot of that list sits past this number: that is the flat
 * reading's length and not the timeline's, a face of the ambiguity WS C6 resolves rather than a
 * defect here. Where this number counts is that the ONE-lane document every project is still reads
 * back exactly what it always did — the longest of one lane is that lane.
 */
val EditDocument.timelineDurationUs: Long get() = durationUs

/**
 * The clip the playhead is inside, or null when it is past the end or parked in a gap.
 *
 * Boundaries belong to the clip on their RIGHT, the same rule the timeline's hit-testing uses for a
 * clip edge: at a boundary the cut lands on the clip that is about to start, which is what a playhead
 * parked on a cut point means when the user presses "cut right".
 *
 * Reads [EditDocument.timeline], so with more than one track it answers about the timeline as ONE
 * lane. "Which clip does this position mean" is a question one list can answer; turning it into the
 * per-lane one (see [EditDocument.lanes], one clip per lane) takes an intent that names the lane it
 * acts on, which is WS C6. So this is the answer the commands are built on until the intents carry
 * a track id. A gap is no clip's time, so a playhead parked in one has nothing to cut, and the
 * null it gets is the same "there is no clip here" the end of the timeline gives. The clip it
 * returns is the clip a tool at that position means; WHICH lane that clip is on is answered by
 * [EditDocument.trackIdOf], and that is what the commands are built with.
 */
fun EditDocument.clipAt(playheadUs: Long): Clip? = timeline.firstOrNull { playheadUs in it }?.clip

/**
 * The clip a tool at [playheadUs] means ON [trackId], or null when there is none there.
 *
 * The LANE-scoped reading of [clipAt], and the whole reason WS C6 has a remaining half: the flat reading
 * walks [EditDocument.timeline], which lays every lane END TO END, so on a project with an audio lane a
 * playhead past the video lane's end resolves to the AUDIO clip — a cut offered, and applied, on a lane
 * the user is not looking at. The playhead is one number and the timeline is more than one lane, so which
 * lane a position means is a question only the caller can answer; this is where the answer is spent.
 *
 * The positions come from the lane's own walk ([Track.positionedClips]), so a [Gap] on this lane moves the
 * clips after it here exactly as it does everywhere else.
 *
 * Boundaries belong to the clip on their RIGHT, the same rule the flat reading and the timeline's
 * hit-testing keep.
 */
fun EditDocument.clipAt(trackId: String, playheadUs: Long): Clip? =
    trackById(trackId)?.positionedClips()
        ?.firstOrNull { playheadUs >= it.startUs && playheadUs < it.endUs }
        ?.clip

/**
 * How far into [clipId] the playhead is, in TIMELINE time, read on [trackId]'s OWN positions.
 *
 * The lane-scoped twin of [offsetIntoClip], and it is not a convenience: the flat one subtracts the
 * slot's start on a timeline where the earlier lanes' clips come first, so for a clip on the second lane
 * it answers an offset shifted by the first lane's whole length — the arithmetic a lane-scoped cut would
 * otherwise carry into every command it builds.
 */
fun EditDocument.offsetIntoClip(trackId: String, clipId: String, playheadUs: Long): Long? {
    val placed = trackById(trackId)?.positionedClips()
        ?.firstOrNull { it.clip.id == clipId } ?: return null
    val offset = playheadUs - placed.startUs
    return if (offset >= 0 && offset < placed.clip.timelineDurationUs) offset else null
}

/**
 * Where [clipId] starts on the timeline, or null when the document has no such clip.
 *
 * The start the clip actually has, a gap in front of it included: this reads [EditDocument.timeline]
 * rather than summing the clips again, because a second sum is a second place to forget a gap.
 */
fun EditDocument.timelineStartOf(clipId: String): Long? =
    timeline.firstOrNull { it.clip.id == clipId }?.startUs

/**
 * How far into [clipId] the playhead is, in TIMELINE time, or null when the playhead is not on it.
 *
 * Timeline time, not source time: turning this into a source time is the clip's own job
 * ([sourceTimeFor]), and doing it here would put a sped-up or reversed clip's mapping in two places.
 * The start it subtracts is the slot's own, so a gap before the clip is already accounted for.
 */
fun EditDocument.offsetIntoClip(clipId: String, playheadUs: Long): Long? {
    val slot = timeline.firstOrNull { it.clip.id == clipId } ?: return null
    val offset = playheadUs - slot.startUs
    return if (offset >= 0 && offset < slot.durationUs) offset else null
}

/**
 * May [tool] run at [playheadUs]?
 *
 * The rules are the commands' own, read back: a split needs two halves that both survive the 100 ms
 * floor (which is why [SplitClip] refuses without saying so), and delete needs something left to
 * render, because an empty document cannot be played and undo is the only way back ([DeleteClip]
 * refuses to empty the document).
 */
fun EditDocument.availabilityFor(tool: CutTool, playheadUs: Long): CutAvailability {
    if (clips.isEmpty()) return CutAvailability.Unavailable(NOTHING_TO_CUT)
    val clip = clipAt(playheadUs) ?: return CutAvailability.Unavailable(PLAYHEAD_PAST_END)
    // A clip always belongs to a track — `clips` is DERIVED from them — so this cannot fail for a
    // document built by the commands. It is here because the alternative is a `!!` in a tool path, and
    // the honest refusal for "this clip is on no lane I can name" is the same one as "there is no clip".
    val trackId = trackIdOf(clip.id) ?: return CutAvailability.Unavailable(PLAYHEAD_PAST_END)

    // Everything below is the LANE-scoped rule, which is why this reading is a lookup and a forward:
    // there is one answer to "may this tool run here", not two that could drift (WS C6).
    return availabilityFor(tool, trackId, clip.id, playheadUs)
}

/**
 * May [tool] run on [clipId] of [trackId] at [playheadUs]? (WS C6's remaining half.)
 *
 * The rules are the commands' own, read back: a split needs two halves that both survive the 100 ms
 * floor (which is why [SplitClip] refuses without saying so), and delete needs something left to
 * render, because an empty document cannot be played and undo is the only way back ([DeleteClip]
 * refuses to empty the document).
 *
 * Everything it reads is lane-scoped: the clip is looked up ON [trackId], and the offset comes from that
 * lane's own positions. The flat reading is what this replaces — it is the reading under which a playhead
 * past the video lane's end resolves to a clip of the audio lane that happens to sit there end-to-end.
 */
fun EditDocument.availabilityFor(
    tool: CutTool,
    trackId: String,
    clipId: String,
    playheadUs: Long,
): CutAvailability {
    if (clips.isEmpty()) return CutAvailability.Unavailable(NOTHING_TO_CUT)
    val clip = trackById(trackId)?.clipById(clipId)
        ?: return CutAvailability.Unavailable(NOTHING_TO_CUT)
    // Asked ONCE, and every branch that needs it reads this value: the two cut directions and the split
    // all mean "where inside the clip", and a second lookup could only ever answer the same thing.
    val offsetUs = offsetIntoClip(trackId, clipId, playheadUs)

    return when (tool) {
        CutTool.SPLIT -> splitAvailability(clip, offsetUs)
        // Cut left and cut right accept a playhead near an edge, because there the command DELETES the
        // clip (FR-2's floor rule) — a real action rather than a refusal: the user asked to remove
        // everything on one side of the playhead, and removing all of it satisfies that. Off the clip
        // entirely, though, there is no side to remove, which is the boundary refusal.
        CutTool.CUT_LEFT, CutTool.CUT_RIGHT ->
            if (offsetUs == null) AT_THE_BOUNDARY else CutAvailability.Available
        CutTool.DELETE ->
            if (clips.size <= 1) {
                CutAvailability.Unavailable(LAST_CLIP)
            } else {
                CutAvailability.Available
            }

        // Merge asks a different question (is the NEXT clip fusable?) and has its own four reasons,
        // which is why its rule lives in MergeRun.kt and this just forwards the clip — together with the
        // lane it is on, because "what follows it" is a question about that lane.
        CutTool.MERGE -> mergeAvailability(trackId, clip.id)

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
 *
 * [offsetUs] is already the lane-scoped offset, so this reads the clip and nothing else: what is left of
 * the clip is its own trimmed length minus where the playhead is inside it.
 */
private fun EditDocument.splitAvailability(clip: Clip, offsetUs: Long?): CutAvailability {
    val offset = offsetUs ?: return AT_THE_BOUNDARY
    val remaining = clip.timelineDurationUs - offset
    return if (offset < Clip.MIN_DURATION_US || remaining < Clip.MIN_DURATION_US) {
        CutAvailability.Unavailable(TOO_CLOSE_TO_EDGE)
    } else {
        CutAvailability.Available
    }
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
    // A lookup and a forward, like [availabilityFor]'s flat half: WHICH lane the playhead means in the
    // flat reading is `trackIdOf`'s answer, and the command itself is built by the lane-scoped function
    // below so there is one construction rather than two (WS C6).
    val clip = clipAt(playheadUs) ?: return null
    val trackId = trackIdOf(clip.id) ?: return null
    return commandFor(tool, trackId, clip.id, playheadUs, newClipId)
}

/**
 * The command [tool] means on [clipId] of [trackId] at [playheadUs], or null when it is not available
 * (WS C6's remaining half).
 *
 * The lane is what makes this reading different from the flat one, and it is spent twice: the clip is
 * looked up ON [trackId] and the offset comes from that lane's own positions, so a cut can no longer land
 * on a clip of another lane that merely sits at the same place in the end-to-end reading.
 *
 * [newClipId] is a supplier rather than a value because only a split needs an id, and generating one
 * for the other three would make this function impure for their sake. Calling it exactly once, inside
 * the split branch, keeps the command the caller gets as comparable as the commands in this package
 * are (see [SplitClip]).
 */
fun EditDocument.commandFor(
    tool: CutTool,
    trackId: String,
    clipId: String,
    playheadUs: Long,
    newClipId: () -> String,
): EditCommand? {
    if (availabilityFor(
            tool,
            trackId,
            clipId,
            playheadUs,
        ) !is CutAvailability.Available
    ) {
        return null
    }
    val clip = clipById(clipId) ?: return null
    // Asked for INSIDE the branches that need it, and that is a behaviour rather than tidiness: delete,
    // duplicate and merge NAME a clip and nothing else, so demanding the playhead be on that clip would
    // refuse a command the availability rule had just offered — the button/command disagreement FR-2
    // exists to prevent. The three position-dependent tools read it through this lambda.
    val atSourceUs = { offsetIntoClip(trackId, clipId, playheadUs)?.let(clip::sourceTimeFor) }

    return when (tool) {
        CutTool.SPLIT -> atSourceUs()?.let { sourceUs ->
            SplitClip(
                trackId = trackId,
                clipId = clip.id,
                atSourceUs = sourceUs,
                newClipId = newClipId(),
            )
        }
        CutTool.CUT_LEFT -> atSourceUs()?.let { CutLeft(trackId, clip.id, it) }
        CutTool.CUT_RIGHT -> atSourceUs()?.let { CutRight(trackId, clip.id, it) }
        CutTool.DELETE -> DeleteClip(trackId = trackId, clipId = clip.id)
        // The whole run, not just the next clip: FR-2.4 says "two or more", and a clip split into
        // five pieces comes back in one action. mergeRunFrom stops at the first clip that cannot join,
        // and never leaves the lane the clip at the playhead is on.
        CutTool.MERGE -> MergeClips(trackId, mergeRunFrom(trackId, clip.id).map { it.id })
        CutTool.DUPLICATE -> DuplicateClip(
            trackId = trackId,
            clipId = clip.id,
            newClipId = newClipId(),
        )
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
