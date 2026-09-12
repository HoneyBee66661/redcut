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
}
