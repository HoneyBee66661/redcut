package com.redcut.feature.editor

import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.document.Clip
import com.redcut.domain.document.ClipAdjustment
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.SetTextTransform
import com.redcut.domain.document.TrimClip
import com.redcut.domain.document.UndoStack
import com.redcut.domain.document.adjust
import com.redcut.domain.document.textOverlayById
import com.redcut.domain.document.toOverlayBox
import com.redcut.domain.document.toTransform
import com.redcut.domain.document.trimmedTo

/**
 * The editor's live gestures: a drag PREVIEWS while the finger is down and becomes ONE history entry
 * when it lifts (FR-2.1, FR-3.1–3.4, 3.9, FR-4.3) — a trim edge, a slider and a caption's box, which
 * share that lifecycle.
 *
 * Its own file because `detekt` measured [EditorViewModel] at 23 functions against a threshold of 20,
 * and because a gesture is a responsibility, not a branch: begin, move, lift, abandon, plus the
 * preview/commit pairing that makes those four one edit. It holds no state and no ViewModel — the
 * document, the UI state, the state write and the autosave arrive as the lambdas below, and [history]
 * is a lambda because the stack is replaced wholesale when a saved project is reopened.
 */
internal class GestureSession(
    private val logger: RedcutLogger,
    private val history: () -> UndoStack,
    private val state: () -> EditorUiState,
    private val publishState: (EditorUiState) -> Unit,
    private val persist: () -> Unit,
) {

    /**
     * The trim gesture's four moments, dispatched by moment. The drag previews and only the lift
     * records; [applyAdjust] has the same shape, and [end] and [cancel] close either one.
     *
     * The two entry points are the only members the ViewModel calls — the class is `internal`, so a
     * bare `fun` here is module-wide and no redundant modifier is needed to say so.
     */
    fun applyTrim(intent: EditorIntent.TrimGesture) {
        when (intent) {
            is EditorIntent.BeginTrim -> beginTrim(intent.clipId, intent.edge, intent.sourceTimeUs)
            is EditorIntent.UpdateTrim -> updateTrim(intent.sourceTimeUs)
            EditorIntent.EndTrim -> end()
            EditorIntent.CancelTrim -> cancel()
        }
    }

    /** The adjust gesture's four moments (FR-3.1–3.4, 3.9). See [applyTrim]. */
    fun applyAdjust(intent: EditorIntent.AdjustGesture) {
        when (intent) {
            is EditorIntent.BeginAdjust -> beginAdjust(intent.clipId, intent.adjustment)
            is EditorIntent.UpdateAdjust -> updateAdjust(intent.value)
            EditorIntent.EndAdjust -> end()
            EditorIntent.CancelAdjust -> cancel()
        }
    }

    /**
     * The caption drag's four moments (FR-4.3). See [applyTrim] — the lifecycle is the same one, and it is
     * deliberately the same code: [end] and [cancel] are shared, so a caption drag commits as ONE entry
     * because that is what [UndoStack.commit] does, not because this family remembered to ask it to.
     */
    fun applyTextDrag(intent: EditorIntent.TextGesture) {
        when (intent) {
            is EditorIntent.BeginTextDrag -> beginTextDrag(intent.effectId)
            is EditorIntent.UpdateTextDrag -> updateTextDrag(intent.centerX, intent.centerY)
            EditorIntent.EndTextDrag -> end()
            EditorIntent.CancelTextDrag -> cancel()
        }
    }

    /**
     * Starts a trim drag: preview it, so the timeline and the stage body follow the finger live.
     *
     * The clip is looked up fresh rather than trusted from the intent — the id came from a hit test
     * against a frame the user saw, and a clip deleted since (an undo, a ripple) must not start a
     * gesture against nothing.
     */
    private fun beginTrim(clipId: String, edge: ClipEdge, sourceTimeUs: Long) {
        val clip = clipOf(clipId) ?: return
        val command = trimCommandFor(clip, edge, sourceTimeUs) ?: return
        logger.d(TAG, "trim ${edge.name.lowercase()} of $clipId to $sourceTimeUs")
        history().preview(command)
        publishState(
            state().copy(
                tool = ToolState.Trimming(
                    clipId = clipId,
                    edge = edge,
                    sourceTimeUs = sourceTimeUs,
                ),
            ),
        )
    }

    /**
     * The drag moved, so rebuild the command from the CURRENT clip on every frame — which is what
     * makes the held-edge invariant hold: the drag value only ever moves the edge the gesture started
     * on, and the other end keeps whatever the last preview put there.
     *
     * Ignored when no trim is in flight, which is not an error: taps race drags.
     */
    private fun updateTrim(sourceTimeUs: Long) {
        val trimming = state().tool as? ToolState.Trimming ?: return
        val clip = clipOf(trimming.clipId) ?: return
        val command = trimCommandFor(clip, trimming.edge, sourceTimeUs) ?: return
        history().preview(command)
        publishState(state().copy(tool = trimming.copy(sourceTimeUs = sourceTimeUs)))
    }

    /**
     * The command a trim drag means: `trimmedTo` gives the INTENT (one edge moves) and [TrimClip] owns
     * the clamping, so a drag past the end of the source is recorded as the user's intent and applied
     * as the limit. The UI learns what it actually got by reading the document back.
     */
    private fun trimCommandFor(clip: Clip, edge: ClipEdge, sourceTimeUs: Long): TrimClip? {
        val (inUs, outUs) = clip.trimmedTo(edge, sourceTimeUs)
        // Null for a clip no track holds: the drag arrived with a clip id, and the command it means
        // needs the lane too. Unreachable for a document built by the commands, and null rather than a
        // `!!` because a gesture must not be able to crash the editor.
        val trackId = history().current.trackIdOf(clip.id) ?: return null
        return TrimClip(
            trackId = trackId,
            clipId = clip.id,
            sourceInUs = inUs,
            sourceOutUs = outUs,
        )
    }

    /**
     * Marks a control as being dragged and selects its clip; no command is previewed yet, because a
     * drag that has not moved the slider has changed nothing, and previewing the value it already has
     * would put a no-op on the history the moment the finger went down.
     */
    private fun beginAdjust(clipId: String, adjustment: ClipAdjustment) {
        if (history().current.clipById(clipId) == null) return
        logger.d(TAG, "adjust ${adjustment.name.lowercase()} of $clipId")
        publishState(
            state().copy(
                tool = ToolState.Adjusting(clipId, adjustment),
                selection = Selection.Clip(clipId),
            ),
        )
    }

    /**
     * One frame of a slider drag: preview, so the document — and so the preview body and the timeline —
     * follows the finger; [UndoStack] collapses the whole drag into a single entry on commit.
     */
    private fun updateAdjust(value: Float) {
        val adjusting = (state().tool as? ToolState.Adjusting) ?: return
        val command = history().current
            .adjust(adjusting.clipId, adjusting.adjustment, value) ?: return
        history().preview(command)
        publishState(state())
    }

    /**
     * Marks the caption as being dragged, and previews nothing yet — the shape [beginAdjust] has, and for
     * the same reason: a drag that has not moved has changed nothing, and previewing the box the caption
     * already has would put a no-op on the history the moment the finger went down.
     *
     * The caption is looked up fresh rather than trusted from the intent: the id came from a hit test
     * against a frame the user saw, and a caption removed since (an undo, a reopened project) must not open
     * a gesture against nothing.
     */
    private fun beginTextDrag(effectId: String) {
        if (history().current.textOverlayById(effectId) == null) return
        logger.d(TAG, "drag text $effectId")
        publishState(state().copy(tool = ToolState.MovingText(effectId)))
    }

    /**
     * One frame of a caption drag: preview, so the caption follows the finger on the preview itself.
     *
     * The command is rebuilt from the caption the document holds NOW rather than from the one the gesture
     * started on, which is the same rule [updateTrim] follows: what the drag decides is where the box's
     * CENTRE goes, and everything else about the transform — rotation, flip, fit — is carried through from
     * whatever the caption currently holds.
     */
    private fun updateTextDrag(centerX: Float, centerY: Float) {
        val moving = (state().tool as? ToolState.MovingText) ?: return
        val caption = history().current.textOverlayById(moving.effectId) ?: return
        val moved = caption.transform.toOverlayBox().movedToCentre(centerX, centerY)
        history().preview(
            SetTextTransform(
                effectId = moving.effectId,
                transform = moved.toTransform(base = caption.transform),
            ),
        )
        publishState(state())
    }

    /**
     * Ends whichever gesture is open, committing it as ONE entry. One function for both because the
     * lifecycle is the same one: a preview is open, the finger has lifted, and what the document holds
     * now becomes the edit — and the undo label comes from the previewed command, so nothing here has
     * to know which gesture it is closing.
     */
    private fun end() {
        if (state().tool is ToolState.Idle) return
        history().commit()
        persist()
        publishState(state().copy(tool = ToolState.Idle))
    }

    /** Abandons whichever gesture is open: the document goes back to what it held before. See [end]. */
    private fun cancel() {
        if (state().tool is ToolState.Idle) return
        history().abortPreview()
        publishState(state().copy(tool = ToolState.Idle))
    }

    private fun clipOf(clipId: String): Clip? =
        history().current.clips.firstOrNull { it.id == clipId }

    private companion object {
        const val TAG = "GestureSession"
    }
}
