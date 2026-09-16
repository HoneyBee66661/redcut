package com.redcut.feature.editor

/**
 * What the user currently has selected (spec §7.2).
 *
 * A sealed interface rather than a nullable id because the editor will select effects as well as
 * clips (§7.2's `Selection: None | Clip(id) | Effect(id)`), and "nothing selected" is a real state
 * the UI draws differently — not the absence of a value to be inferred from a null.
 */
sealed interface Selection {

    /** Nothing is selected: no clip outline, no inspector. */
    data object None : Selection

    /** One clip is selected. Its edges show their trim handles (FR-2.1). */
    data class Clip(val clipId: String) : Selection

    /**
     * One caption is selected: the inspector's text rows read and write it (FR-4.3, J-2).
     *
     * Selected by EFFECT rather than by clip, like [ToolState.MovingText] is — a caption belongs to the
     * document's effect stack, so there is no clip id to name. The tap that picks the caption up (on the
     * preview or on its timeline lane) is what selects it, which is why this variant arrived with the
     * caption features and not with the selection itself.
     */
    data class Text(val effectId: String) : Selection
}

/** The selected clip's id, or null when the selection is not a clip. */
val Selection.clipIdOrNull: String?
    get() = (this as? Selection.Clip)?.clipId

/**
 * This selection, made consistent with [clipIds].
 *
 * Called after every document change, and it is not busywork: FR-2.6 deletes a clip, and a
 * selection that still names the deleted id would leave the timeline highlighting nothing while
 * the inspector edits a clip that no longer exists. Clearing it here means no command has to
 * remember to.
 */
fun Selection.reconciledWith(clipIds: List<String>): Selection =
    if (this is Selection.Clip && clipId !in clipIds) Selection.None else this
