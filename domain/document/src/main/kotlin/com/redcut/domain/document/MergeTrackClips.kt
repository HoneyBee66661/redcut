package com.redcut.domain.document

/**
 * The lane-level merge (UI revision 2, §WS E / Task E2): what a TRACK selection is FOR.
 *
 * The plan's words for the task are "cross-clip operations for a selected track (merge, delete all, …)",
 * and the user's reason for wanting a lane selection at all was exactly this: the cut tools act on one
 * clip today, and there is no way to say "do it to everything on this lane". Merge is the operation that
 * has a rule worth writing down, so it is the one that lands here first.
 *
 * ### Why this is a command and not a loop at the call site
 *
 * "Merge every clip on the lane" looks like [MergeClips] in a loop, and it is not: [MergeClips] takes the
 * ids the user selected, so a caller looping it would have to decide WHICH groups to fuse, which means
 * re-deriving [mergeBlocker] and [mergeRunFrom] — the two facts `MergeRun.kt` exists to keep in one place.
 * A second derivation of "may these two fuse" is a button that offers a merge the command then refuses.
 *
 * ### What "every clip on the lane" means
 *
 * Not "one clip": the lane is walked and every MAXIMAL run of neighbours that may fuse becomes one clip.
 * A lane holding A+B (one source) then C (another) ends with two clips, not one — a mixed lane is the
 * normal case the moment a project has two files in it, and refusing the whole lane because one pair
 * cannot join would make the command useless exactly then.
 *
 * The file is named after the command because detekt's `MatchingDeclarationName` requires it: the two
 * other declarations here are helpers that exist for this one, and the plan's suggested `TrackCommands.kt`
 * would hold a single public class and earn the finding instead. It is also the honest name for what is
 * in it — a command and the two functions that are only here to serve it.
 */
data class MergeTrackClips(val trackId: String) : EditCommand {
    override val label: String get() = "Merge lane"

    override fun apply(doc: EditDocument): EditDocument {
        val track = doc.trackById(trackId) ?: return doc
        val fused = fuseRuns(track.items)
        // Nothing to fuse is a no-op in the same shape as an unmet precondition: identical document, no
        // movement of the revision, no history entry. A lane of one clip, or of clips that cannot join,
        // therefore costs the user neither an undo step nor a recompile.
        if (fused == track.items) return doc
        return doc.withTrackItems(trackId, fused)
    }

    override fun touchedTrackIds(document: EditDocument): Set<String> = setOf(trackId)
}

/**
 * [items] with every maximal run of fusable neighbours replaced by one clip.
 *
 * One walk, and the same rule [MergeClips] applies to a run the user picked: a clip joins the run being
 * built while [mergeBlocker] says it may, and starts a new one when it may not. The merged clip keeps the
 * FIRST clip's identity and properties and takes the LAST clip's source end — so "merge the lane" and
 * "merge these" cannot disagree about the result, which is the whole reason both read [mergeBlocker].
 *
 * A [Gap] ends the run, deliberately. A hole between two clips is a fact the user put on the timeline, and
 * fusing across it would delete the hole while showing a history entry called "Merge lane" — the clip
 * arithmetic would be right and the edit would still be a lie. Nothing creates a gap yet; this is the
 * command that already behaves correctly when something does.
 */
internal fun fuseRuns(items: List<TrackItem>): List<TrackItem> {
    val fused = mutableListOf<TrackItem>()
    var run: Clip? = null
    for (item in items) {
        run = appendItem(item, run, fused)
    }
    run?.let { fused += it }
    return fused
}

/**
 * Writes [item] onto the end of [fused], and answers the run that is still open after it.
 *
 * The step, pulled out of the loop for one reason: detekt's `NestedBlockDepth` counts a loop, a `when`
 * and an `if` inside one another as three, and the step is a readable function on its own — "this item,
 * and the run it leaves behind" is the whole of what the walk does.
 *
 * A `when` with a SUBJECT rather than a chain of conditions, because the clip arm needs `item` to BE a
 * Clip: testing `item is Gap` first and falling through does not give the compiler that, since "not a
 * gap" is not "a clip" to a type checker that cannot see the third case is unreachable.
 */
private fun appendItem(item: TrackItem, run: Clip?, fused: MutableList<TrackItem>): Clip? =
    when (item) {
        // A hole ends the run and is written through unchanged, which is the whole reason this walk reads
        // items rather than clips.
        is Gap -> {
            run?.let { fused += it }
            fused += item
            null
        }
        is Clip ->
            // `run.copy(sourceOutUs = item.sourceOutUs)` IS the fusion: the run's own head, with its source
            // range extended to cover what joined it.
            if (run != null && mergeBlocker(run, item) == null) {
                run.copy(sourceOutUs = item.sourceOutUs)
            } else {
                run?.let { fused += it }
                item
            }
    }

/**
 * Whether [trackId] has anything to fuse, as the toolbar's enablement rule (§WS E3).
 *
 * The same shape as [mergeAvailability], and for the same reason: the button that OFFERS the operation
 * and the command that PERFORMS it read one rule, so the button cannot offer a merge the command then
 * refuses silently. Unknown lane and nothing-to-fuse read as one answer — there is nothing there to act
 * on, which is what a disabled button should say.
 */
fun EditDocument.mergeTrackAvailability(trackId: String): CutAvailability {
    val track = trackById(trackId) ?: return CutAvailability.Unavailable(NOTHING_TO_MERGE_ON_LANE)
    return if (fuseRuns(track.items).size < track.items.size) {
        CutAvailability.Available
    } else {
        CutAvailability.Unavailable(NOTHING_TO_MERGE_ON_LANE)
    }
}

private const val NOTHING_TO_MERGE_ON_LANE =
    "This lane has no clips that can merge: merging needs two neighbours from the same source, " +
        "one ending exactly where the other starts."
