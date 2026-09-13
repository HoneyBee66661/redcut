package com.redcut.engine.media3

import android.content.Context
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.PreviewRenderer
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Which of the two renderers the app gets — spec §8.4's *"Selected by a build flag; remote-disableable
 * if a device-specific `CompositionPlayer` bug appears in the field."*
 *
 * This is the selection the spec asks for, and not a layer above the two classes: one function, no
 * behaviour, and the choice is the whole of it. It lives here rather than in `:app` because the
 * decision is about Media3 — which experimental API is being leaned on, and what the alternative costs
 * — and `:app` should be reading a decision, not making one about a library it does not link against
 * directly.
 *
 * ### The two halves of the spec's sentence, and where each stands
 *
 * *Build flag*: [PREFER_COMPOSITION_PLAYER], flipped at compile time. That is what makes an
 * `@ExperimentalApi` regression in Media3 a config change rather than a release blocker.
 *
 * *Remote-disableable*: not yet. A device-specific bug in the field needs a config channel the MVP does
 * not have (Phase 4.x owns preferences; §8.4's "remote" implies more than that). Until it exists, the
 * honest statement is that the fallback is compiled in and one constant away, and that the field
 * escape hatch is owed — not that this file provides it.
 */
object Media3PreviewRenderers {

    /**
     * The renderer for this build.
     *
     * `logger` is forwarded rather than held: both renderers log through it, and a log line about a
     * preview should name the renderer that produced it.
     */
    fun create(
        context: Context,
        main: CoroutineDispatcher,
        logger: RedcutLogger,
    ): PreviewRenderer = if (PREFER_COMPOSITION_PLAYER) {
        CompositionPlayerRenderer(context = context, main = main, logger = logger)
    } else {
        ExoPlayerRenderer(context = context, main = main, logger = logger)
    }

    /**
     * The build flag. `true` because `CompositionPlayer` is the renderer that plays the compiled edit
     * (and therefore the one the export will agree with, §12.3) — the fallback exists for the day that
     * stops being true on someone's device, not because it is equal.
     */
    internal const val PREFER_COMPOSITION_PLAYER = true
}
