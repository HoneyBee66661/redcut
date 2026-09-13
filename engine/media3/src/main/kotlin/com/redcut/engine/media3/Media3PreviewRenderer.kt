package com.redcut.engine.media3

import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.redcut.core.common.di.MainDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.PreviewRenderer
import com.redcut.core.media.PreviewState
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
 */
internal abstract class Media3PreviewRenderer(
    @param:MainDispatcher private val main: CoroutineDispatcher,
    protected val logger: RedcutLogger,
) : PreviewRenderer {

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val _state = MutableStateFlow<PreviewState>(PreviewState.Idle)

    override val state: StateFlow<PreviewState> = _state.asStateFlow()

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
            val opened = openOrFail(graph) ?: return@launch
            player = opened
            opened.addListener(listener)
            opened.setVideoSurfaceView(surface)
            opened.prepare()
            // After prepare and before play: seeking first is what stops the first frame the user
            // sees being frame zero of the composition rather than the frame under the playhead.
            pendingSeekUs?.let { seekPlayer(opened, it) }
            if (wantPlay) opened.play()
            _state.value = PreviewState.Ready(opened.isPlaying)
            try {
                awaitCancellation()
            } finally {
                opened.removeListener(listener)
                opened.release()
                player = null
                if (failureReason == null) _state.value = PreviewState.Idle
            }
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
    }

    override fun seekTo(us: Long) {
        val position = us.coerceAtLeast(0L)
        pendingSeekUs = position
        // A seek with no player is not a lost seek: `pendingSeekUs` is applied by the next attach,
        // which is the state the user is in when they scrub while the composition is still opening.
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
     * player that has not finished preparing is not playing because someone asked it to.
     */
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (failureReason == null && player != null) {
                _state.value = PreviewState.Ready(isPlaying)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            logger.d(TAG, "preview stopped: ${error.message}")
            fail(error.message ?: PLAYBACK_FAILED)
        }
    }

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
    }
}
