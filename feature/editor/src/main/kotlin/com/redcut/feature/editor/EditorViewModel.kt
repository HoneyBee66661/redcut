package com.redcut.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.redcut.core.common.IdSource
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.MediaSourceReader
import com.redcut.core.media.PreviewRenderer
import com.redcut.core.media.PreviewState
import com.redcut.core.media.SourceReadResult
import com.redcut.domain.document.Clip
import com.redcut.domain.document.CompoundCommand
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.FrameStep
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.KeyframableProperty
import com.redcut.domain.document.Keyframe
import com.redcut.domain.document.RenameDocument
import com.redcut.domain.document.ReorderClip
import com.redcut.domain.document.SetKeyframes
import com.redcut.domain.document.SetTransform
import com.redcut.domain.document.TransformSpec
import com.redcut.domain.document.UndoStack
import com.redcut.domain.document.ViewportRect
import com.redcut.domain.document.commandFor
import com.redcut.domain.document.planImport
import com.redcut.domain.document.steppedPlayheadUs
import com.redcut.domain.document.textOverlayById
import com.redcut.domain.document.timelineDurationUs
import com.redcut.domain.project.ProjectStore
import com.redcut.domain.project.SavedProject
import com.redcut.domain.project.nextUntitledName
import com.redcut.domain.render.staticValueIn
import com.redcut.domain.render.transformAt
import com.redcut.domain.render.valueAt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /**
     * The preview's renderer (spec §8.4).
     *
     * Public because the screen has to hold it too: §6.8 rule D6 is *"the UI holds the interface"*, and
     * the composable that owns the `SurfaceView` is the only place that knows when that surface appears
     * and when the app goes to the background. The ViewModel owns the LIFETIME — it is the thing whose
     * end means "this screen is gone" — and the screen owns the binding. That split is also why the
     * renderer is not a field of [EditorUiState]: it is a resource with a lifetime, not a value to
     * render, and §7.2's one-state rule is about the values the UI draws.
     */
    val previewRenderer: PreviewRenderer,
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
     * The live-gesture half of the editor: the drags that preview while the finger is down and commit
     * as one history entry when it lifts. See [GestureSession] for why it is a class of its own.
     *
     * It is built with lambdas rather than values because it must see THIS class's current history and
     * state on every call — `history` is replaced wholesale when a saved project is reopened, and a
     * captured stack would quietly drag the new project's edits onto the old project's history.
     */
    private val gestures = GestureSession(
        logger = logger,
        history = { history },
        state = { _state.value },
        publishState = { next ->
            _state.value = next
            publish()
        },
        persist = ::autosave,
    )

    /** The caption gestures (FR-4.3): drag, restyle, range-trim. Same five lambdas as [gestures]. */
    private val textGestures = TextGestureSession(
        logger = logger,
        history = { history },
        state = { _state.value },
        publishState = { next ->
            _state.value = next
            publish()
        },
        persist = ::autosave,
    )

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

    // The preview -> playhead direction (FR-2.10). `state` answers "is it playing" and `positionUs`
    // answers "where", and both are the RENDERER's own answers, so what lands in the UI state is a
    // snapshot of the player rather than a second opinion about it (§7.2). On `viewModelScope`, so a
    // screen that goes away stops moving a playhead nobody is looking at.
    init {
        viewModelScope.launch {
            combine(previewRenderer.state, previewRenderer.positionUs) { preview, positionUs ->
                PlaybackState(
                    isPlaying = preview is PreviewState.Ready && preview.isPlaying,
                    positionUs = positionUs,
                )
            }.collect { playback -> followPlayback(playback) }
        }
    }

    /**
     * Gives the preview's decoder back (spec §9.1).
     *
     * A renderer holds a `Media3` player, a player holds a hardware decoder, and the device's pool of
     * them is small and shared — so the end of this screen is the end of the preview's claim on one. The
     * screen releases its surface separately; between the two, no path out of the editor leaves a codec
     * held.
     */
    override fun onCleared() {
        previewRenderer.release()
        super.onCleared()
    }

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
     * Four gesture families pass through: the timeline's trim, the sliders, and the caption's two (the
     * box drag and the style rows). The gestures are handed on whole — the four moments of
     * each, and the preview/commit pairing that makes them one edit, belong to the gesture lifecycle
     * rather than to this class, so they live in [GestureSession] and this class keeps one line each.
     * An import is not a family — one intent, one branch, one handler that was already named for it —
     * and neither are the keyframe transport's toggle (WS K) or the caption's add (FR-4.3): one intent,
     * one branch, one handler.
     *
     * There is still no `else` anywhere: a new intent joins a family (and the compiler makes that
     * family's `when` handle it) or takes a branch of its own, and either way it cannot fall outside
     * both. That is what makes a fourth family cheap — its intents, its helper wherever that
     * responsibility belongs, and one line here.
     */
    private fun applyEdit(intent: EditorIntent.Edit) {
        when (intent) {
            // The drag gestures, handed whole to the session that owns their lifecycle. Each family's
            // `when` — four moments, exhaustive — is there rather than here.
            is EditorIntent.TrimGesture -> gestures.applyTrim(intent)
            is EditorIntent.AdjustGesture -> gestures.applyAdjust(intent)
            is EditorIntent.TextGesture -> textGestures.applyTextDrag(intent)
            is EditorIntent.TextStyleGesture -> textGestures.applyTextStyle(intent)
            is EditorIntent.TextTrimGesture -> textGestures.applyTextTrim(intent)

            // The Cut stage's tools at the playhead, and the drag that rearranges one lane.
            is EditorIntent.ApplyCut -> applyCut(intent.tool)
            is EditorIntent.ApplyReorder -> applyReorder(intent.clipId, intent.toIndex)

            // The preview viewport (WS F): the crop and zoom of the SELECTED clip.
            is EditorIntent.SetViewport -> applyViewport(intent)

            is EditorIntent.ToggleKeyframe -> toggleKeyframe()

            is EditorIntent.AddText -> applyAddTextOverlay(
                history = { history },
                state = { _state.value },
                ids = ids,
                logger = logger,
                autosave = { autosave() },
                publish = { publish() },
            )

            is EditorIntent.ImportMedia -> importMedia(intent.uris)
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
     * The transport diamond: add or remove a key at the playhead for the selected clip's active
     * keyframable property (WS K).
     *
     * Toggle, derived from the state: if the active property already has a key exactly at the playhead's
     * local time, remove it; otherwise add one holding the property's current value there. Removing the
     * LAST key empties the property's list, which [SetKeyframes] turns into removing the property from
     * the clip's keyframes map — so the clip returns exactly to its pre-keyframe state. The current value
     * is the interpolated one when the property is already animated, and the clip's static transform value
     * when it is not (the one-key-equals-a-constant rule, spec §13.1).
     */
    private fun toggleKeyframe() {
        val state = _state.value
        val transport = keyframeTransport(history.current, state.selection, state.playheadUs)
            ?: return
        val clip = history.current.clipById(transport.clipId) ?: return
        val trackId = history.current.trackIdOf(transport.clipId) ?: return
        val existing = clip.keyframes[transport.property].orEmpty()
        val hasKeyAt = existing.any { it.timeUs == transport.localUs }
        val keys = if (hasKeyAt) {
            existing.filterNot { it.timeUs == transport.localUs }
        } else {
            val value = keyframeValueAt(clip, transport.property, transport.localUs)
            (existing + Keyframe(transport.localUs, value)).sortedBy { it.timeUs }
        }
        if (keys == existing) return
        val verb = if (hasKeyAt) "remove" else "add"
        logger.d(TAG, "keyframe $verb ${transport.property} @ ${transport.localUs}")
        history.execute(SetKeyframes(trackId, transport.clipId, transport.property, keys))
        autosave()
        publish()
    }

    /**
     * Move the playhead to the previous or next key of the selected clip's active keyframable property.
     */
    private fun moveToAdjacentKey(forward: Boolean) {
        val state = _state.value
        val transport = keyframeTransport(history.current, state.selection, state.playheadUs)
            ?: return
        val keys = history.current.clipById(transport.clipId)
            ?.keyframes
            ?.get(transport.property)
            .orEmpty()
        val target = if (forward) {
            keys.firstOrNull { it.timeUs > transport.localUs }
        } else {
            keys.lastOrNull { it.timeUs < transport.localUs }
        } ?: return
        movePlayhead(transport.clipStartUs + target.timeUs)
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

            is EditorIntent.SetPlayhead -> movePlayhead(intent.us)

            is EditorIntent.StepPlayhead -> stepPlayhead(intent.step)

            // EVERY selection intent through one arm, and deliberately: the three differ only in which id
            // they carry, they share one stale-id rule, and detekt counts each arm of this `when` as a
            // branch. `applySelection` is where the three readings and that rule live.
            is EditorIntent.SelectClip, is EditorIntent.SelectTrack, EditorIntent.ClearSelection ->
                applySelection(intent)

            // Selects [effectId] if the document actually holds that caption (FR-4.3, J-2). The same
            // stale-id rule [applySelection] keeps, and for the same reason: the id comes from a hit test
            // against a frame the user SAW, and a caption removed between that frame and the tap (an
            // undo, a reopened project) must not leave the inspector editing an effect that is not there.
            // The whole branch is one call to a file-level function so updateView's `when` stays a flat
            // dispatch — the stale-id guard is the ONE branch this arm owns, and counting it here pushed
            // the function over detekt's CyclomaticComplexMethod limit.
            is EditorIntent.SelectTextOverlay ->
                applyTextSelection(
                    effectId = intent.effectId,
                    document = history.current,
                    state = _state.value,
                    setState = { _state.value = it },
                    onIgnored = { logger.d(TAG, it) },
                )

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

            // The keyframe transport's two VIEW moves: jump the playhead to the previous or next key of
            // the selected clip's active keyframable property. Moving the playhead is not an edit, so
            // undo must not step through it — the same rule every other playhead move follows.
            is EditorIntent.PrevKeyframe, is EditorIntent.NextKeyframe ->
                moveToAdjacentKey(forward = intent is EditorIntent.NextKeyframe)
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
        movePlayhead(history.current.steppedPlayheadUs(_state.value.playheadUs, step))
    }

    /**
     * Puts the playhead at [us], and the preview with it (FR-2's "correct preview").
     *
     * One function for both playhead paths — a tap and a frame step — because they are one thing: the
     * position the user is looking at, and the frame that position means. The preview is told the
     * TIMELINE position and resolves the frame itself from the graph it was attached with, so nothing
     * here maps a timeline position to a source position; that mapping has one owner and it is not the
     * ViewModel.
     *
     * A seek with nothing attached is not lost: the renderer remembers it and applies it when the next
     * composition is ready, which is exactly the state the user is in when they scrub while a rebuild is
     * still opening.
     */
    private fun movePlayhead(us: Long) {
        val next = us.coerceIn(0L, history.current.timelineDurationUs)
        _state.value = _state.value.copy(playheadUs = next)
        previewRenderer.seekTo(next)
    }

    /**
     * The other direction: the preview moving the playhead (FR-2.10), which is the half that was missing.
     *
     * The device pass asked for exactly this — *"saat gua play clip, preview jalan namun clip tidak
     * bergerak sesuai posisi frame di preview. harusnya ada link antara clip dan player"* — and the link
     * had one end already: [movePlayhead] seeks the preview when the user moves the playhead. Nothing went
     * the other way, so the picture played while the timeline stood still.
     *
     * ### There is no `seekTo` here, and that is the whole point
     *
     * This runs about thirty times a second while the preview plays, and every call would be a seek back
     * into the player that produced the position it carries. That is the difference between playback that
     * runs and playback that stutters: the player is already where it says it is, and the playhead is the
     * one that has to catch up.
     *
     * ### The clamp
     *
     * The same bound [movePlayhead] applies, for the same reason (invariant 5 of §7.2): the last position a
     * player reports can sit a frame past the end of the document — the composition knows its own length,
     * not the timeline the ruler draws — and a playhead past the end is a playhead drawn off the timeline.
     * It is applied to the snapshot as well as to the playhead, so the two numbers in the state cannot
     * disagree about where "here" is.
     *
     * `playback` travels in the same `copy` as the playhead rather than in a write of its own: §7.2's one
     * state object exists so that a frame of the UI cannot show the position from one instant and the play
     * button from another.
     */
    private fun followPlayback(playback: PlaybackState) {
        val here = playback.positionUs.coerceIn(0L, history.current.timelineDurationUs)
        _state.value = _state.value.copy(
            playback = playback.copy(positionUs = here),
            playheadUs = here,
        )
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

    private fun clipOf(clipId: String): Clip? =
        history.current.clips.firstOrNull { it.id == clipId }

    /**
     * Every intent that changes WHAT IS SELECTED (§7.2, §WS E / Task E1).
     *
     * One behaviour read three ways — select this clip, select this lane, select nothing — and the two ids
     * keep the SAME stale-id rule, which is why they are gathered here rather than spread across arms that
     * would each have to remember it: an id arrives from a tap resolved against the geometry of the frame
     * the user SAW, so an id the document no longer holds must not become the selection (the inspector would
     * edit a clip that is not there; the toolbar would offer a lane's tools for a lane that is not on
     * screen). Clip and track selection replace each other rather than stacking — that is the `Selection`
     * type's own rule, not something this function enforces: assigning the whole field unselects the other.
     *
     * It is also the shape detekt asks for, twice over: the three intents share ONE arm of the dispatch's
     * `when` (whose complexity is measured per arm), and they go through one function here rather than one
     * per intent, which keeps the class's own function count inside its limit.
     *
     * A caption is deliberately NOT one of the three: [applyTextSelection] takes the document apart (the
     * caption lives in the effect stack, not in a track) and its id is a different kind of thing.
     */
    private fun applySelection(intent: EditorIntent) {
        when (intent) {
            EditorIntent.ClearSelection ->
                _state.value = _state.value.copy(selection = Selection.None)

            is EditorIntent.SelectClip ->
                if (history.current.clips.any { it.id == intent.clipId }) {
                    _state.value = _state.value.copy(selection = Selection.Clip(intent.clipId))
                } else {
                    logger.d(TAG, "ignored a selection for ${intent.clipId}: no such clip")
                }

            is EditorIntent.SelectTrack ->
                if (history.current.tracks.any { it.id == intent.trackId }) {
                    _state.value = _state.value.copy(selection = Selection.Track(intent.trackId))
                } else {
                    logger.d(TAG, "ignored a selection for ${intent.trackId}: no such track")
                }

            // Unreachable through the dispatch, which routes only the three above here. A log line rather
            // than a throw because a selection intent that is not one of the three is a caller mistake that
            // costs nothing to survive.
            else -> logger.d(TAG, "ignored a selection intent: $intent")
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
     * Re-reads the state from the stack, keeping the view fields (stage, playhead, playback, selection,
     * import report, export sheet).
     * None of the six is history: undoing a trim must not also undo "the user is looking at the
     * Effect stage", rewind the playhead, claim the preview has stopped, drop the selection, erase the
     * explanation of why one of four files was refused, or close the sheet the user is reading.
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
            playback = _state.value.playback,
            selection = _state.value.selection.reconciledWith(
                clipIds = document.clips.map { it.id },
                trackIds = document.tracks.map { it.id },
            ),
            tool = _state.value.tool.reconciledWith(document.clips.map { it.id }),
            import = import,
            exportSheet = _state.value.exportSheet,
        )
        // The preview follows the playhead this re-derived, for the same reason the re-derivation
        // exists: a delete, a cut or an undo can move it, and a preview still showing the old position
        // would be the one surface in the app disagreeing with the timeline. A seek is idempotent, so
        // the common case — nothing moved — costs a comparison inside the renderer.
        previewRenderer.seekTo(_state.value.playheadUs)
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

/**
 * The transport's reading of the keyframe state for [document], [selection] and [playheadUs].
 *
 * Shared by the UI (to enable the diamond and the prev/next arrows and to show the keyed state) and by
 * the ViewModel (to act), so the two can never disagree about which property the diamond points at.
 */
internal data class KeyframeTransport(
    val clipId: String,
    val property: KeyframableProperty,
    val clipStartUs: Long,
    val localUs: Long,
)

/**
 * Selects [effectId] when the document actually holds that caption, and reports ignored otherwise.
 *
 * File-level (rather than a ViewModel member) so [updateView]'s `when` stays a flat dispatch: the
 * stale-id guard is this function's one branch, and counting it in the `when`'s own cyclomatic total
 * pushed the dispatcher over detekt's limit. The same stale-id rule [applySelection] keeps for a clip and a
 * lane, for the same reason: the id comes from a hit test against a frame the user SAW, and a caption removed
 * between that frame and the tap (an undo, a reopened project) must not leave the inspector editing an effect
 * that is not there.
 */
private fun applyTextSelection(
    effectId: String,
    document: EditDocument,
    state: EditorUiState,
    setState: (EditorUiState) -> Unit,
    onIgnored: (String) -> Unit,
) {
    val exists = document.textOverlayById(effectId) != null
    if (exists) {
        setState(state.copy(selection = Selection.Text(effectId)))
    } else {
        onIgnored("ignored a selection for $effectId: no such caption")
    }
}

/**
 * The selected clip's active keyframable property and the playhead's position within it, or null when
 * the transport has nothing to act on.
 *
 * Null covers both "nothing selected" and "the playhead is not over the selected clip": a key can only
 * be added where the clip actually plays, so a playhead sitting over a different clip leaves the diamond
 * inert. The active property is the first keyframable property the clip has actually keyed (in enum
 * order, so the answer is stable regardless of map ordering), falling back to
 * [KeyframableProperty.CROP_LEFT] when the clip has no keys at all — the property the transform/crop
 * block makes keyframable first. The property list is read from the model's own
 * [KeyframableProperty.entries], never fabricated here.
 */
internal fun keyframeTransport(
    document: EditDocument,
    selection: Selection,
    playheadUs: Long,
): KeyframeTransport? {
    val clipId = (selection as? Selection.Clip)?.clipId ?: return null
    val clip = document.clipById(clipId) ?: return null
    val clipStartUs = document.timeline.firstOrNull { it.clip.id == clipId }?.startUs ?: return null
    val localUs = playheadUs - clipStartUs
    if (localUs < 0L || localUs >= clip.timelineDurationUs) return null
    val property = KeyframableProperty.entries.firstOrNull { it in clip.keyframes }
        ?: KeyframableProperty.CROP_LEFT
    return KeyframeTransport(clipId, property, clipStartUs, localUs)
}

/**
 * The clip's transform at the playhead: the PREVIEW's read of the render tier's one resolver (WS G1).
 *
 * The subtraction is the whole of the preview's half of the parity contract. The playhead is a
 * TIMELINE position and a clip's keys are on the CLIP's own clock, so the one thing the preview has to
 * get right is which microsecond of the clip it is asking about; everything after that belongs to
 * `:domain:render`, shared with the export path frame for frame. A preview that got the clock wrong
 * would animate, and would animate at the wrong moment — the failure that looks like a bug in the
 * interpolation rather than in this line.
 *
 * Null when the document has no such clip, or (impossibly for a clip [EditDocument.clips] reports) no
 * lane places it. A playhead outside the clip's own span is NOT special-cased, deliberately: the
 * resolver already clamps to the first and last key, and the alternative — falling back to the static
 * transform — would snap the picture back to a crop the user animated away from, on the frame the
 * playhead crossed the clip's edge.
 *
 * A function of the document rather than of the ViewModel, like [keyframeTransport] beside it, so the
 * composable that draws the preview reaches the same answer the tests do without a ViewModel.
 */
internal fun EditDocument.transformAtPlayhead(clipId: String, playheadUs: Long): TransformSpec? {
    val clip = clipById(clipId) ?: return null
    val clipStartUs = timeline.firstOrNull { it.clip.id == clipId }?.startUs ?: return null
    return clip.transformAt(playheadUs - clipStartUs)
}

/**
 * The value a new key should hold: [property]'s on-screen value on [clip] at [localUs].
 *
 * When the property is already keyframed, that is the interpolated value at the playhead — so a key
 * dropped mid-animation does not snap the property, it freezes where it was heading. When the property
 * has no keys yet, it is the clip's static transform value, which is what "one key = constant" means:
 * the first key captures the value the property already has.
 *
 * Both branches are the DOMAIN's, not this module's. The question "what is this property worth at t"
 * has exactly one answer in the app — `:domain:render`'s resolver, which the preview and the export
 * read too — and a UI that interpolated for itself would be a third opinion, agreeing with the other
 * two only until someone keyed a clip. So this delegates rather than computes.
 *
 * A pure function beside [keyframeTransport] rather than a method of the ViewModel, for the same reason
 * that one is: it is the transport's reading of the document, both paths that act on a keyframe need it
 * to agree, and a plain JVM test reaches it without a ViewModel.
 */
internal fun keyframeValueAt(clip: Clip, property: KeyframableProperty, localUs: Long): Float =
    property.valueAt(clip.transform, clip.keyframes, localUs)

/** The static value of [property] on [transform] — the fallback when the property has no keys. */
internal fun KeyframableProperty.valueIn(transform: TransformSpec): Float = staticValueIn(transform)
