package com.redcut.core.media

import android.graphics.Bitmap
import com.redcut.core.common.logging.RedcutLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The still the stage shows while a trim edge is dragged (FR-2.1), at the stage's size.
 *
 * ### Where this sits now that the renderer exists
 *
 * Phase 1.11's other half landed: §8.4's `PreviewRenderer` — `CompositionPlayerRenderer` with an
 * `ExoPlayerRenderer` fallback — plays the compiled `RenderGraph` onto the stage's `SurfaceView`, so
 * the frame at the playhead is the player's and never passes through this class. What is left here is
 * the one question a player cannot answer: **what frame is the trim edge at**. A trim edge is dragged
 * into media the clip has trimmed away, and those frames are by definition not in the composition
 * being played — so the still is not a stand-in for the renderer, it is the instrument for a different
 * picture.
 *
 * That still reuses the pipeline that already exists and is tested: the same `MediaMetadataRetriever`
 * decode as the timeline's thumbnails, behind the same [MediaResourceBroker] semaphore (§9.1), in a
 * cache bounded by bytes (§9.3).
 *
 * ### Why a bigger frame than a thumbnail
 *
 * 160 px is the right size for a filmstrip and a visibly blurry one for a stage that fills the screen,
 * so this gets its own source at [PREVIEW_WIDTH_PX]. It is the same decode arithmetic with a different
 * number, which is why [AndroidFrameSource] takes the width rather than hard-coding it — and taking
 * the source as a PORT (rather than building one from a Context) is what keeps this class
 * constructible in a JVM test with a fake.
 */
@Singleton
class PreviewFrames @Inject constructor(
    @param:PreviewFrameSource private val source: ThumbnailSource,
    private val logger: RedcutLogger,
) {
    private val store = ThumbnailStore(source = source, logger = logger)

    /**
     * The frame at [positionUs] in [uri], or null when it cannot be decoded.
     *
     * [positionUs] is SOURCE time — the trim edge's own position, which `ToolState.Trimming` already
     * carries, so nothing maps it on the way here.
     *
     * The URI doubles as the cache key's sourceId: the question is always "this file at this time", so
     * two clips of one file share frames rather than decoding twice — the same reason the store's key is
     * `(source, position)` and not a clip id.
     */
    suspend fun frame(uri: String, positionUs: Long): Bitmap? =
        store.thumbnail(sourceId = uri, uri = uri, positionUs = positionUs)

    /**
     * Drops the cached frames. Called on a low-memory signal, exactly as the thumbnails are.
     *
     * `suspend` because the store's clear takes the mutex a decode in flight may be holding — the same
     * reason the app launches it from a scope rather than calling it from `onTrimMemory` directly.
     */
    suspend fun clear() {
        store.clear()
    }

    /** Bytes held right now — what [clear] brings to zero. */
    val cachedBytes: Int get() = store.cachedBytes

    companion object {
        /**
         * 640 px: roughly a third of a 1080p frame per side.
         *
         * Chosen against the STAGE's size rather than the source's resolution — beyond a few hundred
         * pixels the difference is invisible on a phone, and a 1080p frame held at full size costs
         * ~8 MB in a cache sharing the app's heap with the decode pipeline.
         */
        const val PREVIEW_WIDTH_PX = 640
    }
}
