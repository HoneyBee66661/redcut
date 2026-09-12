package com.redcut.feature.editor

import androidx.compose.runtime.Immutable
import com.redcut.domain.document.EditDocument
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
internal fun UndoStack.toUiState(stage: Stage): EditorUiState = EditorUiState(
    document = current,
    history = HistoryState.Ready(
        canUndo = canUndo,
        canRedo = canRedo,
        topLabel = undoLabel,
    ),
    stage = stage,
)

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
