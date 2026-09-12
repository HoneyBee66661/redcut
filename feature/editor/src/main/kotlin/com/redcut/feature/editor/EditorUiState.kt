package com.redcut.feature.editor

import androidx.compose.runtime.Immutable
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.UndoStack

/**
 * The editor's entire UI state, as ONE immutable value (spec §7.2).
 *
 * The anti-pattern this exists to prevent is named in the spec and was observed in
 * LibreCuts: nine separate `StateFlow`s from one ViewModel permit genuinely inconsistent
 * frames — `canUndo == true` while `project == null`. With one object, an inconsistent
 * frame is not a bug that might happen; it is a value that cannot be constructed.
 *
 * `@Immutable` is a promise to the Compose compiler that every field is stable, which
 * lets it skip recomposition when the state is unchanged. Every field here is a
 * `val` of an immutable type (`EditDocument` is a data class of immutable values).
 *
 * Deliberately absent, because nothing produces them yet: `selection`, `playheadUs`,
 * `playback`, `tool`, `export`, `dialogs` (all in spec §7.2's sketch). Each arrives with
 * the phase that gives it meaning — a field added empty now would be a field every
 * screen has to pass a default for, and the first real one would have to argue with it.
 */
@Immutable
data class EditorUiState(
    val document: EditDocument,
    val history: HistoryState,
    val stage: Stage,
    /**
     * Where the playhead is, in microseconds from the start of the timeline (§7.2).
     *
     * Not part of the document and not part of history: moving the playhead is not an edit, so
     * undo must not step through it. The same distinction the stage already has.
     */
    val playheadUs: Long = 0L,
    /** What the user has selected (§7.2). */
    val selection: Selection = Selection.None,
    /**
     * What the user is doing right now (§7.2): idle, or a trim in flight.
     *
     * Not view state like the timeline's zoom: a trim in flight has ALREADY changed the document as
     * a preview, so the screen that showed the document without this could not explain why a clip is
     * shorter than it was a moment ago.
     */
    val tool: ToolState = ToolState.Idle,
    /**
     * What the last import did, or null when nothing has been imported yet (FR-1.4).
     *
     * Part of the ONE state object rather than a second `StateFlow`, for the reason §7.2
     * gives: a rejection message that could arrive while the document has already moved on
     * is exactly the inconsistent frame this type exists to prevent. The report is cleared
     * by the user dismissing it, not by a timer, so an import that half-failed cannot
     * disappear before it has been read.
     */
    val import: ImportReport? = null,
) {
    /** The document's state identity, and the recompilation trigger (spec §1.1, §8.1). */
    val revision: Long get() = document.revision
}

/**
 * Whether the history can move, and what the next move would be called.
 *
 * [Ready] carries the flags rather than the stack so the UI never holds the mutable
 * `UndoStack`: spec §7.3's "Undo Trim" label is a string, and a screen that could call
 * `undo()` itself would be a second mutation gateway.
 *
 * [Busy] exists because export and native reconfiguration are asynchronous; the UI must
 * disable Undo rather than race them. Nothing produces it yet — Phase 4.1 is the first
 * producer — and it is here now so the `when` that renders the toolbar is already total.
 */
sealed interface HistoryState {
    data class Ready(
        val canUndo: Boolean,
        val canRedo: Boolean,
        val topLabel: String?,
    ) : HistoryState

    data object Busy : HistoryState
}

/**
 * Reads the current state out of the stack.
 *
 * A function rather than a property so it is a fresh value every time: the flags change
 * on every mutation, and a cached one would be exactly the "inconsistent frame" this
 * state object exists to prevent.
 */
internal fun UndoStack.toUiState(
    stage: Stage,
    playheadUs: Long = 0L,
    selection: Selection = Selection.None,
    tool: ToolState = ToolState.Idle,
    import: ImportReport? = null,
): EditorUiState = EditorUiState(
    document = current,
    history = HistoryState.Ready(
        canUndo = canUndo,
        canRedo = canRedo,
        topLabel = undoLabel,
    ),
    stage = stage,
    playheadUs = playheadUs,
    selection = selection,
    tool = tool,
    import = import,
)

/**
 * A trim in flight for a clip that no longer exists is not a tool state; it is a lost gesture.
 *
 * Same rule as [Selection.reconciledWith], and it matters more here: a stale [ToolState.Trimming]
 * would keep the stage body showing a frame at an edge that is not on screen, and the next
 * [EditorIntent.UpdateTrim] would preview a command against a clip the document does not have.
 */
internal fun ToolState.reconciledWith(clipIds: List<String>): ToolState =
    if (this is ToolState.Trimming && clipId !in clipIds) ToolState.Idle else this

/**
 * Switches stages.
 *
 * A `copy` and nothing else: spec §7.1 — "Stage switching is instant and free ... it
 * does not recompile the document, because there is nothing to recompile". The document
 * (and therefore its `revision`) is carried over untouched, which is what the test
 * asserts, because "the stage change bumped the revision" would mean every stage tap
 * triggered a recompile of the whole timeline.
 */
internal fun EditorUiState.withStage(stage: Stage): EditorUiState = copy(stage = stage)

/**
 * The outcome of one import, as the UI needs to tell it (FR-1.2, FR-1.4).
 *
 * Counts and reasons, not the sources themselves: the clips are already in the document,
 * and a report that carried them too would be a second copy of state the UI can read from
 * `document`.
 *
 * [rejected] holds both kinds of refusal — files the policy turned down (a codec, a
 * duration) and files that could not be read at all — because the user made one gesture and
 * is owed one list. The distinction lives in the rejection types, not in two lists.
 */
@Immutable
data class ImportReport(
    val importedCount: Int,
    val rejected: List<ImportRejection>,
) {
    val hasRejections: Boolean get() = rejected.isNotEmpty()

    /** The user-facing lines, in the order the files were selected. */
    val messages: List<String> get() = rejected.map { it.message }

    companion object {
        /** Nothing was imported and nothing was refused — e.g. an empty selection. */
        val EMPTY = ImportReport(importedCount = 0, rejected = emptyList())
    }
}
