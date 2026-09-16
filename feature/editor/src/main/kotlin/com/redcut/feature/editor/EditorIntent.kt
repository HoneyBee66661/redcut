package com.redcut.feature.editor

import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.ClipAdjustment
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.FrameStep
import com.redcut.domain.document.TextSpec

/**
 * Everything the UI can ask the editor to do (spec §7.2).
 *
 * One sealed type rather than a method per gesture: it makes the set of things the UI can do
 * enumerable (and therefore reviewable), gives tests a vocabulary, and means a new capability shows
 * up as a compile error in the `when` that handles them.
 *
 * ### Split into [Edit] and [View]
 *
 * Six phases in, the single flat list had grown to twelve members and the dispatcher's `when` had
 * grown with it — past the point where it read as a list of capabilities and towards a switch that
 * `detekt` measures. More importantly the flat list hid a distinction the editor turns on: some
 * intents change the DOCUMENT and belong on the history stack, and some only change where the user is
 * looking. Getting that wrong is the bug class this file's whole design exists to prevent — an undo
 * that also repositions the playhead, a stage tap that enters the undo stack.
 *
 * So the two kinds are now two sealed sub-interfaces, and the dispatcher has exactly two branches.
 * Two things follow:
 *
 * * the compiler enforces that every intent is one or the other — there is no `else` anywhere, so no
 *   future intent can quietly fall outside both handlers;
 * * the split is a place a reviewer can check the distinction at a glance instead of inferring it
 *   from twelve handler bodies.
 *
 * ### And the same again, one level down
 *
 * [Edit] grew the same way for the same reason: three workstreams' intents arrived (the timeline's
 * cuts and gestures, the preview viewport, the export sheet) and its handler's `when` went back to
 * being a switch that `detekt` measures. So the DRAG GESTURES are sub-interfaces of their own —
 * [TrimGesture], [AdjustGesture], and (FR-4.3) [TextGesture] — and each has four moments that only read
 * as a set when they sit together. A single-intent family needs no type: [Edit]'s handler covers those
 * directly.
 *
 * The rule this keeps: a new intent either joins a family (and the family's own `when` must handle it,
 * which the compiler checks) or takes a branch of its own. There is still no `else` to fall into.
 */
sealed interface EditorIntent {

    /** An intent that changes the document, and therefore goes through the history stack. */
    sealed interface Edit : EditorIntent

    /**
     * An intent that changes what the user is looking at or doing: the stage, the playhead, the
     * selection, or a history navigation. None of these is an edit, and none is undoable.
     */
    sealed interface View : EditorIntent

    /**
     * The trim gesture's four moments: the finger went down on an edge, moved, lifted, or the gesture
     * was abandoned (FR-2.1).
     *
     * A family rather than four unrelated [Edit]s, because they are one conversation with the document:
     * the drag is a PREVIEW and only the lift records a history entry (§7.3's "Undo Trim"), which is a
     * rule about the four of them together and cannot be read off any one of them.
     */
    sealed interface TrimGesture : Edit

    /** The adjust gesture's four moments (FR-3.1–3.4, 3.9). See [TrimGesture] — the same lifecycle. */
    sealed interface AdjustGesture : Edit

    /**
     * The caption drag's four moments (FR-4.3). See [TrimGesture] — the same lifecycle, for the same
     * reason, and it is the third family of that shape rather than a fourth mechanism.
     *
     * What it moves is a text overlay's BOX on the canvas, not a clip and not an edge: the caption follows
     * the finger as a preview, the lift records one entry, and the undo entry reads "Move text".
     */
    sealed interface TextGesture : Edit

    /**
     * The caption restyle's four moments (FR-4.3, J-2). See [TrimGesture] — the same lifecycle, and the
     * reason is the same one sentence: the inspector rows follow the finger as a preview and the lift
     * records ONE entry, so dragging the size slider from 48 to 64 does not leave sixteen "Text style"
     * entries behind it.
     */
    sealed interface TextStyleGesture : Edit

    /**
     * The caption's timeline-edge drag (FR-4.3, task 3.6's card 4). See [TrimGesture] — the same
     * lifecycle again: an edge follows the finger as a preview and the lift records one entry, labelled
     * "Text timing" by the command it previews.
     */
    sealed interface TextTrimGesture : Edit

    /** Show a different stage. Does not touch the document (spec §7.1). */
    data class SelectStage(val stage: Stage) : View

    /** Step one entry back in history. */
    data object Undo : View

    /** Step one entry forward in history. */
    data object Redo : View

    /**
     * Add the picked media to the timeline (FR-1.1–1.5).
     *
     * An [Edit]: one import is one entry in the history, however many files were picked (FR-1.2).
     */
    data class ImportMedia(val uris: List<String>) : Edit

    /**
     * Move the playhead to [us] (FR-2.2/2.3/2.5 act on where it is).
     *
     * Not an edit and not undoable: the playhead is where the user is LOOKING, so undo must not step
     * through it.
     */
    data class SetPlayhead(val us: Long) : View

    /**
     * Move the playhead by one frame (FR-2.9).
     *
     * Not a cut and not a tool: frame-stepping changes where the playhead is, exactly like tapping the
     * timeline, so it goes through the same view state and never touches history. It exists because
     * tapping is not frame-accurate and cutting to a frame is.
     */
    data class StepPlayhead(val step: FrameStep) : View

    /** Select a clip (§7.2). */
    data class SelectClip(val clipId: String) : View

    /**
     * Select a caption (FR-4.3, J-2).
     *
     * The twin of [SelectClip] for the effect stack, and a [View] for the same reason: which row the
     * inspector shows is where the user is LOOKING, not a change to the document, so an undo must not
     * step through it. The id arrives from a hit test (a tap on the caption in the preview or on its
     * timeline lane), and a caption the document does not hold is refused rather than shown — the same
     * stale-id rule [SelectClip]'s handler keeps.
     */
    data class SelectTextOverlay(val effectId: String) : View

    /** Clear the selection (a tap on empty timeline space). */
    data object ClearSelection : View

    /**
     * Select a LANE — the track itself, not a clip in it (UI revision 2, §WS E / Task E1).
     *
     * A [View], like [SelectClip]: which lane the user is working with is where they are LOOKING, not a
     * change to the document, so an undo must not step through it. The id arrives from a hit test — a tap
     * on a lane's background, which is what `TimelineHit.Track` answers for — and a lane the document does
     * not hold is refused rather than shown, the same stale-id rule [SelectClip]'s handler keeps.
     *
     * The TOGGLE is not here: the gesture layer already knows which lane is selected and turns a second
     * tap into a [ClearSelection], exactly as it does for a clip.
     */
    data class SelectTrack(val trackId: String) : View

    /**
     * Start a trim gesture on [edge] of [clipId] (FR-2.1).
     *
     * The command is applied as a PREVIEW, not pushed: the whole drag is one undo entry, and §7.3's
     * "Undo Trim" is what the user expects to see once, not once per frame of the gesture.
     */
    data class BeginTrim(
        val clipId: String,
        val edge: ClipEdge,
        val sourceTimeUs: Long,
    ) : TrimGesture

    /** The drag moved: the edge is now at [sourceTimeUs]. */
    data class UpdateTrim(val sourceTimeUs: Long) : TrimGesture

    /** The finger lifted: the preview becomes one history entry. */
    data object EndTrim : TrimGesture

    /** The gesture was abandoned (a second finger, a system interruption): the preview is rolled back. */
    data object CancelTrim : TrimGesture

    /**
     * Run a Cut tool at the playhead, on the clip the user is working with (FR-2.2–2.6, WS C6).
     *
     * One intent for six tools, because they differ only in which command the document produces: the
     * availability rules and the command construction both live in the domain, next to the commands
     * they guard. A screen that decided what a tool meant would be a second place for those rules to
     * drift.
     *
     * It carries the CLIP the strip resolved rather than leaving the command to be built from the playhead
     * alone. The playhead is one number while the timeline has more than one lane, so a command built from
     * it can land on a clip of another lane that merely sits at the same place in the end-to-end reading —
     * the music bed under the picture is exactly that case. The LANE is deliberately NOT in the payload: a
     * clip is on exactly one lane and `trackIdOf` is the document's answer to which, so carrying it here
     * would be a second copy of a fact that has one authority.
     */
    data class ApplyCut(val tool: CutTool, val clipId: String) : Edit

    /**
     * Merge every fusable run of clips on the selected LANE (§WS E / Task E2-E3, FR-2.4).
     *
     * The lane selection's first real operation, and the reason it exists: [ApplyCut] acts on the clip at
     * the playhead, and there was no way to say "do it to this whole lane". The lane id is carried rather
     * than read off the selection here, for the same reason [ApplyCut] carries the tool: an intent that had
     * to re-read the state could act on a selection that had moved since the tap.
     *
     * There is no tool parameter: `MergeTrackClips` is the only lane operation so far, and a
     * `TrackTool` enum with one member would be a shape without a second case to justify it. It arrives
     * when the second operation does.
     */
    data class MergeTrack(val trackId: String) : Edit

    /**
     * Move the clip at the playhead to [toIndex] (FR-2.7).
     *
     * The index comes from the document's own arithmetic ([reorderTargetIndex]) rather than from the
     * drag handler, so "where does a drop here land" is a tested question with edge cases instead of a
     * line of gesture code. Undoable like any other edit — a reorder the user did not mean is worth
     * one tap to take back.
     */
    data class ApplyReorder(val clipId: String, val toIndex: Int) : Edit

    /**
     * Start adjusting a clip control (FR-3.1–3.4, 3.9).
     *
     * Also selects the clip, because a slider and its selection are the same thought: the user reached
     * for the control of the clip they are looking at, and the inspector draws from the selection.
     */
    data class BeginAdjust(val clipId: String, val adjustment: ClipAdjustment) : AdjustGesture

    /** A slider's new value, mid-drag. */
    data class UpdateAdjust(val value: Float) : AdjustGesture

    /** The drag ended: the preview becomes ONE undoable entry, not one per frame. */
    data object EndAdjust : AdjustGesture

    /** The drag was abandoned (a second finger, a system interruption): the preview is rolled back. */
    data object CancelAdjust : AdjustGesture

    /**
     * Add a text overlay at the playhead (FR-4.3, spec task 3.6).
     *
     * An [Edit]: a caption is document structure, so adding one changes the document and is worth one undo
     * entry — the same as any other edit. It carries nothing, because everything the added caption needs is
     * derived where the command is built: the range starts at the playhead, the position is the model's own
     * caption band, and the id comes from the editor's [com.redcut.core.common.IdSource] rather than from a
     * control that could invent one.
     *
     * The content a new caption holds, and the fact that editing it is the inspector's card rather than
     * this one, are stated in the ViewModel where the placeholder lives.
     */
    data object AddText : Edit

    /**
     * Start dragging the caption [effectId] (FR-4.3).
     *
     * The caption is named rather than assumed from a selection, because the editor has no effect
     * selection yet: the press itself is what picks the caption up, so the id comes from the hit test that
     * found it under the finger.
     */
    data class BeginTextDrag(val effectId: String) : TextGesture

    /**
     * The drag moved: the caption's box is now centred on ([centerX], [centerY]), in canvas fractions.
     *
     * An ABSOLUTE position rather than a delta, which is the shape the trim gesture sends too: the gesture
     * layer remembers the grab point, and the document is told where the caption should be rather than how
     * far it has moved. A delta would accumulate every dropped or coalesced frame into a drift the user
     * would see as the caption lagging their finger.
     */
    data class UpdateTextDrag(val centerX: Float, val centerY: Float) : TextGesture

    /** The finger lifted: the preview becomes one history entry. */
    data object EndTextDrag : TextGesture

    /** The gesture was abandoned (a press that never moved, a system interruption): the preview rolls back. */
    data object CancelTextDrag : TextGesture

    /**
     * Start restyling the caption [effectId] (FR-4.3, J-2).
     *
     * The WHOLE [spec] arrives on every moment of this family rather than one field at a time: the row
     * that started the gesture copies the caption's current spec, changes its own field, and sends the
     * result — so the command the gesture previews is the same whole-value `SetTextStyle` a single tap
     * produces, and the two cannot disagree about what a style edit means.
     *
     * Like [BeginAdjust], this also selects the caption: a row and its selection are one thought, and
     * the inspector draws from the selection.
     */
    data class BeginTextStyle(val effectId: String, val spec: TextSpec) : TextStyleGesture

    /** A style row's new value, mid-drag, as the whole spec it results in. */
    data class UpdateTextStyle(val spec: TextSpec) : TextStyleGesture

    /** The drag ended: the preview becomes one history entry. */
    data object EndTextStyle : TextStyleGesture

    /** The gesture was abandoned (a system interruption): the preview rolls back. */
    data object CancelTextStyle : TextStyleGesture

    /**
     * Start dragging one END of the caption [effectId] on the timeline (FR-4.3's card 4).
     *
     * [us] is a TIMELINE time, not a source time — a caption has no source, so the crossing the trim
     * gesture makes ([EditorIntent.BeginTrim] takes the clip's own source time) does not exist here: the
     * finger's position IS the value the command needs. The command clamps it, exactly as it clamps the
     * inspector's fields.
     */
    data class BeginTextTrim(
        val effectId: String,
        val edge: ClipEdge,
        val us: Long,
    ) : TextTrimGesture

    /** The drag moved: the edge is now at [us] on the timeline. */
    data class UpdateTextTrim(val us: Long) : TextTrimGesture

    /** The finger lifted: the preview becomes one history entry. */
    data object EndTextTrim : TextTrimGesture

    /** The gesture was abandoned (a press that never moved, a system interruption): the preview rolls back. */
    data object CancelTextTrim : TextTrimGesture

    /**
     * Set the preview viewport rect centre and zoom (spec UI revision 2, §WS F / Task F3).
     *
     * Pinch to zoom and drag to pan emit this intent with the normalised centre coordinates
     * and zoom level.
     */
    data class SetViewport(
        val centerX: Float,
        val centerY: Float,
        val zoom: Float,
    ) : Edit

    /**
     * Dismiss the import report (FR-1.4).
     *
     * An explicit intent rather than a timer: a report that says two of five files were unreadable is
     * worth reading, and one that disappears on its own is worth nothing.
     */
    data object DismissImport : View

    /**
     * Open the export sheet (FR-5.1).
     *
     * A [View] intent, and that is the whole point of the sheet: choosing a resolution changes what the user
     * is about to DO, not what the document IS. On the history stack it would be an "edit" whose undo does
     * nothing visible, which is the class of bug this file's split exists to prevent.
     */
    data object OpenExport : View

    /** Close the export sheet without exporting. */
    data object DismissExport : View

    /**
     * The frame size the user picked in the export sheet (FR-5.1).
     *
     * Carries the [CanvasSpec] rather than an index or a name, so the sheet and the document speak about a
     * frame size in exactly the same terms — and so the state cannot hold a size the domain does not have.
     */
    data class SetExportResolution(val resolution: CanvasSpec) : View

    /**
     * Add or remove a keyframe at the playhead for the selected clip's keyframable property (WS K).
     *
     * An [Edit]: keying a clip changes the document and is worth one undo entry, the same as any other
     * edit. The property acted on is derived from the selection and the clip's own keyframes map (see
     * the transport helpers), so this intent carries no property of its own — the transport keys the
     * clip's active keyframable property, and the per-property picker is the inspector's later card.
     */
    data object ToggleKeyframe : Edit

    /** Move the playhead to the previous key of the selected clip's active keyframable property. */
    data object PrevKeyframe : View

    /** Move the playhead to the next key of the selected clip's active keyframable property. */
    data object NextKeyframe : View
}
