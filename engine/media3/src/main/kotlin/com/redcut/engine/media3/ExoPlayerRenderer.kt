package com.redcut.engine.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.MediaResourceBroker
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The fallback renderer: one `ExoPlayer` over a playlist built from the graph's video layers.
 *
 * Spec §8.4's *"class ExoPlayerRenderer : PreviewRenderer   // fallback"*, and the other half of the
 * hedge: `CompositionPlayer` is `@ExperimentalApi`, and this is what the app can be switched to by a
 * config change if a device-specific bug turns up in it.
 *
 * ### What it does reproduce
 *
 * The base video track, exactly: each layer becomes one clipped playlist item, so a **trim** is the
 * item's clip, a **split** is two items, and the playhead lands on the same frame the Cut stage shows —
 * because both come from the same [RenderGraph]. `ExoPlayer` supports neither effects nor a
 * `Composition`, so the graph's ordering and source ranges are the whole of what it can honour, and
 * clipping is passed in microseconds (not the millisecond setters), so a trim is not rounded.
 *
 * ### What it does not, stated rather than discovered
 *
 * No effects, no transforms, no reverse, no fades, no dissolves, no overlay layers — the features the
 * mapper does not map yet (§8.2, FR-4) are absent here for the same reason they are absent there. Per
 * clip *gain* and mute are also absent: `ExoPlayer` has one volume for the player rather than one per
 * playlist item, so muting is a `CompositionPlayer` behaviour until the audio chain lands (FR-3.2).
 *
 * ### The one piece of arithmetic
 *
 * A timeline position has to become a playlist position, and the units change on the way: the graph
 * measures a clip's speed in *timeline* terms (a 10 s source at 2× occupies 5 s of timeline), while
 * `ExoPlayer` reports and seeks within a clipped item in **media** time. So the offset into a window is
 * the timeline offset multiplied by the clip's speed — the inverse of the division the compiler already
 * did, and the reason this is written once, here, instead of at each call site.
 */
@OptIn(UnstableApi::class)
internal class ExoPlayerRenderer(
    private val context: Context,
    broker: MediaResourceBroker,
    main: CoroutineDispatcher,
    logger: RedcutLogger,
) : Media3PreviewRenderer(broker = broker, main = main, logger = logger) {

    /** The playlist, in timeline order. Empty until a graph is attached. */
    private var windows: List<PlaylistWindow> = emptyList()

    override fun openPlayer(graph: RenderGraph): Player {
        windows = graph.videoLayers.map { layer ->
            PlaylistWindow(
                uri = layer.source.uri,
                sourceInUs = layer.sourceRange.startUs,
                sourceOutUs = layer.sourceRange.endUs,
                timelineStartUs = layer.timeRange.startUs,
                speed = layer.speed,
            )
        }

        val exo = ExoPlayer.Builder(context).build()
        exo.setMediaItems(windows.map { it.toMediaItem() })
        // Speed is a property of the player, not of a playlist item, so each seam re-states the
        // incoming clip's speed: without this a 2× clip would play at 1× the moment it started.
        exo.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    exo.playbackParameters =
                        PlaybackParameters(windows.speedAt(exo.currentMediaItemIndex))
                }
            },
        )
        exo.playbackParameters = PlaybackParameters(windows.speedAt(index = 0))
        return exo
    }

    override fun seekPlayer(player: Player, timelineUs: Long) {
        if (windows.isEmpty()) return
        val index = windowIndexAt(timelineUs)
        val window = windows[index]
        val intoWindowUs = (timelineUs - window.timelineStartUs) * window.speed.toDouble()
        player.seekTo(index, intoWindowUs.toLong() / MICROS_PER_MILLI)
    }

    /**
     * The same arithmetic as [seekPlayer], backwards — because here the axis really does change.
     *
     * The preferred `CompositionPlayer` plays the composition itself, so reading its position is a unit
     * change and nothing more (that is the base class's version). `ExoPlayer` plays a playlist of clipped
     * items and answers in **media** time inside the current window: published straight into `positionUs`
     * it would put the playhead at a position that means nothing on the timeline — wrong on every item
     * after the first, and wronger the faster the clip runs — and the playhead is exactly the number the
     * user watches during playback (FR-2.10). The window's start and its speed are what [seekPlayer]
     * applied on the way in, so they are what this applies back on the way out.
     */
    override fun positionOf(player: Player): Long {
        val window = windows.getOrNull(player.currentMediaItemIndex) ?: return 0L
        val intoWindowUs = player.currentPosition * MICROS_PER_MILLI
        return window.timelineStartUs + (intoWindowUs / window.speed.toDouble()).toLong()
    }

    /**
     * Which playlist item contains [timelineUs].
     *
     * A scan rather than a search: the graph guarantees the video layers are contiguous and ordered
     * (its own `init`), so the last window that starts at or before the position is the one — and a
     * position before the first window (or past the last) clamps to the ends rather than to nothing.
     */
    private fun windowIndexAt(timelineUs: Long): Int =
        windows.indexOfLast { it.timelineStartUs <= timelineUs }.coerceAtLeast(0)

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}

/**
 * One playlist item: a slice of a source file, and where that slice sits on the timeline.
 *
 * The speed is carried for `seekPlayer`'s unit change and not as something `ExoPlayer` is told per item
 * — it cannot be, which is why the listener above exists.
 */
private data class PlaylistWindow(
    val uri: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val timelineStartUs: Long,
    val speed: Float,
)

/**
 * The item, clipped to the window's source range.
 *
 * The microsecond setters rather than `setStartPositionMs`/`setEndPositionMs`: those are the API's
 * millisecond form, and a trim is the one place where a millisecond is a visible difference. The
 * `@OptIn` on the class covers them.
 */
@OptIn(UnstableApi::class)
private fun PlaylistWindow.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(uri)
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(sourceInUs)
            .setEndPositionUs(sourceOutUs)
            .build(),
    )
    .build()

/** The speed to play the item at [index], or normal speed when there is no such item. */
private fun List<PlaylistWindow>.speedAt(index: Int): Float =
    getOrNull(index)?.speed ?: DEFAULT_SPEED

/** Normal speed, which is what the document model starts a clip at. */
private const val DEFAULT_SPEED = 1f
