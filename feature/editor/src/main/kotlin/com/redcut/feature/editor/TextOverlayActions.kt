package com.redcut.feature.editor

import com.redcut.core.common.IdSource
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.document.AddTextOverlay
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import com.redcut.domain.document.UndoStack

private const val TAG = "TextOverlayActions"

/** The placeholder a fresh caption opens with; editing it is the inspector's card (J-2). */
private const val NEW_CAPTION_CONTENT = "Text"

/** The default length of a caption added at the playhead; the timing fields then adjust it. */
private const val NEW_CAPTION_DURATION_US = 3_000_000L

/**
 * Adds a caption at the playhead (FR-4.3, spec task 3.6), as a top-level function rather than a
 * ViewModel method because the ViewModel's function budget was measured against detekt's
 * `TooManyFunctions` (see `EditorViewModel`'s split history: GestureSession took the gestures, this
 * file takes the caption's one edit).
 *
 * The editor's internals arrive as lambdas, the same shape [GestureSession] documents: the stack is
 * replaced wholesale on reopen, the UI state is a snapshot the caller owns, and the autosave/publish
 * pair belong to the ViewModel's lifecycle, not to an edit. The command itself needs three decisions
 * — WHERE it starts (the playhead, which the user has just positioned at the moment they want words
 * on screen), HOW LONG it lasts (the default three seconds, adjusted by the inspector's timing
 * fields afterwards), and its id (from the editor's [IdSource], because commands never mint their
 * own).
 *
 * ### The one refusal, and why it is not merely the button's
 *
 * A caption on a document with no clips is refused. Nothing would render it — the render compiler
 * intersects a Document-scoped overlay with the finished video, and there is no video — so it would
 * be an effect the user can neither see nor find, which is exactly what `AddTextOverlay` refuses at
 * the command level for a blank caption. The Effect stage's button is disabled on the same fact (one
 * rule, two readers), and this is the reader that has to hold when the intent arrives from anywhere
 * else.
 */
internal fun applyAddTextOverlay(
    history: () -> UndoStack,
    state: () -> EditorUiState,
    ids: IdSource,
    logger: RedcutLogger,
    autosave: () -> Unit,
    publish: () -> Unit,
) {
    if (history().current.clips.isEmpty()) {
        logger.d(TAG, "no caption: there are no clips to render it over")
        return
    }
    val startUs = state().playheadUs
    logger.d(TAG, "add text at $startUs")
    history().execute(
        AddTextOverlay(
            effectId = "text-${ids.next()}",
            spec = TextSpec(content = NEW_CAPTION_CONTENT),
            timeRange = TimeRange(startUs, startUs + NEW_CAPTION_DURATION_US),
        ),
    )
    autosave()
    publish()
}
