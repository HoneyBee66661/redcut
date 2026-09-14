package com.redcut.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.redcut.core.common.IdSource
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.MediaSourceReader
import com.redcut.core.media.SourceReadResult
import com.redcut.domain.document.Clip
import com.redcut.domain.document.ClipAdjustment
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.CompoundCommand
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.FrameStep
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.RenameDocument
import com.redcut.domain.document.ReorderClip
import com.redcut.domain.document.SetTransform
import com.redcut.domain.document.TrimClip
import com.redcut.domain.document.UndoStack
import com.redcut.domain.document.ViewportRect
import com.redcut.domain.document.adjust
import com.redcut.domain.document.commandFor
import com.redcut.domain.document.planImport
import com.redcut.domain.document.steppedPlayheadUs
import com.redcut.domain.document.timelineDurationUs
import com.redcut.domain.document.trimmedTo
import com.redcut.domain.project.ProjectStore
import com.redcut.domain.project.SavedProject
import com.redcut.domain.project.nextUntitledName
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
    private val images: EditorImages,
    private val projects: ProjectStore,
    private val ids: IdSource,
    // `@param:` for the same reason as in :core:media — Kotlin 2.2 warns that a bare
    // annotation on a constructor property will also apply to the field, and CI compiles
    // with `-Werror`. Stating the target we mean costs one token and cannot regress.
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /**
     * A `var`, not a `val`, for one reason: opening a saved project starts a NEW history. The comment
     * above about a second owner still holds — this is the only owner, and it is replaced only by a
     * document that deserves no undo stack of its own.
     */
    private var history = UndoStack(
        initial = EditDocument(id = UNTITLED_ID, name = UNTITLED_NAME),
    )

    /**
     * The name this project is saved under, or null while it has never been saved.
     *
     * Held here rather than read from the document, because "has this been named yet?" is not a question
     * the document can answer: it starts with a placeholder name, and a placeholder is not a name.
     */
    private var projectName: String? = null

    private val _state = MutableStateFlow(history.toUiState(stage = Stage.Cut))

    /**
     * Reopens the project the user was last in (the device pass's "my clip disappeared").
     *
     * `init` rather than a screen-side call, so every entry point to the editor — back navigation, process
     * recreation, a future deep link — gets the same behaviour without remembering to ask for it. A fresh
     * history is correct here for the same reason the name is: a project the user has just opened has
     * nothing to undo, and inheriting the previous session's stack would let them undo an edit they never
     * made.
     */
    init {
        viewModelScope.launch(io) {
            val saved = projects.latest() ?: return@launch
            history = UndoStack(initial = saved.document)
            projectName = saved.name
            logger.d(TAG, "reopened ${saved.name}: ${saved.document.clips.size} clip(s)")
            publish()
        }
    }

    /** The single source of truth the UI renders. */
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /**
     * The dispatcher, in two branches.
     *
     * It used to be one `when` over fourteen intents, which `detekt` measured and a reader had to
     * hold. Splitting by KIND rather than by size is the point: an [EditorIntent.Edit] is a document
     * change that belongs on the history stack, a [EditorIntent.View] is a change to where the user is
     * LOOKING, and the difference is the bug class this editor is careful about (an undo that also
     * rewinds the playhead; a stage tap that lands in the undo stack). No `else` anywhere, so a new
     * intent cannot fall outside both handlers.
     */
    fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.Edit -> applyEdit(intent)
            is EditorIntent.View -> updateView(intent)
        }
    }

    /**
     * Document edits: everything that ends up on the history stack, dispatched by FAMILY.
     *
     * The same idea as [onIntent], one level down, and for the same reason: three workstreams' intents
     * arrive here now — the timeline's cuts, gestures and reorder, the preview viewport, and (through
     * [EditorIntent.View]) the export sheet. A flat list of ten branches is a switch `detekt` measures
     * and a reader has to hold; a family is a sub-interface of [EditorIntent.Edit], so its branch is one
     * line and the family's own `when` is exhaustive over the members that belong to it.
     *
     * Five families pass through: the two drag gestures, the Cut stage's tools at the playhead, the drag
     * that rearranges one lane, and the viewport. An import is not a family — one intent, one branch,
     * one handler that was already named for it.
     *
     * There is still no `else` anywhere: a new intent joins a family (and the compiler makes that
     * family's `when` handle it) or takes a branch of its own, and either way it cannot fall outside
     * both. That is what makes a fourth family cheap — its intents, its helper, one line here.
     */
    private fun applyEdit(intent: EditorIntent.Edit) {
        when (intent) {
            // The two drag gestures. Each family's own `when` covers its four moments exhaustively.
            is EditorIntent.TrimGesture -> applyTrimGesture(intent)
            is EditorIntent.AdjustGesture -> applyAdjustGesture(intent)

            // The Cut stage's tools at the playhead, and the drag that rearranges one lane.
            is EditorIntent.ApplyCut -> applyCut(intent.tool)
            is EditorIntent.ApplyReorder -> applyReorder(intent.clipId, intent.toIndex)

            // The preview viewport (WS F): the crop and zoom of the SELECTED clip.
            is EditorIntent.SetViewport -> applyViewport(intent)

            is EditorIntent.ImportMedia -> importMedia(intent.uris)
        }
    }

    /**
     * The trim gesture's four moments (FR-2.1): the finger went down on an edge, moved, lifted, or the
     * gesture was abandoned.
     *
     * The drag PREVIEWS and only the lift records a history entry — that pairing is what makes these
     * four one family rather than four unrelated edits, and [applyAdjustGesture] has the same shape.
     */
    private fun applyTrimGesture(intent: EditorIntent.TrimGesture) {
        when (intent) {
            is EditorIntent.BeginTrim -> beginTrim(intent.clipId, intent.edge, intent.sourceTimeUs)
            is EditorIntent.UpdateTrim -> updateTrim(intent.sourceTimeUs)
            EditorIntent.EndTrim -> endGesture()
            EditorIntent.CancelTrim -> cancelGesture()
        }
    }

    /** The adjust gesture's four moments (FR-3.1–3.4, 3.9). See [applyTrimGesture]. */
    private fun applyAdjustGesture(intent: EditorIntent.AdjustGesture) {
        when (intent) {
            is EditorIntent.BeginAdjust -> beginAdjust(intent.clipId, intent.adjustment)
            is EditorIntent.UpdateAdjust -> updateAdjust(intent.value)
            EditorIntent.EndAdjust -> endGesture()
            EditorIntent.CancelAdjust -> cancelGesture()
        }
    }

    /**
     * Moves a clip to a new slot (FR-2.7).
     *
     * `ReorderClip` clamps the index and refuses an unknown clip, so there is nothing to validate here —
     * and the marker the user dragged to came from the same arithmetic the command will apply, which is
     * what stops the clip landing somewhere they did not point at.
     *
     * The lane comes from the document, the way every command in this file obtains one: the intent
     * carries a clip id because the SELECTION still does, and a `(track, clip)` pair is the timeline
     * work's next step. A clip no lane holds has nothing to reorder, so this is a no-op.
     */
    private fun applyReorder(clipId: String, toIndex: Int) {
        logger.d(TAG, "reorder $clipId -> $toIndex")
        val trackId = history.current.trackIdOf(clipId) ?: return
        history.execute(ReorderClip(trackId = trackId, clipId = clipId, toIndex = toIndex))
        _state.value = _state.value.copy(selection = Selection.Clip(clipId))
        autosave()
        publish()
    }

    /**
     * The preview viewport (spec UI revision 2, §WS F / Task F4): the crop and zoom of the SELECTED clip.
     *
     * The guards are the feature's rules rather than defensive noise: the viewport is INERT with nothing
     * selected, and a gesture that lands on the value the clip already has must not put a no-op on the
     * history stack. What a legal rect is, and how it folds into the clip's transform, is the domain's
     * ([ViewportRect]) — this function turns a gesture into a command and nothing else.
     */
    private fun applyViewport(intent: EditorIntent.SetViewport) {
        val clipId = (_state.value.selection as? Selection.Clip)?.clipId ?: return
        val clip = clipOf(clipId) ?: return
        val trackId = history.current.trackIdOf(clip.id) ?: return
        val updatedRect = ViewportRect(
            centerX = intent.centerX,
            centerY = intent.centerY,
            zoom = intent.zoom,
            canvasSpec = history.current.canvas,
        ).clamped()
        val newTransform = updatedRect.toTransformSpec(base = clip.transform)
        if (clip.transform == newTransform) return
        logger.d(TAG, "viewport ${intent.centerX}, ${intent.centerY} @ ${intent.zoom}x")
        history.execute(
            SetTransform(trackId = trackId, clipId = clipId, transform = newTransform),
        )
        autosave()
        publish()
    }

    /**
     * Marks a control as being dragged, and selects its clip.
     *
     * No command is previewed yet: a drag that has not moved the slider has not changed anything, and
     * previewing the value it already has would put a no-op on the history the moment the finger went
     * down. The first [updateAdjust] is what starts the preview.
     */
    private fun beginAdjust(clipId: String, adjustment: ClipAdjustment) {
        if (history.current.clipById(clipId) == null) return
        logger.d(TAG, "adjust ${adjustment.name.lowercase()} of $clipId")
        _state.value = _state.value.copy(
            tool = ToolState.Adjusting(clipId, adjustment),
            selection = Selection.Clip(clipId),
        )
        publish()
    }

    /**
     * One frame of a slider drag: preview, so the document — and therefore the preview and the timeline —
     * follows the finger, and `UndoStack` collapses the whole drag into a single entry on commit.
     */
    private fun updateAdjust(value: Float) {
        val adjusting = (_state.value.tool as? ToolState.Adjusting) ?: return
        val command = history.current
            .adjust(adjusting.clipId, adjusting.adjustment, value) ?: return
        history.preview(command)
        publish()
    }

    /**
     * Ends whichever gesture is open: a trim drag or a slider drag, committing it as ONE entry.
     *
     * One function for both because the lifecycle is the same one — a preview is open, the finger has
     * lifted, and what the document holds right now becomes the edit. The undo label comes from the
     * command that was previewed, so nothing here needs to know WHICH gesture it is closing; and a
     * gesture that never moved anything commits nothing, because `UndoStack` refuses to record a
     * command that changed nothing.
     */
    private fun endGesture() {
        if (_state.value.tool is ToolState.Idle) return
        history.commit()
        autosave()
        _state.value = _state.value.copy(tool = ToolState.Idle)
        publish()
    }

    /**
     * Abandons whichever gesture is open: the document goes back to what it held before the finger went
     * down. The same pairing as [endGesture], and for the same reason.
     */
    private fun cancelGesture() {
        if (_state.value.tool is ToolState.Idle) return
        history.abortPreview()
        _state.value = _state.value.copy(tool = ToolState.Idle)
        publish()
    }

    /**
     * View changes: stage, playhead, selection, history navigation.
     *
     * None of these touches the document, and none is undoable — which is why they are all here and
     * not scattered through the edit path.
     */
    private fun updateView(intent: EditorIntent.View) {
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
                autosave()
                publish()
            }

            EditorIntent.Redo -> {
                logger.d(TAG, "redo")
                history.redo()
                autosave()
                publish()
            }

            is EditorIntent.SetPlayhead -> {
                val limit = history.current.timelineDurationUs
                _state.value = _state.value.copy(playheadUs = intent.us.coerceIn(0L, limit))
            }

            is EditorIntent.StepPlayhead -> stepPlayhead(intent.step)

            is EditorIntent.SelectClip -> selectClip(intent.clipId)

            EditorIntent.ClearSelection ->
                _state.value = _state.value.copy(selection = Selection.None)

            EditorIntent.DismissImport -> _state.value = _state.value.copy(import = null)

            // The export sheet's three, all view state. Inlined rather than given a private function each
            // because detekt's function-count limit is what surfaced the last one (see ApplyReorder), and
            // the logic behind each — what a resolution may be, what an empty document means — lives in
            // EditorUiState beside `withStage`, where a plain JVM test reaches it without a ViewModel.
            EditorIntent.OpenExport ->
                _state.value = _state.value.withExportOpened(history.current.canvas)

            EditorIntent.DismissExport -> _state.value = _state.value.copy(exportSheet = null)

            is EditorIntent.SetExportResolution ->
                _state.value = _state.value.withExportResolution(intent.resolution)
        }
    }

    /**
     * Moves the playhead one frame (FR-2.9).
     *
     * The frame's length comes from the document, because it belongs to the clip under the playhead —
     * a 2x clip's timeline frame is half its source frame, and a step computed here from a constant
     * would be wrong by more the faster the clip plays.
     */
    private fun stepPlayhead(step: FrameStep) {
        val moved = history.current.steppedPlayheadUs(_state.value.playheadUs, step)
        _state.value = _state.value.copy(playheadUs = moved)
    }

    /**
     * Runs a Cut tool at the playhead (FR-2.2–2.6).
     *
     * The document decides everything: `commandFor` returns null exactly when the tool is not
     * available, so this handler has no rules of its own to keep in step with the domain — and the UI
     * asks `availabilityFor` the same question to decide whether to enable the button. One rule, two
     * readers.
     *
     * A discrete command rather than a preview: unlike a trim drag, a cut happens once and is either
     * wanted or undone, so it goes straight onto the stack as one entry (§7.3's "Undo Split").
     */
    private fun applyCut(tool: CutTool) {
        val command = history.current.commandFor(tool, _state.value.playheadUs) { ids.next() }
        if (command == null) {
            logger.d(
                TAG,
                "cut ${tool.name.lowercase()} is not available at ${_state.value.playheadUs}",
            )
            return
        }
        logger.d(TAG, "cut ${tool.name.lowercase()}")
        history.execute(command)
        autosave()
        publish()
    }

    /**
     * Starts a trim gesture (FR-2.1).
     *
     * A preview, not a command: the whole drag is ONE history entry (§7.3's "Undo Trim"), and the
     * document changes on every frame of the gesture so the timeline and the stage body follow the
     * finger live.
     *
     * The clip is looked up fresh rather than trusted from the intent: the id came from a hit test
     * against a frame the user saw, and a clip deleted since then (an undo, a ripple) must not start
     * a gesture against nothing.
     */
    private fun beginTrim(clipId: String, edge: ClipEdge, sourceTimeUs: Long) {
        val clip = clipOf(clipId) ?: return
        val command = trimCommandFor(clip, edge, sourceTimeUs) ?: return
        logger.d(TAG, "trim ${edge.name.lowercase()} of $clipId to $sourceTimeUs")
        history.preview(command)
        _state.value = _state.value.copy(
            tool = ToolState.Trimming(clipId = clipId, edge = edge, sourceTimeUs = sourceTimeUs),
        )
        publish()
    }

    /** The drag moved. Ignored when no trim is in flight, which is not an error: taps race drags. */
    private fun updateTrim(sourceTimeUs: Long) {
        val trimming = _state.value.tool as? ToolState.Trimming ?: return
        val clip = clipOf(trimming.clipId) ?: return
        // Rebuilt from the CURRENT clip on every frame. That is what makes the held edge invariant:
        // the drag value only ever moves the edge the gesture started on, and the other end keeps
        // whatever the last preview put there.
        val command = trimCommandFor(clip, trimming.edge, sourceTimeUs) ?: return
        history.preview(command)
        _state.value = _state.value.copy(tool = trimming.copy(sourceTimeUs = sourceTimeUs))
        publish()
    }

    /**
     * The command a trim drag means.
     *
     * `trimmedTo` gives the INTENT (one edge moves), and `TrimClip` owns the clamping — so a drag past
     * the end of the source is recorded as the user's intent and applied as the limit. The UI learns
     * what it actually got by reading the document back, not by duplicating the rule.
     */
    private fun trimCommandFor(clip: Clip, edge: ClipEdge, sourceTimeUs: Long): TrimClip? {
        val (inUs, outUs) = clip.trimmedTo(edge, sourceTimeUs)
        // Null for a clip no track holds: the drag arrived with a clip id, and the command it means
        // needs the lane too. Unreachable for a document built by the commands (clips are derived from
        // tracks), and null rather than a `!!` because a gesture must not be able to crash the editor.
        val trackId = history.current.trackIdOf(clip.id) ?: return null
        return TrimClip(
            trackId = trackId,
            clipId = clip.id,
            sourceInUs = inUs,
            sourceOutUs = outUs,
        )
    }

    private fun clipOf(clipId: String): Clip? =
        history.current.clips.firstOrNull { it.id == clipId }

    /**
     * Selects [clipId] if the document actually has it.
     *
     * The guard is not decoration: the id arrives from a tap, and a tap is resolved against the
     * geometry of the frame the user SAW. A clip deleted between that frame and the tap (an undo,
     * a ripple) would otherwise put a stale id in the state, and the inspector would edit a clip
     * that is not there.
     */
    private fun selectClip(clipId: String) {
        val exists = history.current.clips.any { it.id == clipId }
        if (exists) {
            _state.value = _state.value.copy(selection = Selection.Clip(clipId))
        } else {
            logger.d(TAG, "ignored a selection for $clipId: no such clip")
        }
    }

    /**
     * A thumbnail for one slice of the timeline.
     *
     * The Canvas takes a `suspend` loader rather than a Hilt-injected store, so that it stays a
     * function of its parameters and can be exercised with a fake. The route then has to obtain the
     * loader from somewhere, and the ViewModel is the Hilt-constructed object it already holds — the
     * alternatives are an `EntryPointAccessors` lookup in the UI or a second ViewModel that exists
     * only to hand out one object. The cost is an image-shaped method on a ViewModel; the benefit is
     * that no composable needs to know how the object graph is wired.
     */
    suspend fun timelineThumbnail(sourceId: String, uri: String, positionUs: Long): ImageBitmap? =
        images.timelineThumbnail(sourceId, uri, positionUs)

    /**
     * The frame the preview shows at the playhead (FR-2's "correct preview").
     *
     * Same reasoning as [timelineThumbnail]: the stage takes a `suspend` loader, and the ViewModel is
     * the Hilt-built object the route already has. The uri and the source time both come from the
     * DOMAIN's `previewTargetAt`, so the preview shows the frame the playhead maps to — trims, speed
     * and direction included — rather than the frame at the playhead's own time.
     */
    suspend fun previewFrame(uri: String, positionUs: Long): ImageBitmap? =
        images.previewFrame(uri, positionUs)

    /**
     * Writes the working project, so leaving the screen does not throw the user's edits away.
     *
     * ### When this is called, and why not on every state change
     *
     * Only from the paths that CHANGE THE DOCUMENT — an import, a cut, a reorder, the commit of a
     * gesture, an undo. A save on every `publish()` would also fire for a moved playhead or a changed
     * stage, which are view state: writing the user's project to disk because they scrolled the timeline
     * is work nobody asked for, on a disk that has to last.
     *
     * Fire-and-forget on [io], deliberately: the UI must not wait for a file write to redraw. A save that
     * fails is logged rather than surfaced, because the alternative — an error dialog over an edit the
     * user has already made — is worse than losing the autosave and telling them the next time they open
     * the project (Phase 4.7's job to make that visible).
     */
    private fun autosave() {
        val document = history.current
        val name = projectName ?: return
        viewModelScope.launch(io) {
            runCatching {
                projects.save(
                    SavedProject(
                        id = document.id,
                        name = name,
                        document = document,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { failure -> logger.d(TAG, "autosave failed: ${failure.message}") }
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
                // Which lane an imported file belongs on is a decision about the document, and with
                // one video lane per project there is nothing to decide: the clips join it. The audio
                // workstream is where a second lane (and so a per-file choice) arrives.
                trackId = history.current.importTrackId,
                sourceId = { "src-${ids.next()}" },
                clipId = { "clip-${ids.next()}" },
            )

            if (plan.hasImports) {
                // The project is NAMED at its first import, and the rename rides inside the import's own
                // command list: one act, one undoable entry, one label the user recognises. Naming it when
                // the screen opened would name a project the user might never make, and the numbering
                // exists to avoid colliding with projects that DO exist.
                val name = if (projectName == null) {
                    nextUntitledName(
                        projects.savedNames(),
                    )
                } else {
                    null
                }
                val commands = buildList {
                    name?.let { add(RenameDocument(it)) }
                    addAll(plan.commands)
                }

                history.execute(CompoundCommand(IMPORT_LABEL, commands))
                if (name != null) {
                    projectName = name
                    logger.d(TAG, "project named $name")
                }
                // The clip just imported becomes the SELECTED one. After an import the user's next act is
                // almost always about the clip they added, and the timeline's indicator is what answers
                // "which one am I working on?" — the device pass asked for exactly that.
                history.current.clips.lastOrNull()?.let { appended ->
                    _state.value = _state.value.copy(selection = Selection.Clip(appended.id))
                }
                autosave()
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
     * Re-reads the state from the stack, keeping the view fields (stage, playhead, selection,
     * import report, export sheet).
     * None of the five is history: undoing a trim must not also undo "the user is looking at the
     * Effect stage", rewind the playhead, drop the selection, erase the explanation of why one of
     * four files was refused, or close the sheet the user is reading.
     *
     * The playhead and selection are RE-DERIVED against the new document rather than copied
     * blindly, which is where two bugs would otherwise live: after a delete or an undo that
     * shortens the timeline, a playhead past the new end would draw off the timeline, and a
     * selection naming a clip that no longer exists would leave the inspector editing nothing.
     */
    private fun publish(import: ImportReport? = _state.value.import) {
        val document = history.current
        _state.value = history.toUiState(
            stage = _state.value.stage,
            playheadUs = _state.value.playheadUs.coerceIn(0L, document.timelineDurationUs),
            selection = _state.value.selection.reconciledWith(document.clips.map { it.id }),
            tool = _state.value.tool.reconciledWith(document.clips.map { it.id }),
            import = import,
            exportSheet = _state.value.exportSheet,
        )
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
