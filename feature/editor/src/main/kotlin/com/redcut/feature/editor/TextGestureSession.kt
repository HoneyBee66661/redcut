package com.redcut.feature.editor

import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.SetTextRange
import com.redcut.domain.document.SetTextStyle
import com.redcut.domain.document.SetTextTransform
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.UndoStack
import com.redcut.domain.document.textOverlayById
import com.redcut.domain.document.toOverlayBox
import com.redcut.domain.document.toTransform
import com.redcut.feature.editor.EditorIntent.BeginTextDrag
import com.redcut.feature.editor.EditorIntent.BeginTextStyle
import com.redcut.feature.editor.EditorIntent.BeginTextTrim
import com.redcut.feature.editor.EditorIntent.UpdateTextDrag
import com.redcut.feature.editor.EditorIntent.UpdateTextStyle
import com.redcut.feature.editor.EditorIntent.UpdateTextTrim

/**
 * The caption gestures (FR-4.3): drag the box, restyle, trim the range.
 *
 * Split out of [GestureSession] when the text families joined the trim and adjust ones and the class
 * hit detekt's function-count limit — the same split that created GestureSession from the ViewModel,
 * for the same reason: three gesture families behind one door is a class that does three jobs, and
 * the two text families share a lifecycle that the clip ones do not.
 *
 * The constructor takes the same five lambdas GestureSession takes — the class needs the exact same
 * four handles (history, state, publishState, persist) and the same two closers, whose bodies are
 * deliberately identical to GestureSession's ([end], [cancel]): a preview is open, and closing it is
 * one of exactly two moves whatever the gesture was. They live here so the text session owns its own
 * close; GestureSession's are the same six lines, and a comment there says so.
 */
internal class TextGestureSession(
    private val logger: RedcutLogger,
    private val history: () -> UndoStack,
    private val state: () -> EditorUiState,
    private val publishState: (EditorUiState) -> Unit,
    private val persist: () -> Unit,
) {

    /**
     * The caption drag's four moments (FR-4.3). See GestureSession.applyTrim — the lifecycle is the
     * same one, and it is deliberately the same code: [end] and [cancel] are shared in shape, so a
     * caption drag commits as ONE entry because that is what [UndoStack.commit] does, not because
     * this family remembered to ask it to.
     */
    fun applyTextDrag(intent: EditorIntent.TextGesture) {
        when (intent) {
            is BeginTextDrag -> beginTextDrag(intent.effectId)
            is UpdateTextDrag -> updateTextDrag(intent.centerX, intent.centerY)
            EditorIntent.EndTextDrag -> end()
            EditorIntent.CancelTextDrag -> cancel()
        }
    }

    /**
     * The caption restyle's four moments (FR-4.3, J-2). See [applyTextDrag]: the same lifecycle, and
     * the same two shared closers — a slider drag on a style row previews per frame and commits once,
     * which is what keeps "Undo Text style" one entry per gesture.
     */
    fun applyTextStyle(intent: EditorIntent.TextStyleGesture) {
        when (intent) {
            is BeginTextStyle -> beginTextStyle(intent.effectId, intent.spec)
            is UpdateTextStyle -> updateTextStyle(intent.spec)
            EditorIntent.EndTextStyle -> end()
            EditorIntent.CancelTextStyle -> cancel()
        }
    }

    /**
     * The caption's timeline-edge drag (FR-4.3's card 4). See GestureSession.applyTrim: the same
     * lifecycle, previewing [SetTextRange] per frame and committing once, so an edge drag reads as
     * one "Undo Text timing" entry — the same label the inspector's timing fields produce, because
     * they are the same edit.
     */
    fun applyTextTrim(intent: EditorIntent.TextTrimGesture) {
        when (intent) {
            is BeginTextTrim -> beginTextTrim(intent.effectId, intent.edge, intent.us)
            is UpdateTextTrim -> updateTextTrim(intent.us)
            EditorIntent.EndTextTrim -> end()
            EditorIntent.CancelTextTrim -> cancel()
        }
    }

    /**
     * Marks the caption as being dragged, and previews nothing yet — the shape GestureSession's
     * beginAdjust has, and for the same reason: a drag that has not moved has changed nothing, and
     * previewing the box the caption already has would put a no-op on the history the moment the
     * finger went down.
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
     * The command is rebuilt from the caption the document holds NOW rather than from the one the
     * gesture started on, which is the same rule GestureSession.updateTrim follows: what the drag
     * decides is where the box's CENTRE goes, and everything else about the transform — rotation,
     * flip, fit — is carried through from whatever the caption currently holds.
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
     * Marks a style row as being dragged, and selects its caption; no command is previewed yet, because a
     * drag that has not moved a row has changed nothing — the shape GestureSession.beginAdjust has, and
     * for the same reason: previewing the spec the caption already holds would put a no-op on the history
     * the moment the finger went down.
     *
     * The caption is looked up fresh rather than trusted from the intent, the rule [beginTextDrag]
     * keeps: a caption removed since the tap (an undo, a reopened project) must not open a gesture
     * against nothing.
     */
    private fun beginTextStyle(effectId: String, spec: TextSpec) {
        val caption = history().current.textOverlayById(effectId) ?: return
        logger.d(TAG, "style text $effectId")
        history().preview(SetTextStyle(effectId = caption.id, spec = spec))
        publishState(
            state().copy(
                tool = ToolState.StylingText(effectId),
                selection = Selection.Text(effectId),
            ),
        )
    }

    /**
     * One frame of a style drag: preview the whole spec, so the caption follows the row live and the
     * whole drag lands on the history as ONE entry when the finger lifts.
     */
    private fun updateTextStyle(spec: TextSpec) {
        val styling = (state().tool as? ToolState.StylingText) ?: return
        history().preview(SetTextStyle(effectId = styling.effectId, spec = spec))
        publishState(state())
    }

    /**
     * Starts a caption edge drag: preview it, so the lane item follows the finger live.
     *
     * The caption is looked up fresh rather than trusted from the intent, the rule
     * GestureSession.beginTrim keeps; and the OTHER end is read at this moment and then held: a drag
     * on one edge must not move the other edge as the preview shifts the range — the held-edge
     * invariant GestureSession.updateTrim keeps for a clip, with timeline time in place of source
     * time because a caption has no source.
     */
    private fun beginTextTrim(effectId: String, edge: ClipEdge, us: Long) {
        val caption = history().current.textOverlayById(effectId) ?: return
        logger.d(TAG, "text trim ${edge.name.lowercase()} of $effectId to $us")
        history().preview(textRangeCommand(caption, edge, us))
        publishState(state().copy(tool = ToolState.TrimmingText(effectId, edge, us)))
    }

    /**
     * The drag moved, so rebuild the command from the CURRENT caption on every frame — which is what
     * keeps the held-edge invariant: the fixed end comes from the caption's previewed range (where
     * only the dragged edge has moved), and the dragged edge is the finger's raw position, clamped by
     * the command.
     */
    private fun updateTextTrim(us: Long) {
        val trimming = state().tool as? ToolState.TrimmingText ?: return
        val caption = history().current.textOverlayById(trimming.effectId) ?: return
        history().preview(textRangeCommand(caption, trimming.edge, us))
        publishState(state().copy(tool = trimming.copy(us = us)))
    }

    /** The command a caption edge drag means: the dragged edge moves, the other end holds. */
    private fun textRangeCommand(
        caption: AppliedEffect.Text,
        edge: ClipEdge,
        us: Long,
    ): SetTextRange = if (edge == ClipEdge.IN) {
        SetTextRange(effectId = caption.id, startUs = us, endUs = caption.timeRange.endUs)
    } else {
        SetTextRange(effectId = caption.id, startUs = caption.timeRange.startUs, endUs = us)
    }

    /**
     * Ends whichever gesture is open, committing it as ONE entry. The body is identical to
     * GestureSession.end by design — a preview is open, the finger has lifted, and what the document
     * holds now becomes the edit. Kept here (rather than inherited) so each session owns its closers
     * and there is no base class whose only members are two six-line methods.
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

    private companion object {
        const val TAG = "TextGestureSession"
    }
}
