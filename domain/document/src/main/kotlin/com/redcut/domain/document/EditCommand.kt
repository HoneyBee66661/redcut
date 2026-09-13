package com.redcut.domain.document

/**
 * A named, comparable, pure edit operation (spec §7.3).
 *
 * Every command is a `data class`, so `equals`/`hashCode` come for free. That is
 * deliberate and load-bearing: the spec explicitly rejects the opaque
 * `(List<EditOperation>) -> List<EditOperation>` escape hatch that LibreCuts uses,
 * because commands that cannot be compared cannot be logged, coalesced, or
 * asserted on in tests.
 *
 * ### Commands are total
 *
 * [apply] never throws. When a precondition fails -- the clip was deleted, the
 * merge operands are not source-adjacent, the split point is too close to an edge
 * -- it returns [doc] unchanged. The UI is responsible for disabling actions whose
 * preconditions fail and explaining why (FR-2.4); the domain layer's job is to be
 * safe to call speculatively. This is also what makes the commands drivable by
 * property tests, which fire them at random states.
 *
 * ### Position semantics
 *
 * Commands address clips by *source* time, never timeline time, because timeline
 * position is derived ([EditDocument.timeline]). Callers holding a playhead
 * position convert once at the edge via [EditDocument.slotAt].
 *
 * ### Every clip command names its track
 *
 * A command that touches a clip carries the `trackId` of the lane it expects to find it on, and
 * refuses when the clip is not there. That is not bookkeeping: clip ids are unique, so a command could
 * look a clip up without a lane and still find it — and then apply a trim, a delete or a ripple to a
 * lane the user is not looking at, because the id came from a frame the timeline has since moved past.
 * Naming the lane turns that class of bug into a no-op, and turns "which track was this meant for?"
 * into a question the CALLER has to answer rather than one the command guesses.
 *
 * The lane is therefore a precondition, exactly like "the clip exists": unmet, the command returns the
 * document unchanged. Nothing here creates a track, and nothing moves a clip between tracks.
 */
sealed interface EditCommand {
    /** Human-readable, shown as "Undo <label>" (spec §7.3). */
    val label: String

    fun apply(doc: EditDocument): EditDocument
}

// ---------------------------------------------------------------------------
// Composition
// ---------------------------------------------------------------------------

/**
 * Several commands as one (FR-1.2).
 *
 * Importing five videos is one action to the user and must be one entry in history: the
 * alternative — Appending the plan's commands straight through [UndoStack] — makes undo
 * mean \"the last of six steps\" and turns a single mis-tap into six presses. That is the
 * same reasoning spec §7.3 applies to drags (preview while dragging, commit once).
 *
 * It is the commands in order, applied in order, and it labels the entry with what the user
 * did rather than with the machinery: \"Add media\", not \"Add source\".
 *
 * A compound that produces no change is NOT special-cased here — [UndoStack] already
 * declines to push an entry for a command that left the document identical, which is what
 * keeps a fully-rejected import out of the undo history.
 */
data class CompoundCommand(
    override val label: String,
    val commands: List<EditCommand>,
) : EditCommand {

    override fun apply(doc: EditDocument): EditDocument =
        commands.fold(doc) { current, command -> command.apply(current) }
}

// ---------------------------------------------------------------------------
// Media import (FR-1). Needed so a document can be built by commands rather than
// only by hand, which is what lets the command tests start from realistic state.
// ---------------------------------------------------------------------------

/** Adds an imported source. Re-adding an existing id is a no-op. */
data class AddSource(val source: SourceRef) : EditCommand {
    override val label: String get() = "Add media"

    override fun apply(doc: EditDocument): EditDocument = if (doc.sourceById(source.id) != null) {
        doc
    } else {
        doc.copy(sources = doc.sources + source)
    }
}

/**
 * Appends a clip to the end of the timeline (FR-1.2).
 *
 * Append rather than insert-at-index: import always lands last on the lane it
 * targets, and reordering is a separate explicit command.
 *
 * [trackId] is the lane it targets, and it must be a lane the document already
 * has — this command adds a clip, never a track. An unknown id is a no-op, which
 * is what keeps a stale `(track, clip)` pair from a frame the user has already
 * scrolled past out of the document.
 */
data class AppendClip(
    val trackId: String,
    val clipId: String,
    val sourceId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
) : EditCommand {
    override val label: String get() = "Add clip"

    override fun apply(doc: EditDocument): EditDocument {
        val track = doc.trackById(trackId) ?: return doc
        // A clip must point at a live source, or the document stops being renderable.
        if (doc.sourceById(sourceId) == null) return doc
        // Clip ids are unique across the DOCUMENT, not per track: an id that already exists on another
        // lane would make `clipById` ambiguous, and every effect names a clip by id alone.
        if (doc.clipById(clipId) != null) return doc
        if (sourceOutUs - sourceInUs < Clip.MIN_DURATION_US) return doc
        val clip = Clip(
            id = clipId,
            sourceId = sourceId,
            sourceInUs = sourceInUs,
            sourceOutUs = sourceOutUs,
        )
        return doc.withTrackClips(trackId, track.clips + clip)
    }
}

// ---------------------------------------------------------------------------
// CUT stage (FR-2). Ripple-only: deleting or cutting closes the gap, which is
// automatic here because timeline position is derived, never stored.
// ---------------------------------------------------------------------------

/**
 * Trim one or both edges of a clip (FR-2.1). Non-destructive.
 *
 * Clamps to `[0, source.durationUs]` and to [Clip.MIN_DURATION_US]. Clamping rather
 * than rejecting is right for a drag gesture: the UI sends the raw finger position
 * every frame and expects the clip to stop at its limit rather than refuse to move.
 *
 * [trackId] must be the lane the clip is on: a trim is a change to one clip's edges, so a caller that
 * names the wrong lane gets a refusal rather than a clip on a lane the user is not looking at.
 */
data class TrimClip(
    val trackId: String,
    val clipId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
) : EditCommand {
    override val label: String get() = "Trim"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.trackById(trackId)?.clipById(clipId) ?: return doc
        val source = doc.sourceById(clip.sourceId) ?: return doc

        // Fall back to the clip's own bounds when the probe did not report a
        // duration, so a bad probe cannot make the clip untrimmable.
        val upperBound = if (source.durationUs > 0L) source.durationUs else clip.sourceOutUs
        // Guard the coerceIn range: a source shorter than the minimum clip length
        // would otherwise produce min > max and throw.
        val ceiling = upperBound.coerceAtLeast(Clip.MIN_DURATION_US)

        val newIn = sourceInUs.coerceIn(0L, ceiling - Clip.MIN_DURATION_US)
        val newOut = sourceOutUs.coerceIn(newIn + Clip.MIN_DURATION_US, ceiling)

        if (newIn == clip.sourceInUs && newOut == clip.sourceOutUs) return doc
        return doc.withClip(trackId, clip.copy(sourceInUs = newIn, sourceOutUs = newOut))
    }
}

/**
 * Split a clip in two at a source-time point (FR-2.5).
 *
 * [newClipId] is supplied by the caller rather than generated here, so that
 * commands stay pure and comparable -- a command that minted its own id could not
 * be compared, replayed, or asserted on.
 */
data class SplitClip(
    val trackId: String,
    val clipId: String,
    val atSourceUs: Long,
    val newClipId: String,
) : EditCommand {
    override val label: String get() = "Split"

    override fun apply(doc: EditDocument): EditDocument {
        // A split produces TWO clips on the SAME lane: the halves are two pieces of one clip's place in
        // the timeline, so a split that named the wrong lane could not be applied at all.
        val clip = doc.trackById(trackId)?.clipById(clipId) ?: return doc
        // The new id must be fresh; otherwise the split would overwrite a clip.
        if (newClipId == clipId || doc.clipById(newClipId) != null) return doc
        // Outside the clip's own source range there is nothing to split. Guarding
        // here (as CutLeft/CutRight do) keeps the command total: splitAtSource would
        // otherwise hand Clip an inverted range and its validation would throw.
        if (atSourceUs <= clip.sourceInUs || atSourceUs >= clip.sourceOutUs) return doc

        val (left, right) = clip.splitAtSource(atSourceUs, newClipId)
        // Both halves must survive the minimum-duration rule, or this is not a
        // split but a trim -- which is a different command with different intent.
        if (left.sourceDurationUs < Clip.MIN_DURATION_US) return doc
        if (right.sourceDurationUs < Clip.MIN_DURATION_US) return doc

        return doc.replaceClip(trackId, clipId, listOf(left, right))
    }
}

/**
 * Remove everything in the active clip before the playhead (FR-2.2).
 *
 * Shares its arithmetic with [SplitClip] via [Clip.splitAtSource] so there is
 * exactly one code path for the split edge cases (FR-2).
 *
 * **Deliberate divergence from a literal "split + delete".** The surviving tail
 * keeps the original clip id. A literal split would mint a new id for the tail and
 * delete the original, which silently orphans every `EffectScope.Clip(clipId)`
 * pointing at this clip and drops the UI's current selection. The arithmetic is
 * shared; the identity is preserved on purpose.
 */
data class CutLeft(val trackId: String, val clipId: String, val atSourceUs: Long) : EditCommand {
    override val label: String get() = "Cut left"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.trackById(trackId)?.clipById(clipId) ?: return doc
        // Nothing before the playhead to remove.
        if (atSourceUs <= clip.sourceInUs) return doc
        // The surviving tail would be shorter than the minimum: delete instead, as
        // FR-2 specifies for a clip that would go below the floor.
        if (clip.sourceOutUs - atSourceUs < Clip.MIN_DURATION_US) {
            return DeleteClip(trackId, clipId).apply(doc)
        }
        val (_, tail) = clip.splitAtSource(atSourceUs, clip.id)
        return doc.withClip(trackId, tail)
    }
}

/** Remove everything in the active clip after the playhead (FR-2.3). See [CutLeft]. */
data class CutRight(val trackId: String, val clipId: String, val atSourceUs: Long) : EditCommand {
    override val label: String get() = "Cut right"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.trackById(trackId)?.clipById(clipId) ?: return doc
        if (atSourceUs >= clip.sourceOutUs) return doc
        if (atSourceUs - clip.sourceInUs < Clip.MIN_DURATION_US) {
            return DeleteClip(trackId, clipId).apply(doc)
        }
        val (head, _) = clip.splitAtSource(atSourceUs, clip.id)
        return doc.withClip(trackId, head)
    }
}

/**
 * Delete a clip and ripple-close the gap (FR-2.6).
 *
 * Also drops any effect scoped to the deleted clip. Leaving them would violate the
 * invariant that every `EffectScope.Clip` references a live clip (spec §7.2), and
 * the failure would surface much later as effects that never render.
 *
 * Refuses to empty the DOCUMENT: an empty document cannot be rendered and cannot be recovered from by
 * undo in any way the UI exposes, so the invariant `clips.size >= 1` is held here rather than left to
 * callers. Emptying one LANE is a different thing and is allowed — the audio workstream's document is
 * one whose video lane has nothing in it, and a guard per track would forbid exactly that.
 */
data class DeleteClip(val trackId: String, val clipId: String) : EditCommand {
    override val label: String get() = "Delete"

    override fun apply(doc: EditDocument): EditDocument {
        val track = doc.trackById(trackId) ?: return doc
        if (track.clipById(clipId) == null) return doc
        if (doc.clips.size <= 1) return doc
        return doc
            .withTrackClips(trackId, track.clips.filterNot { it.id == clipId })
            .copy(effects = doc.effects.filterNot { it.scope.isScopedTo(clipId) })
    }
}

/**
 * Join two or more source-adjacent clips into one (FR-2.4).
 *
 * Preconditions, all of which make the merge a no-op when unmet:
 *  1. same `sourceId`;
 *  2. source-contiguous -- `right.sourceInUs == left.sourceOutUs`;
 *  3. adjacent on the timeline, with nothing between them;
 *  4. equal `speed` and `reverse`.
 *
 * (1) and (2) are the spec's stated precondition. (3) guards against merging
 * across a clip that sits between them. (4) is stricter than the spec text and is
 * deliberate: clips with different speeds cover different amounts of timeline per
 * unit of source, so merging them would silently change playback of one of them.
 * Refusing is better than quietly altering the edit.
 *
 * The merged clip inherits the *first* clip's properties and spans the union of
 * the source ranges.
 */
data class MergeClips(val trackId: String, val clipIds: List<String>) : EditCommand {
    override val label: String get() = "Merge"

    override fun apply(doc: EditDocument): EditDocument {
        // The run is looked for ON [trackId] and nothing else: "the clips between these two" is a
        // question about one lane, and asking it of the whole document would let a video clip merge
        // with an audio one that happens to sit next to it in the flattened reading.
        val run = doc.mergeRunOf(trackId, clipIds) ?: return doc
        val track = doc.trackById(trackId) ?: return doc

        val merged = run.clips.first().copy(sourceOutUs = run.clips.last().sourceOutUs)
        // Replace the whole run with the merged clip, preserving position.
        val mergedClips = track.clips.toMutableList()
        repeat(run.clips.size) { mergedClips.removeAt(run.startIndex) }
        mergedClips.add(run.startIndex, merged)
        return doc.withTrackClips(trackId, mergedClips)
    }
}

/**
 * Move a clip to a new index (FR-2.7).
 *
 * Ripple is implicit: every other clip's timeline position is derived, so moving
 * one clip is the entire operation.
 *
 * The index is a position ON ONE LANE. A drag along the timeline is a drag along
 * one track's own order, and a clip can no more jump lanes by being dragged than it
 * can by being cut — moving a clip between tracks is a different operation, and one
 * the schema does not offer yet.
 */
data class ReorderClip(val trackId: String, val clipId: String, val toIndex: Int) : EditCommand {
    override val label: String get() = "Reorder"

    override fun apply(doc: EditDocument): EditDocument {
        val track = doc.trackById(trackId) ?: return doc
        val from = track.clips.indexOfFirst { it.id == clipId }
        if (from < 0) return doc
        val to = toIndex.coerceIn(0, track.clips.lastIndex)
        if (from == to) return doc
        val reordered = track.clips.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return doc.withTrackClips(trackId, reordered)
    }
}

/**
 * Duplicate a clip immediately after itself (FR-2.8).
 *
 * The copy lands on the SAME lane as the original, at the position right after it: a duplicate is
 * "that shot again", and a copy that appeared on another track would be a different edit.
 */
data class DuplicateClip(
    val trackId: String,
    val clipId: String,
    val newClipId: String,
) : EditCommand {
    override val label: String get() = "Duplicate"

    override fun apply(doc: EditDocument): EditDocument {
        val track = doc.trackById(trackId) ?: return doc
        val index = track.clips.indexOfFirst { it.id == clipId }
        if (index < 0) return doc
        if (newClipId == clipId || doc.clipById(newClipId) != null) return doc
        val duplicate = track.clips[index].copy(id = newClipId)
        val clips = track.clips.toMutableList()
        clips.add(index + 1, duplicate)
        return doc.withTrackClips(trackId, clips)
    }
}

// ---------------------------------------------------------------------------
// Shared internals
// ---------------------------------------------------------------------------

/**
 * The one place split arithmetic lives.
 *
 * Both halves are produced here so that [SplitClip], [CutLeft] and [CutRight]
 * cannot drift apart on the edge cases (FR-2 requires them to share a code path).
 * Callers decide which halves to keep and whether the result is valid for them.
 *
 * Note for reversed clips: this splits in SOURCE space, so the two halves are the
 * correct two pieces of footage, but their timeline order is reversed relative to
 * source order. Composing split with reverse is a Phase 2 concern (FR-3.9).
 */
private fun Clip.splitAtSource(atSourceUs: Long, rightClipId: String): Pair<Clip, Clip> =
    copy(sourceOutUs = atSourceUs) to copy(id = rightClipId, sourceInUs = atSourceUs)

/** Replaces the clip with [clipId], in place ON ITS OWN TRACK, with [replacements]. */
private fun EditDocument.replaceClip(
    trackId: String,
    clipId: String,
    replacements: List<Clip>,
): EditDocument {
    val clips = trackById(trackId)?.clips ?: return this
    val index = clips.indexOfFirst { it.id == clipId }
    if (index < 0) return this
    val updated = clips.toMutableList()
    updated.removeAt(index)
    updated.addAll(index, replacements)
    return withTrackClips(trackId, updated)
}

/**
 * Replaces the clip with one that has the same id, on [trackId], or leaves the document alone.
 *
 * `internal` rather than private-to-this-file: the Edit stage's commands (AdjustCommands.kt) replace a
 * clip the same way the Cut stage's do, and a second copy of "swap one clip by id" is a second place
 * for the id-matching rule to be got wrong.
 */
internal fun EditDocument.withClip(trackId: String, clip: Clip): EditDocument =
    replaceClip(trackId, clip.id, listOf(clip))

/** True when this scope targets [clipId]. */
internal fun EffectScope.isScopedTo(clipId: String): Boolean =
    this is EffectScope.Clip && this.clipId == clipId
