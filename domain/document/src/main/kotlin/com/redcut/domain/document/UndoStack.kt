package com.redcut.domain.document

/**
 * Snapshot-based undo/redo over an immutable [EditDocument] (spec §7.3).
 *
 * ### Why snapshots rather than inverse commands
 *
 * `EditDocument` is immutable and small -- a list of clips plus a list of effects --
 * so a snapshot is a pointer copy, and the whole history costs a few hundred bytes.
 * Inverse-command undo would be several times the code and several times the bugs
 * for no benefit at this state size. If documents ever grow large enough for this
 * to matter, the fix is structural sharing, not inverse commands.
 *
 * ### Coalescing
 *
 * A trim drag emits a delta every frame. Pushing all of them would bury the rest of
 * the user's history and make Undo appear broken -- the classic symptom is that one
 * gesture needs fifty taps to reverse.
 *
 * The fix is a two-phase API:
 *
 * ```
 * stack.preview(TrimClip(id, in, out))   // called per frame; no history entry
 * stack.preview(TrimClip(id, in, out))   // ...
 * stack.commit()                         // gesture ended: ONE history entry
 * ```
 *
 * [preview] moves the live document without touching history; [commit] records a
 * single entry spanning the whole gesture; [abortPreview] snaps back for a cancelled
 * gesture. [execute] is the single-step form for every other action.
 *
 * ### Revision (spec §1.1, §8.1)
 *
 * This class is the single mutation gateway, and therefore the only thing that
 * advances [EditDocument.revision] -- the stamp that identifies the current document
 * state and triggers recompilation (§8.1). An operation that changes content advances
 * the revision by exactly one; [undo], [redo] and [abortPreview] restore the revision
 * that belonged to the document being restored, because a restored document *is* that
 * earlier state; a no-op operation leaves the revision alone; and commands never touch
 * it. See [commit] and [reset] for the two places that deliberately add an extra stamp.
 *
 * The stack is NOT thread-safe. It is owned by one ViewModel and touched only from
 * the main dispatcher.
 */
class UndoStack(
    initial: EditDocument,
    /** FR-6.5: bounded depth, dropping the oldest. */
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit > 0) { "limit must be > 0, was $limit" }
    }

    /**
     * A past or future document, paired with the label of the command that moves
     * *away* from it -- which is what makes "Undo Trim" and "Redo Trim" nameable.
     */
    private data class Entry(val doc: EditDocument, val label: String)

    // Scoped suppression, with the reason attached: the rule exists to catch
    // `class Foo { val foo = ... }` where the member shadows the type's own name and
    // confuses readers. Here the pair is `undoStack` / `redoStack` — the names ARE
    // the design, they mirror the public `canUndo` / `canRedo` surface, and renaming
    // them to satisfy a count would make the two deques harder to tell apart.
    @Suppress("MemberNameEqualsClassName")
    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** Set while a gesture is previewing; the document the gesture started from. */
    private var previewBase: EditDocument? = null
    private var previewLabel: String? = null

    /** The live document. Every mutation goes through this class. */
    var current: EditDocument = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    /** True between the first [preview] and its [commit] or [abortPreview]. */
    val isPreviewing: Boolean get() = previewBase != null

    /** Label of the command [undo] would reverse, or null. For "Undo <label>". */
    val undoLabel: String? get() = undoStack.lastOrNull()?.label

    /** Label of the command [redo] would reapply, or null. For "Redo <label>". */
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /**
     * Applies [command] and records one history entry.
     *
     * A command that changes nothing -- a merge whose preconditions fail, a trim
     * clamped back to where it started -- records **no** entry. Otherwise a no-op
     * tap would consume one of the 50 slots and require an undo tap to get past.
     * "Changes nothing" is judged on CONTENT, not on `EditDocument.equals`: the
     * revision is part of equality, so a whole-document comparison would make a
     * command that restores an earlier state look like a fresh change.
     *
     * Otherwise the result becomes the new state and carries the next revision --
     * one mutation, one stamp, which is what §8.1 recompiles on.
     *
     * A command that would touch a LOCKED lane never runs at all: [EditDocument.after] refuses it, and
     * the refusal arrives here as the document it already was. So it records no entry and advances no
     * revision, which is the same handling a no-op gets and is meant to be: a refused command is not an
     * edit, and an entry for one that never happened costs the user two taps to get past. A refused
     * command also leaves the redo history standing, because nothing about the document changed.
     *
     * Any in-flight preview is abandoned first: [execute] is the discrete-action
     * path, and mixing it into a gesture would attribute the gesture's partial
     * state to the wrong history entry.
     */
    fun execute(command: EditCommand): EditDocument {
        discardPreview()
        val next = current.after(command)
        if (next.sameContentAs(current)) return current
        pushUndo(Entry(current, command.label))
        redoStack.clear()
        current = next.copy(revision = current.revision + 1)
        return current
    }

    /**
     * Applies [command] to the live document WITHOUT recording history.
     *
     * Intended for continuous gestures. Must be followed by [commit] (gesture
     * completed) or [abortPreview] (gesture cancelled). Repeated calls extend the
     * same gesture and are collapsed into a single entry.
     *
     * A frame that changes content advances the revision, exactly as [execute] would:
     * a drag emits one delta per frame, so the revision legitimately advances per
     * frame. What §8.1 debounces is the PREVIEW REBUILD (120 ms during drags), never
     * the revision. A frame that changes nothing leaves the revision untouched.
     *
     * A frame the lock refuses is not a frame: [EditDocument.after] hands back the document unchanged, so
     * a gesture that only ever touched a locked lane commits to nothing at all when it ends -- see
     * [commit].
     */
    fun preview(command: EditCommand): EditDocument {
        if (previewBase == null) previewBase = current
        previewLabel = command.label
        val next = current.after(command)
        if (next.sameContentAs(current)) return current
        current = next.copy(revision = current.revision + 1)
        return current
    }

    /**
     * Ends a preview gesture, recording the whole thing as one history entry.
     *
     * A gesture that ended where it started -- drag out and back -- records nothing,
     * so it does not consume a slot or require an undo tap. The base document is
     * restored wholesale, which is what also rolls the revision back: the state the
     * user is looking at is once again the pre-gesture state, stamp and all.
     *
     * Otherwise ONE entry is pushed for the whole gesture, and the revision advances
     * once more. That extra stamp is deliberate: commit is the boundary where a
     * transient preview becomes a recorded state, so the committed state must carry a
     * stamp that no preview frame carried -- otherwise a redo or a replay landing on
     * it could be mistaken for the frame it happens to equal. Under §8.1 compilation
     * is under 1 ms and the preview rebuild is debounced, so the extra recompile at
     * gesture end is free.
     */
    fun commit(): EditDocument {
        val base = previewBase ?: return current
        val label = previewLabel
        previewBase = null
        previewLabel = null
        if (base.sameContentAs(current)) {
            current = base
            return current
        }
        pushUndo(Entry(base, label ?: DEFAULT_LABEL))
        redoStack.clear()
        current = current.copy(revision = current.revision + 1)
        return current
    }

    /** Ends a preview gesture, restoring the document the gesture began from. */
    fun abortPreview(): EditDocument {
        val base = previewBase ?: return current
        previewBase = null
        previewLabel = null
        current = base
        return current
    }

    /** Reverses the most recent entry. Returns the unchanged document if there is none. */
    fun undo(): EditDocument {
        discardPreview()
        val entry = undoStack.removeLastOrNull() ?: return current
        redoStack.addLast(Entry(current, entry.label))
        current = entry.doc
        return current
    }

    /** Reapplies the most recently undone entry. */
    fun redo(): EditDocument {
        discardPreview()
        val entry = redoStack.removeLastOrNull() ?: return current
        pushUndo(Entry(current, entry.label))
        current = entry.doc
        return current
    }

    /** Drops all history, keeping the current document. For project close. */
    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        discardPreview()
    }

    /**
     * Replaces the document and drops all history. For loading a project.
     *
     * The revision advances rather than adopting the incoming document's own stamp:
     * loading a project must not be able to collide with the stamp of the document it
     * replaced, or the renderer would see an unchanged revision, skip the recompile,
     * and keep showing the old project.
     */
    fun reset(document: EditDocument) {
        clearHistory()
        current = document.copy(revision = current.revision + 1)
    }

    private fun pushUndo(entry: Entry) {
        undoStack.addLast(entry)
        // Drop the oldest beyond the bound (FR-6.5).
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    /** An abandoned preview is discarded, not recorded -- see [execute]. */
    private fun discardPreview() {
        previewBase = null
        previewLabel = null
    }

    companion object {
        /** FR-6.5. */
        const val DEFAULT_LIMIT: Int = 50

        private const val DEFAULT_LABEL = "Edit"
    }
}

/**
 * True when the two documents hold the same *content*, ignoring
 * [EditDocument.revision].
 *
 * Revision participates in `EditDocument.equals`, so "did this change anything?" can
 * no longer be answered by comparing whole documents: a gesture that returns to its
 * starting content would differ only in its stamp and be recorded as a change. Every
 * no-op decision in [UndoStack] therefore goes through this helper.
 */
private fun EditDocument.sameContentAs(other: EditDocument): Boolean =
    copy(revision = 0L) == other.copy(revision = 0L)
