package com.redcut.engine.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.redcut.core.common.di.MainDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The preferred preview renderer: Media3's `CompositionPlayer` over the graph's `Composition`.
 *
 * Spec §8.4's *"class CompositionPlayerRenderer : PreviewRenderer   // preferred"*. It is preferred for
 * the reason §4.3 gives: it plays **the same compilation the export will encode**, so what the user
 * scrubs is the edit rather than an approximation of it, and *"preview / export parity is structural
 * rather than aspirational"* (§12.3).
 *
 * ### Why it is not the only one
 *
 * `CompositionPlayer` is marked `@ExperimentalApi` in Media3 1.9.4 — the annotation is on the class, not
 * on a corner of it — so the shape of this file is a hedge the spec asks for explicitly: *"an
 * `@ExperimentalApi` regression in Media3 is a config change, not a release blocker"*. The config is
 * `Media3PreviewRenderers.PREFER_COMPOSITION_PLAYER`; the alternative beside it is [ExoPlayerRenderer].
 *
 * ### The seam the abstraction protects
 *
 * Only four Media3 touches exist here: build a `CompositionPlayer`, hand it a composition, ask it for
 * its surface, seek. If the native core replaces this path (§13 Phase 5), it replaces *these* — the
 * interface, the state and the session rules above it do not move.
 *
 * The `@OptIn` is the Media3 spelling of "I know this is unstable": rule D1 keeps Media3 inside this
 * module, and this is the one file in it that leans on the newest part of the library.
 */
@OptIn(ExperimentalApi::class, UnstableApi::class)
internal class CompositionPlayerRenderer(
    private val context: Context,
    @param:MainDispatcher main: CoroutineDispatcher,
    logger: RedcutLogger,
) : Media3PreviewRenderer(main, logger) {

    override fun openPlayer(graph: RenderGraph): Player =
        CompositionPlayer.Builder(context).build().apply {
            setComposition(RenderGraphMapper.toComposition(graph))
        }

    /**
     * The Player API positions in milliseconds; the graph's ranges are microseconds and are passed to
     * Media3 as microseconds, so what rounds here is the playhead's landing point, never a trim.
     */
    override fun seekPlayer(player: Player, timelineUs: Long) {
        player.seekTo(timelineUs / MICROS_PER_MILLI)
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}
