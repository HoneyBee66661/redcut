package com.redcut.feature.editor

import androidx.lifecycle.ViewModel
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.UndoStack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The editor's state holder (spec §7.2, §7.3).
 *
 * It owns the single mutation gateway — `:domain:document`'s [UndoStack] — and publishes
 * one immutable [EditorUiState]. Nothing else in the app may hold an `UndoStack`: the
 * document's own tests are what make the stack correct, and a second owner would be a
 * second history.
 *
 * Note what is NOT here: no `viewModelScope`, no coroutine, no dispatcher. Every
 * operation today is synchronous and pure, and adding a coroutine because a ViewModel
 * "should" have one is how a state holder becomes impossible to test. The dispatchers
 * provided in `:app/di` are for the operators that actually suspend (probing, exporting).
 *
 * The document is a placeholder, and deliberately labelled as one: creating a project is
 * Phase 4.7 and importing media is Phase 1.3, so what exists before either is an empty
 * document with a name. `UndoStack` requires one, and having a real document here is what
 * makes the undo/redo path wired rather than stubbed.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val logger: RedcutLogger,
) : ViewModel() {

    private val history = UndoStack(
        initial = EditDocument(id = UNTITLED_ID, name = UNTITLED_NAME),
    )

    private val _state = MutableStateFlow(history.toUiState(stage = Stage.Cut))

    /** The single source of truth the UI renders. */
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.SelectStage -> {
                logger.d(TAG, "stage -> ${intent.stage.label}")
                _state.value = _state.value.withStage(intent.stage)
            }

            EditorIntent.Undo -> {
                logger.d(TAG, "undo")
                publish()
            }

            EditorIntent.Redo -> {
                logger.d(TAG, "redo")
                publish()
            }
        }
    }

    /**
     * Re-reads the state from the stack, keeping the stage.
     *
     * The stage is not history: undoing a trim must not also undo "the user is looking at
     * the Effect stage", which is what would happen if the whole UI state were snapshotted
     * with the document.
     */
    private fun publish() {
        _state.value = history.toUiState(stage = _state.value.stage)
    }

    private companion object {
        const val TAG = "EditorViewModel"

        /**
         * Stable ids for the placeholder project. Not random: a random id here would make
         * every process start a different document, so a later autosave (spec §10.3) would
         * see a new project on every launch and Phase 4.7's file-name collision check
         * would be untestable against this placeholder.
         */
        const val UNTITLED_ID = "untitled"
        const val UNTITLED_NAME = "Untitled project"
    }
}
