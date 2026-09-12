package com.redcut.feature.editor

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
     * Dismiss the import report (FR-1.4).
     *
     * An explicit intent rather than a timer: a report that says two of five files were
     * refused is information the user may need to read twice, and a message that disappears
     * on its own is a message that gets missed. Dismissal is the UI's decision, made by the
     * person who read it.
     */
    data object DismissImport : EditorIntent
}
