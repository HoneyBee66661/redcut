package com.redcut.engine.media3

import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.redcut.core.common.di.MainDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.MediaResourceBroker
import com.redcut.core.media.PreviewRenderer
import com.redcut.core.media.PreviewState
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the two Media3 renderers do the same way: one [Player] at a time, the surface binding,
 * the session's lifetime, and the state the UI reads.
 *
 * This is *below* [PreviewRenderer], not an abstraction over it — spec §8.4 names two renderers and one
 * interface, and this class adds no third concept to that set. What it removes is a second copy of the
 * session rules: a player that is built twice in two files is a place where one of them will eventually
 * forget to release.
 *
 * ### Why [attach] launches rather than blocks
 *
 * `PreviewRenderer.attach` is not suspending (the spec's signature), and opening a player is not
 * instant: it takes a decoder slot from the broker (§9.1), which can wait. So the caller gets control
 * back immediately and the work runs in [scope], on the main dispatcher — the single thread Media3
 * players are confined to. The session is a [Job] rather than a field to be cleared in five places,
 * which is what makes "release the decoder however this ends" a `finally` instead of a checklist.
 *
 * ### What ends a session
 *
 * Four things, and they all funnel through the same teardown: another [attach] (a new revision), a
 * caller's [release], a player error, and the renderer being cleared. The player is released and the
 * decoder slot goes back in every one of them, because §9.1's failure mode — *"a short wait instead of
 * a crash"* — only holds while every path returns what it took.
 *
 * ### Resources and threads (§9)
 *
 * **The player is built inside a broker slot.** §9.1 is absolute about this: *"Nothing in the app may
 * construct a `MediaCodec` (or a Media3 `Transformer`/`CompositionPlayer`) except through the broker."*
 * The slot is taken before the player exists and held until the session ends — the session coroutine's
 * own loop is what keeps it (§ "The tick and the session are the same coroutine" below) — which is what
 * makes §9.1's two consequences true by construction: preview and export cannot hold codecs at the same
 * time, and a thumbnail decode queues behind playback rather than racing it. A `release()` gives the slot
 * back, so a preview nobody is looking at is not a decoder an export cannot have.
 *
 * **One thread.** The session runs on [main] — the `Looper` thread Media3 players are confined to, and
 * the thread every [PreviewRenderer] call arrives on. Media3 owns its own playback and GL threads below
 * that (§9.2's *"GL render: Media3's dedicated GL thread"*); nothing here reaches across them.
 *
 * **No frame is ever a Kotlin object.** The surface is handed to the player once and the frames go to
 * it directly — §9.3's *"No frame ever becomes a `Bitmap` in Kotlin state. Frames live as GPU
 * textures."* Nothing in this class allocates per frame, and nothing could: it is handed no frame at
 * all. That is also §6.4 rule 2's precondition — *"No Java object graphs cross the boundary. Frames move
 * as `AHardwareBuffer`/`Surface`/texture IDs, never as `ByteArray` or `Bitmap`"* — which binds hard only
 * once the C++ core is in the path (Phase 5); what this path does today is decline to create the problem
 * it would then have to solve.
 *
 * What DOES run on a timer is one `Long` (see [positionUs]): the position the UI needs so the playhead can
 * follow playback (FR-2.10). It is a number on the main thread, not a frame, and a `MutableStateFlow`
 * drops a value equal to the one it holds — so a paused player, and a player whose position has not moved
 * by the time the tick fires, cost a comparison and no allocation.
 *
 * ### The tick and the session are the same coroutine
 *
 * [holdSession] holds the player open by looping, where it used to hold it open with `awaitCancellation`.
 * The loop is not a second job beside the session, and that is the whole reason for the shape: a tick
 * launched into [scope] would have to be cancelled by hand, and the one path that forgot would leave a
 * coroutine reading a released player — or, worse, holding a decoder past the end of the session (§9.1).
 * A coroutine that IS the session cannot outlive it: whatever ends the session (another [attach], a
 * [release], an error) cancels this loop at its next `delay`, and the `finally` below returns the decoder
 * exactly as it did before.
 */
internal abstract class Media3PreviewRenderer(
    private val broker: MediaResourceBroker,
    @param:MainDispatcher private val main: CoroutineDispatcher,
    protected val logger: RedcutLogger,
) : PreviewRenderer {

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val _state = MutableStateFlow<PreviewState>(PreviewState.Idle)

    override val state: StateFlow<PreviewState> = _state.asStateFlow()

    /** The backing store for [positionUs]; see the interface for what the value means and when it moves. */
    private val _positionUs = MutableStateFlow(0L)

    /**
     * Where the composition is on the timeline — FR-2.10, and the missing half of the device pass's
     * *"harusnya ada link antara clip dan player"*.
     *
     * The tick below is the only writer while the player runs; the seeks and the opens write it once so a
     * player that is not moving still answers where it is. Main thread only, like every other member here:
     * the tick, the publishes and the reads all happen on [main], so the flow itself is the only thing that
     * crosses a thread.
     */
    override val positionUs: StateFlow<Long> = _positionUs.asStateFlow()

    /** The open session, or null when nothing is attached. Main thread only, like [player]. */
    private var session: Job? = null

    /** The player of the open session. Main thread only — the thread contract Media3 requires. */
    private var player: Player? = null

    /** The last seek, kept so a seek that arrives before the first frame is not lost. */
    private var pendingSeekUs: Long? = null

    /** What the caller last asked for: after a rebuild, playback continues if it was playing. */
    private var wantPlay = false

    /** Why the current session failed, or null. Cleared by the next [attach]. */
    private var failureReason: String? = null

    /**
     * Builds the player for [graph], already carrying its media. Called on the main thread.
     *
     * Allowed to throw: an implementation that cannot represent a graph should say so and let [attach]
     * degrade to [PreviewState.Unavailable] rather than hand back a player that will never render.
     */
    protected abstract fun openPlayer(graph: RenderGraph): Player

    /** Moves [player] to [timelineUs]. Called on the main thread, after the player is prepared. */
    protected abstract fun seekPlayer(player: Player, timelineUs: Long)

    override fun attach(surface: SurfaceView, graph: RenderGraph) {
        endSession()
        failureReason = null

        if (graph.videoLayers.isEmpty()) {
            // Nothing to play: an empty document is a stage with a sentence in it, not a player.
            _state.value = PreviewState.Idle
            return
        }

        _state.value = PreviewState.Preparing
        session = scope.launch {
            // The whole player lifetime sits inside one decoder slot (§9.1). `withDecoder` suspends
            // rather than failing when the pool is full, so the wait is the spec's "short wait instead
            // of a crash"; the session coroutine's own loop (see `holdSession`) is what HOLDS the slot,
            // and the broker's own `withPermit` is what gives it back on every way out — a new
            // revision, a release, an error, or the renderer being cleared.
            broker.withDecoder { holdSession(graph, surface) }
        }
    }

    /**
     * Opens the player and keeps it open until this coroutine is cancelled.
     *
     * A function rather than a block inside [attach] so that the decoder slot's scope is one readable
     * span of code: everything between taking the slot and giving it back is in here, and nothing
     * outside it can accidentally outlive the permit.
     */
    private suspend fun holdSession(graph: RenderGraph, surface: SurfaceView) {
        val opened = openOrFail(graph) ?: return
        player = opened
        opened.addListener(listener)
        opened.setVideoSurfaceView(surface)
        opened.prepare()
        // After prepare and before play: seeking first is what stops the first frame the user sees
        // being frame zero of the composition rather than the frame under the playhead.
        pendingSeekUs?.let { seekPlayer(opened, it) }
        if (wantPlay) opened.play()
        _state.value = PreviewState.Ready(opened.isPlaying)
        publishPosition()
        try {
            // The position tick (FR-2.10), and — in the same breath — the thing that keeps this session
            // open. It replaces `awaitCancellation` rather than running beside it: a tick launched into
            // [scope] would be a second job to cancel, and a session that IS a loop cannot be outlived by
            // its own tick (see the class doc).
            //
            // A Media3 player has no per-frame callback, so the position is polled. `positionOf` puts the
            // answer on the TIMELINE axis — the axis `seekTo` takes — and the guard is what "published
            // only while the player is playing" means in practice: a paused player's position does not
            // change, and the one publish that matters there is `pause`'s, below.
            while (true) {
                delay(POSITION_TICK_MS)
                if (opened.isPlaying) _positionUs.value = positionOf(opened)
            }
        } finally {
            opened.removeListener(listener)
            opened.release()
            player = null
            if (failureReason == null) _state.value = PreviewState.Idle
        }
    }

    /**
     * Playback keeps whatever the session is doing with the decoder: a paused player still holds one,
     * and §9.1 counts a held decoder whether or not it is running.
     */
    override fun play() {
        wantPlay = true
        player?.play()
    }

    override fun pause() {
        wantPlay = false
        player?.pause()
        // The tick is not running now, and this is where the player stopped — which is the position the
        // playhead should keep. Without it the playhead would stay where the last tick left it, up to a
        // tick short of the truth.
        publishPosition()
    }

    override fun seekTo(us: Long) {
        val position = us.coerceAtLeast(0L)
        pendingSeekUs = position
        // The request IS the answer here, and asking the player instead would be asking a worse
        // question: a player that has not opened yet reports nothing, and the seek it has been given —
        // remembered in `pendingSeekUs` and applied by the next attach — is what it will show.
        _positionUs.value = position
        player?.let { seekPlayer(it, position) }
    }

    override fun release() {
        wantPlay = false
        endSession()
    }

    /**
     * The player telling us something, in the two cases the UI can see.
     *
     * `onIsPlayingChanged` is the honest source for "is it playing": [play] records an *intent*, and a
     * player that has not finished preparing is not playing because someone asked it to. It is also the
     * end of playback for a reason nobody asked for — the composition reaching its last frame, a buffer
     * running dry — and each of those stops the tick, so the position is read once here to land the
     * playhead exactly where playback stopped rather than at its last tick.
     */
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (failureReason == null && player != null) {
                _state.value = PreviewState.Ready(isPlaying)
                publishPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            logger.d(TAG, "preview stopped: ${error.message}")
            publishPosition()
            fail(error.message ?: PLAYBACK_FAILED)
        }
    }

    /**
     * Reads the player's position and publishes it, for the moments the tick is not running.
     *
     * The player is the authority on where it is; a renderer that is paused, that has just opened, or that
     * has just failed would otherwise report the last position published while it was playing, which is a
     * stale answer to "where is the playhead?".
     */
    private fun publishPosition() {
        player?.let { _positionUs.value = positionOf(it) }
    }

    /**
     * Where [player] is, in microseconds on the TIMELINE — the axis [seekTo] takes, so that what this
     * publishes and what the UI seeks to are the same kind of number.
     *
     * Open rather than final because the axis is the implementation's business, exactly as it is for
     * [seekPlayer]: the preferred `CompositionPlayer` plays the composition itself, so its
     * `currentPosition` IS a timeline position and this default is only a unit change. `ExoPlayer` plays a
     * playlist of clipped items and reports media time inside the current one, so it overrides this with
     * the inverse of its own seek arithmetic. Two players, two answers — the same reason there are two
     * [seekPlayer]s.
     */
    protected open fun positionOf(player: Player): Long = player.currentPosition * MICROS_PER_MILLI

    /**
     * Opens the player, or reports why it could not be opened.
     *
     * The catch is broad on purpose. Media3 signals a refusal with whatever the failing component
     * threw — a codec this device does not have, an output spec it cannot honour — and the file the
     * user picked is not the app's to crash over: FR-1 accepts files the probe passed, and a file the
     * *decoder* later refuses is a preview that cannot be drawn, not a bug in the editor. The stage
     * shows the reason; the timeline stays editable.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun openOrFail(graph: RenderGraph): Player? = try {
        openPlayer(graph)
    } catch (refused: RuntimeException) {
        logger.d(TAG, "preview could not start: ${refused.message}")
        fail(refused.message ?: PLAYBACK_FAILED)
        null
    }

    /**
     * Ends the session, and everything it holds, on the way out.
     *
     * `session?.cancel()` is the whole teardown: the session's `finally` releases the player and gives
     * the decoder back, so cancelling a job that has not started yet is as correct as cancelling one
     * that is mid-playback.
     */
    private fun endSession() {
        session?.cancel()
        session = null
    }

    private fun fail(reason: String) {
        failureReason = reason
        _state.value = PreviewState.Unavailable(reason)
        endSession()
    }

    private companion object {
        const val TAG = "PreviewRenderer"

        /** Shown when a player reports an error without a message of its own. */
        const val PLAYBACK_FAILED = "This clip could not be previewed"

        /**
         * How often the position is read while the player runs (FR-2.10). One frame at 30 fps, which is
         * the rate the playhead it feeds is drawn at (FR-5.2 fixes the MVP at 30 fps): reading faster
         * would publish values no frame of the UI could show, and slower would let the playhead lag the
         * picture by a visible amount.
         */
        const val POSITION_TICK_MS = 33L

        /** Media3's `Player` API measures in milliseconds; this interface measures in microseconds. */
        const val MICROS_PER_MILLI = 1_000L
    }
}
