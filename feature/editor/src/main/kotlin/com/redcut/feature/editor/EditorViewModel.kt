package com.redcut.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.redcut.core.common.IdSource
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.MediaSourceReader
import com.redcut.core.media.SourceReadResult
import com.redcut.domain.document.CompoundCommand
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.UndoStack
import com.redcut.domain.document.planImport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The editor's state holder (spec §7.2, §7.3).
 *
 * It owns the single mutation gateway — `:domain:document`'s [UndoStack] — and publishes one
 * immutable [EditorUiState]. Nothing else in the app may hold an `UndoStack`: the document's
 * own tests are what make the stack correct, and a second owner would be a second history.
 *
 * ### What is NOT here, and the one thing that now is
 *
 * Every edit operation is synchronous and pure, and that is still true: stage switching
 * touches nothing but UI state, and undo/redo are stack operations. Import is the exception,
 * and it is a deliberate one — reading a content URI, probing a container and taking a
 * persistable permission are I/O, so [importMedia] is the only suspending path in this class
 * and the only reason it has a dispatcher. It is injected rather than reached for
 * (`Dispatchers.IO` would make the class untestable on the JVM, which is where the rest of it
 * is verified), and the work is `launch(io)` on `viewModelScope`, so a screen that leaves
 * mid-import cancels it instead of leaking a probe for a screen nobody is looking at.
 *
 * ### Why the import is a plan and then a compound
 *
 * [planImport] (pure, tested in the fast tier) decides which files may enter the document and
 * yields the commands; this class carries them through the stack as ONE [CompoundCommand], so
 * a five-video import is one undo entry rather than ten. The ids come from an injected
 * [IdSource] because commands never mint their own.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val logger: RedcutLogger,
    private val sourceReader: MediaSourceReader,
    private val ids: IdSource,
    // `@param:` for the same reason as in :core:media — Kotlin 2.2 warns that a bare
    // annotation on a constructor property will also apply to the field, and CI compiles
    // with `-Werror`. Stating the target we mean costs one token and cannot regress.
    @param:IoDispatcher private val io: CoroutineDispatcher,
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

            // Both of these actually MOVE the stack. Until Phase 1.3 the intent existed and
            // the wiring did not: `Undo` re-read the state without undoing anything, so the
            // button was a no-op that looked wired. The import test caught it — an import is
            // the first thing a test can put on the stack and then take off again, which is
            // why "the stage survives an undo" passed on an empty history and proved nothing.
            EditorIntent.Undo -> {
                logger.d(TAG, "undo")
                history.undo()
                publish()
            }

            EditorIntent.Redo -> {
                logger.d(TAG, "redo")
                history.redo()
                publish()
            }

            is EditorIntent.ImportMedia -> importMedia(intent.uris)

            EditorIntent.DismissImport -> _state.value = _state.value.copy(import = null)
        }
    }

    /**
     * Reads, assesses and appends (FR-1.1–1.5).
     *
     * The two rejection sources are merged into one report and kept in selection order only
     * as far as each list is concerned: the user picked files, some could not be opened and
     * some were refused, and both explanations belong in the same message.
     *
     * Note the order of operations: the document is only touched after the whole batch has
     * been read. A half-import that appended three clips and then failed on the fourth is a
     * state the user did not ask for and cannot undo in one action.
     */
    private fun importMedia(uris: List<String>) {
        if (uris.isEmpty()) return
        logger.d(TAG, "import ${uris.size} source(s)")

        viewModelScope.launch(io) {
            val read = sourceReader.read(uris)
            val probed = read.filterIsInstance<SourceReadResult.Read>().map { it.source }
            val unreadable = read
                .filterIsInstance<SourceReadResult.Unreadable>()
                .map { it.rejection as ImportRejection }

            val plan = planImport(
                probed = probed,
                sourceId = { "src-${ids.next()}" },
                clipId = { "clip-${ids.next()}" },
            )

            if (plan.hasImports) {
                history.execute(CompoundCommand(IMPORT_LABEL, plan.commands))
            }

            val report = ImportReport(
                importedCount = plan.accepted.size,
                rejected = unreadable + plan.rejected,
            )
            logger.d(TAG, "import: ${report.importedCount} added, ${report.rejected.size} refused")
            publish(import = report)
        }
    }

    /**
     * Re-reads the state from the stack, keeping the stage and the import report.
     *
     * Neither the stage nor the report is history: undoing a trim must not also undo "the
     * user is looking at the Effect stage", and it must not erase the explanation of why
     * one of four files was refused.
     */
    private fun publish(import: ImportReport? = _state.value.import) {
        _state.value = history.toUiState(stage = _state.value.stage, import = import)
    }

    private companion object {
        const val TAG = "EditorViewModel"

        /** What the undo entry says (spec §7.3's "Undo <label>"). */
        const val IMPORT_LABEL = "Add media"

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
