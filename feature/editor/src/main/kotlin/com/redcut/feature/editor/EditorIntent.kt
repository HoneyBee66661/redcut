package com.redcut.feature.editor

import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.FrameStep

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
 */
sealed interface EditorIntent {

    /** An intent that changes the document, and therefore goes through the history stack. */
    sealed interface Edit : EditorIntent

    /**
     * An intent that changes what the user is looking at or doing: the stage, the playhead, the
     * selection, or a history navigation. None of these is an edit, and none is undoable.
     */
    sealed interface View : EditorIntent

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

    /** Clear the selection (a tap on empty timeline space). */
    data object ClearSelection : View

    /**
     * Start a trim gesture on [edge] of [clipId] (FR-2.1).
     *
     * The command is applied as a PREVIEW, not pushed: the whole drag is one undo entry, and §7.3's
     * "Undo Trim" is what the user expects to see once, not once per frame of the gesture.
     */
    data class BeginTrim(val clipId: String, val edge: ClipEdge, val sourceTimeUs: Long) : Edit

    /** The drag moved: the edge is now at [sourceTimeUs]. */
    data class UpdateTrim(val sourceTimeUs: Long) : Edit

    /** The finger lifted: the preview becomes one history entry. */
    data object EndTrim : Edit

    /** The gesture was abandoned (a second finger, a system interruption): the preview is rolled back. */
    data object CancelTrim : Edit

    /**
     * Run a Cut tool at the playhead (FR-2.2–2.6).
     *
     * One intent for six tools, because they differ only in which command the document produces: the
     * availability rules and the command construction both live in the domain, next to the commands
     * they guard. A screen that decided what a tool meant would be a second place for those rules to
     * drift.
     */
    data class ApplyCut(val tool: CutTool) : Edit

    /**
     * Dismiss the import report (FR-1.4).
     *
     * An explicit intent rather than a timer: a report that says two of five files were unreadable is
     * worth reading, and one that disappears on its own is worth nothing.
     */
    data object DismissImport : View
}
