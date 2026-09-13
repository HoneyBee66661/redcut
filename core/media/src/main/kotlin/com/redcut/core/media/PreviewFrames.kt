package com.redcut.core.media

import android.graphics.Bitmap
import com.redcut.core.common.logging.RedcutLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The frame the preview shows at the playhead (FR-2's "correct preview").
 *
 * ### What this is, and what it deliberately is not
 *
 * Spec §8.4 describes the preview as a renderer over a `RenderGraph` — `CompositionPlayerRenderer`
 * with an `ExoPlayerRenderer` fallback — because the long-term design is that preview and export share
 * one graph, and parity between them is a test (§12.3). That shape needs the `RenderGraph` →
 * `Composition` mapper and the composition path, which is Phase 4.2's work.
 *
 * Phase 1's exit criterion is narrower: "scrub with correct preview". A scrub shows ONE frame per
 * playhead position, so a still decode is not a shortcut around the renderer — it is what the criterion
 * asks for, and it reuses the pipeline that already exists and is tested: the same
 * `MediaMetadataRetriever` decode as the timeline's thumbnails, behind the same
 * [MediaResourceBroker] semaphore (§9.1), in a cache bounded by bytes (§9.3).
 *
 * What it cannot do is PLAY. Continuous playback across cut points needs a composition, and this class
 * is honest about being one frame at a time — which is why the renderer abstraction in §8.4 is still
 * owed, and is not claimed here.
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
     * The URI doubles as the cache key's sourceId: a preview is always "this file at this time", so two
     * clips of one file share frames rather than decoding twice — the same reason the store's key is
     * `(source, position)` and not a clip id.
     */
    suspend fun frame(uri: String, positionUs: Long): Bitmap? =
        store.thumbnail(sourceId = uri, uri = uri, positionUs = positionUs)

    /** Drops the cached frames. Called on a low-memory signal, exactly as the thumbnails are. */
    fun clear() {
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
