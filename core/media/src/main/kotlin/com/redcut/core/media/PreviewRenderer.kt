package com.redcut.core.media

import android.view.SurfaceView
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The preview's renderer, as an interface — spec §8.4, and rule D6 of §6.8.
 *
 * The spec states the shape and the reason together: *"Because `CompositionPlayer` is experimental"*,
 * and *"This is the single most valuable abstraction in the render layer: it means an `@ExperimentalApi`
 * regression in Media3 is a config change, not a release blocker."* So the members below are the spec's
 * own five — `attach`, `play`, `pause`, `seekTo`, `release` — plus its `state`, and nothing else. A
 * second layer over them would be the thing the abstraction exists to avoid.
 *
 * ### Why this interface is here, and not in :engine:media3
 *
 * §6.8 rule D6 says *"the UI holds the interface, never `CompositionPlayer` directly"*, and §4.1 rule 2
 * says a feature may not depend on an engine. Both hold at once only if the interface lives in a module
 * the editor may see — this one, which it already depends on — while the implementations stay in
 * `:engine:media3`, which is the only module allowed to speak Media3 (rule D1). The consequence is
 * deliberate: this file names a `SurfaceView` and a [RenderGraph] and no Media3 type at all.
 *
 * ### The frame is a surface, never a bitmap (§6.8 rule D5)
 *
 * [attach] takes a `SurfaceView` because the preview is surface/texture-based *from day one*: *"You
 * cannot retrofit a GL pipeline onto a Canvas-based preview. This is the single hardest mistake to
 * undo."* Nothing in this contract passes a frame through Kotlin.
 *
 * ### Threading
 *
 * Every member is called from the main thread — the one Media3 players are confined to — and every
 * implementation must honour that. The implementation does its own resource work off the caller's
 * stack (see `Media3PreviewRenderer`): [attach] returns immediately and reports progress through
 * [state], because acquiring a decoder may have to wait (§9.1).
 */
interface PreviewRenderer {

    /**
     * Binds [surface] and shows [graph] on it — the whole compiled edit, from its first frame.
     *
     * Non-suspending on purpose, and the reason is the spec's signature rather than a preference: the
     * caller is a composable, and §8.1's recompilation trigger is *any* `EditDocument.revision` change,
     * so this is called repeatedly while the user drags a clip edge. Acquiring a decoder can wait
     * (§9.1's "short wait instead of a crash"), so the wait happens inside the implementation and is
     * observable as [state]: `Preparing` until the first frame can be shown.
     *
     * Attaching again replaces what is attached: the previous session is torn down and its decoder
     * returned before the new one starts.
     */
    fun attach(surface: SurfaceView, graph: RenderGraph)

    fun play()

    fun pause()

    /**
     * Moves the preview to [us] — a position on the **timeline**, the same axis as the playhead.
     *
     * Timeline time rather than source time is the point: the graph resolves the frame's position in
     * its source file (`RenderLayer.Video.sourceRange`, §8.1), so the caller never recomputes that
     * mapping and cannot disagree with what the renderer showed.
     *
     * A seek before [attach] is remembered and applied when the first frame is ready, which is what
     * makes scrubbing before the player has finished opening show the frame the user stopped on.
     */
    fun seekTo(us: Long)

    /**
     * Gives everything back: the player, its decoder, and the surface binding.
     *
     * Called when the preview stops being visible — the user left the editor, or the app went to the
     * background — because a held decoder is a decoder the export path cannot have (§9.1). The
     * renderer stays usable: [attach] after [release] opens a new session.
     */
    fun release()

    /** What the preview is doing, for the UI to render. */
    val state: StateFlow<PreviewState>

    /**
     * Where the composition is, in microseconds — the SAME axis as [seekTo], and the playhead's own.
     *
     * ### Why this is here, and why the earlier reasoning against it was wrong
     *
     * This interface used to carry no position, and the argument was written down: *"during playback the
     * position advances on its own, and a state object that had to be republished per frame would be a
     * per-frame allocation for a number nothing reads."* The premise was true and the conclusion was the
     * bug. A device pass, in the user's words: *"saat gua play clip, preview jalan namun clip tidak
     * bergerak sesuai posisi frame di preview. harusnya ada link antara clip dan player"* — the frame
     * moved and the timeline did not, because nothing read the number. It is not nothing: it is the value
     * the playhead takes while the preview plays, and the playhead is what the tracks are drawn against
     * (FR-2.10). The mechanism that reasoning protected — a `PreviewState` that is not rebuilt per frame —
     * survives untouched; what changes is that the advancing number now travels, on a flow of its own.
     *
     * ### When it publishes
     *
     * While the player is playing, and once more at each of the moments no tick is running: an [attach]
     * that has opened, a [pause], a [seekTo], and a failure. So a paused renderer still answers "where are
     * you?" — which is what lets one flow serve both "the playhead follows playback" and "the playhead is
     * where the seek put it".
     *
     * A renderer with nothing attached publishes nothing, and holds `NO_POSITION`: the start of the
     * timeline, which is the honest answer for a preview showing nothing.
     *
     * ### The default, and why it is one
     *
     * An implementation that does not track a position inherits this getter and compiles unchanged — the
     * additive rule this interface already follows. It answers with ONE shared constant rather than a fresh
     * `MutableStateFlow` per read, because a getter that allocates would allocate for every reader of a
     * value that cannot change.
     */
    val positionUs: StateFlow<Long>
        get() = NO_POSITION
}

/**
 * The position of a renderer that does not report one: the start of the timeline.
 *
 * One instance, shared by every implementation that inherits [PreviewRenderer.positionUs]. A
 * `MutableStateFlow` built inside the getter would hand out a new object per read for a value that never
 * changes, which is the per-frame allocation the old comment was right to refuse — the mistake was the
 * conclusion it drew from that, not the frugality.
 */
private val NO_POSITION: StateFlow<Long> = MutableStateFlow(0L)

/**
 * What the preview is doing, as one value.
 *
 * The UI needs exactly two facts — may I draw a frame, and is it playing — so this carries those and not a
 * position. The reason once given for that here ("a number nothing reads") did not survive the device pass:
 * the number IS read, by the playhead. What did survive is the SHAPE, and it is worth keeping — the
 * position changes about thirty times a second while this value changes when playback starts and stops, so
 * the position travels as its own flow ([PreviewRenderer.positionUs]) and this object is not rebuilt per
 * frame.
 *
 * [Unavailable] is a state rather than an exception because there is a real, expected way to reach it:
 * a file whose codec the device does not have. The stage shows the sentence; the user can still trim
 * around it, which is the behaviour a crash would take away.
 */
sealed interface PreviewState {

    /** Nothing attached: no graph bound, or the last session ended. */
    data object Idle : PreviewState

    /** A graph is bound; the first frame is not ready yet. */
    data object Preparing : PreviewState

    /** Frames can be drawn. [isPlaying] is the player's own answer, not the last request. */
    data class Ready(val isPlaying: Boolean) : PreviewState

    /** No frames will be drawn, and [reason] is why. */
    data class Unavailable(val reason: String) : PreviewState
}
