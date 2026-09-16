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

    /**
     * One LANE is selected — the track, not a clip in it (UI revision 2, §WS E / Task E1).
     *
     * Selected by TRACK ID rather than by anything in the selection's own terms because a lane is the
     * document's own object: the id is the one `TimelineHit.Track` carries and the one every command's
     * `trackId` names, so a selection made by a tap is already in the shape the commands that act on a
     * lane will need (E2's merge, and the toolbar's track tools).
     *
     * The rule it exists for, stated the way the tap implements it: tapping a CLIP selects the clip;
     * tapping the lane that clip sits in — anywhere the lane's background shows — selects the TRACK;
     * tapping whichever is already selected deselects. Clip and track selection are two answers to one
     * question, so they replace each other rather than stacking: the inspector and the toolbar each show
     * one thing, and a state holding both would have to pick a winner at every read anyway.
     */
    data class Track(val trackId: String) : Selection
}

/** The selected clip's id, or null when the selection is not a clip. */
val Selection.clipIdOrNull: String?
    get() = (this as? Selection.Clip)?.clipId

/** The selected lane's id, or null when the selection is not a track (§WS E / Task E1). */
val Selection.trackIdOrNull: String?
    get() = (this as? Selection.Track)?.trackId

/**
 * This selection, made consistent with [clipIds].
 *
 * Called after every document change, and it is not busywork: FR-2.6 deletes a clip, and a
 * selection that still names the deleted id would leave the timeline highlighting nothing while
 * the inspector edits a clip that no longer exists. Clearing it here means no command has to
 * remember to.
 *
 * [trackIds] is the same rule for the lane selection: a track the document no longer holds — the only
 * way a `Selection.Track` can go stale, since no command removes a lane yet — must not leave the
 * toolbar showing track tools for a lane that is not there.
 */
fun Selection.reconciledWith(clipIds: List<String>, trackIds: List<String>): Selection = when {
    this is Selection.Clip && clipId !in clipIds -> Selection.None
    this is Selection.Track && trackId !in trackIds -> Selection.None
    else -> this
}
