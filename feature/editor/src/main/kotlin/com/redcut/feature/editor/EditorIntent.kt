package com.redcut.feature.editor

import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.CutTool

/**
 * Everything the UI can ask the editor to do (spec §7.2).
 *
 * One sealed type rather than a method per gesture: it makes the set of things the UI
 * can do enumerable (and therefore reviewable), gives tests a vocabulary, and means a
 * new capability shows up as a compile error in the `when` that handles them.
 *
 * Only two are implemented today — the stage switch, and the two history moves the
 * document's own tests already cover. The rest of spec §7.2's list
 * (`TrimClip`, `SplitClip`, `MergeClips`, `SetSpeed`, `AddEffect`, `SeekTo`,
 * `StartExport`) arrives with the timeline (1.5-1.9) and the export service (4.1);
 * declaring them now would mean three unimplemented `when` branches per intent, which is
 * a worse lie than a short list.
 */
sealed interface EditorIntent {

    /** Show a different stage. Does not touch the document (spec §7.1). */
    data class SelectStage(val stage: Stage) : EditorIntent

    /** Step one entry back in history. */
    data object Undo : EditorIntent

    /** Step one entry forward in history. */
    data object Redo : EditorIntent

    /**
     * Import the given content URIs (FR-1.1–1.5).
     *
     * URIs as `String`, not `Uri`: the intent is the feature's vocabulary, and the feature
     * is the module that must stay testable on the JVM. Turning what SAF returned into a
     * string happens in the one place that has an `ActivityResultContracts` callback.
     *
     * The reading, probing and policy all happen off the main thread behind this intent;
     * what comes back is a document with new clips, or a report of what was refused.
     */
    data class ImportMedia(val uris: List<String>) : EditorIntent

    /**
     * Move the playhead to [us] (FR-2.2/2.3/2.5 act on where it is).
     *
     * Not an edit and not undoable: the playhead is where the user is LOOKING, and an undo that
     * also rewound the playhead would move the view under them every time they undid a trim.
     */
    data class SetPlayhead(val us: Long) : EditorIntent

    /** Select a clip: a tap in the timeline drew it, and the inspector follows the selection. */
    data class SelectClip(val clipId: String) : EditorIntent

    /** Clear the selection (a tap on empty timeline space). */
    data object ClearSelection : EditorIntent

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
    ) : EditorIntent

    /** The drag moved: the edge is now at [sourceTimeUs]. */
    data class UpdateTrim(val sourceTimeUs: Long) : EditorIntent

    /** The finger lifted: the preview becomes one history entry. */
    data object EndTrim : EditorIntent

    /** The gesture was abandoned (a second finger, a system interruption): the preview is rolled back. */
    data object CancelTrim : EditorIntent

    /**
     * Run a Cut tool at the playhead (FR-2.2–2.6).
     *
     * One intent for four tools, because they differ only in which command the document produces:
     * the availability rules and the command construction both live in the domain, next to the
     * commands they guard. A screen that decided what a tool meant would be a second place for those
     * rules to drift.
     */
    data class ApplyCut(val tool: CutTool) : EditorIntent

    /**
     * Dismiss the import report (FR-1.4).
     *
     * An explicit intent rather than a timer: a report that says two of five files were
     * refused is information the user may need to read twice, and a message that disappears
     * on its own is a message that gets missed. Dismissal is the UI's decision, made by the
     * person who read it.
     */
    data object DismissImport : EditorIntent
}
