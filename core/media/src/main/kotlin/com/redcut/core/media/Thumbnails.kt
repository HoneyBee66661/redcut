package com.redcut.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.redcut.core.common.ByteLruCache
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a thumbnail is keyed by (spec §9.3): which source, and where in it.
 *
 * Position is part of the key rather than an index into a strip, because the timeline's
 * thumbnails are requested at whatever times the zoom level lands on — the same source at 1 s
 * and at 1.4 s are two different pictures, and a cache that conflated them would show the wrong
 * frame after a zoom.
 */
data class ThumbnailKey(val sourceId: String, val positionUs: Long)

/**
 * Decodes one frame of a source at ≤ [ThumbnailStore.WIDTH_PX] wide (spec §9.3).
 *
 * An interface so that everything above it — the timeline, screenshot tests, a fake in a JVM
 * test — never touches `MediaMetadataRetriever` or a `Bitmap` it did not ask for.
 */
interface ThumbnailSource {
    /**
     * The frame at [positionUs] in [uri], scaled down, or null when it cannot be decoded.
     *
     * Null rather than an exception: a thumbnail that cannot be produced is a grey rectangle on
     * the timeline, not a reason to fail an edit session. This is the same call the import path
     * makes about unreadable files (FR-1.4) — report, do not throw.
     */
    suspend fun thumbnail(sourceId: String, uri: String, positionUs: Long): Bitmap?
}

/**
 * Cache-first thumbnails, bounded in bytes (spec §9.3).
 *
 * The cache holds ≤ 16 MB, evicts least-recently-used, and is dropped wholesale on
 * `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` (see [clear], called from the app's `onTrimMemory`).
 *
 * ### Why a mutex guards only the cache and not the decode
 *
 * `ByteLruCache` is not thread-safe by construction (its KDoc says so: callers are expected to
 * serialise). A timeline scrolling at speed can ask for several thumbnails at once, so the
 * cache access is serialised here — but only the access. The DECODE stays outside the lock and
 * therefore parallel, bounded by [MediaResourceBroker]'s decoder slots, which is the thing that
 * actually needs bounding. Holding a mutex across a decode would serialise every thumbnail
 * behind the slowest one and make the scroll stutter for no benefit.
 */
@Singleton
class ThumbnailStore @Inject constructor(
    private val source: ThumbnailSource,
    private val logger: RedcutLogger,
) {
    private val cache = ByteLruCache<ThumbnailKey, Bitmap>(MAX_BYTES) { it.byteCount }
    private val cacheMutex = Mutex()

    /** Bytes held right now — the number `onTrimMemory` is supposed to bring to zero. */
    val cachedBytes: Int get() = cache.bytes

    /** Entries held right now. */
    val cachedCount: Int get() = cache.size

    /** The thumbnail for [positionUs] in [uri], from the cache when it is there. */
    suspend fun thumbnail(sourceId: String, uri: String, positionUs: Long): Bitmap? {
        val key = ThumbnailKey(sourceId, positionUs)
        cacheMutex.withLock { cache[key] }?.let { return it }

        val decoded = source.thumbnail(sourceId, uri, positionUs) ?: return null

        cacheMutex.withLock { cache.put(key, decoded) }
        logger.d(TAG, "thumbnail $positionUs decoded (${cache.size} cached, ${cache.bytes} bytes)")
        return decoded
    }

    /**
     * Drops every cached thumbnail (spec §9.3).
     *
     * Called from `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` and above, and from nowhere else: the
     * cache is regenerable by definition (§10.4's "deleting this directory must never lose user
     * work" is the same rule stated about the disk cache), so throwing it away is always safe.
     * The mutex is taken even though the call is a single field clear, because a decode in
     * flight is about to put something back.
     */
    suspend fun clear() {
        cacheMutex.withLock { cache.clear() }
    }

    companion object {
        /** Spec §9.3's budget, in bytes. */
        const val MAX_BYTES = 16 * 1024 * 1024

        /** Spec §9.3: thumbnails are decoded at ≤ 160 px wide. */
        const val WIDTH_PX = 160

        private const val TAG = "ThumbnailStore"
    }
}

/**
 * The platform decoder.
 *
 * Two details are load-bearing:
 *
 * 1. **Aspect ratio is computed, not requested.** `getScaledFrameAtTime` scales to the exact
 *    width and height it is given, so passing the same value for both distorts every frame. The
 *    target height is derived from the retriever's own metadata instead, with a fallback to
 *    decode-then-scale for the cases where the size is not reported.
 * 2. **Every decode runs through [MediaResourceBroker.withDecoder]** (spec §9.1's "thumbnail
 *    extraction queues behind playback"). This is the only reason a scroll that decodes twenty
 *    thumbnails cannot exhaust the device's codecs.
 */
@Singleton
class AndroidThumbnailSource @Inject constructor(
    // `@param:` states the target Kotlin 2.2 is warning about; see AndroidMediaProbe.
    @param:ApplicationContext private val context: Context,
    private val broker: MediaResourceBroker,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val logger: RedcutLogger,
) : ThumbnailSource {

    override suspend fun thumbnail(sourceId: String, uri: String, positionUs: Long): Bitmap? =
        withContext(io) {
            broker.withDecoder {
                decode(uri, positionUs)
            }
        }

    /**
     * The suppression is scoped to this function for the same reason as in [AndroidMediaProbe]:
     * `MediaMetadataRetriever` reports its failures as a bare `RuntimeException` and there is no
     * narrower type to catch. Losing a thumbnail to it is a grey rectangle; letting it escape is
     * a crash mid-scroll.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun decode(uri: String, positionUs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            val target = targetHeight(retriever)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && target != null) {
                // API 27+: ask for a frame already scaled, so a 4K frame never becomes a
                // full-size Bitmap in memory on its way to a 160 px thumbnail.
                retriever.getScaledFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ThumbnailStore.WIDTH_PX,
                    target,
                )
            } else {
                val full = retriever.getFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                full?.scaleDown()
            }
        } catch (e: RuntimeException) {
            logger.d(TAG, "could not decode a thumbnail at $positionUs: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Height for a [ThumbnailStore.WIDTH_PX]-wide frame, or null when the source's size is not
     * reported.
     *
     * Split into named steps rather than one expression because the arithmetic has three
     * separate ideas in it — what the container stores, what the user sees after rotation, and
     * what fits in the thumbnail width — and a single condition combining them is a condition
     * nobody can review (detekt says the same thing about it, at a threshold of four).
     */
    private fun targetHeight(retriever: MediaMetadataRetriever): Int? {
        val stored = retriever.storedSize() ?: return null
        val (visibleWidth, visibleHeight) = stored.forRotation(retriever.rotationDegrees())
        val scaled = ThumbnailStore.WIDTH_PX.toFloat() * visibleHeight / visibleWidth
        return scaled.toInt().coerceAtLeast(1)
    }

    /** The stored frame size, or null when either dimension is missing or nonsense. */
    private fun MediaMetadataRetriever.storedSize(): Pair<Int, Int>? {
        val width = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return null
        val height = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return null
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    /**
     * A metadata value as an Int, or null — the retriever answers with a String, and "0" and
     * "absent" are different answers that a `?: 0` would conflate.
     */
    private fun MediaMetadataRetriever.metadataInt(key: Int): Int? =
        extractMetadata(key)?.toIntOrNull()

    private fun MediaMetadataRetriever.rotationDegrees(): Int =
        metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0

    /**
     * A quarter-turned source is STORED sideways: the frame the user sees is the swapped pair,
     * and scaling by the stored pair would letterbox every portrait video.
     */
    private fun Pair<Int, Int>.forRotation(rotationDegrees: Int): Pair<Int, Int> {
        val sideways = rotationDegrees == QUARTER_TURN_DEGREES ||
            rotationDegrees == THREE_QUARTER_TURN_DEGREES
        return if (sideways) second to first else this
    }

    /** Aspect-preserving downscale for the fallback path, releasing the full-size frame. */
    private fun Bitmap.scaleDown(): Bitmap {
        if (width <= ThumbnailStore.WIDTH_PX) return this
        val ratio = ThumbnailStore.WIDTH_PX.toFloat() * height / width
        val scaledHeight = ratio.toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(this, ThumbnailStore.WIDTH_PX, scaledHeight, true)
        if (scaled !== this) recycle()
        return scaled
    }

    private companion object {
        const val TAG = "AndroidThumbnailSource"
    }
}

/** Binds the platform decoder to the port. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ThumbnailSourceModule {

    @Binds
    abstract fun bindThumbnailSource(impl: AndroidThumbnailSource): ThumbnailSource
}

/**
 * The two rotations that turn a frame sideways.
 *
 * Named rather than written as 90 and 270 at the use site, and deliberately NOT derived from a
 * "is it a multiple of 90" test: only these two values mean the stored width and height should
 * be swapped, and a condition that also accepted 180 would silently rotate every upside-down
 * frame by a quarter turn.
 */
private const val QUARTER_TURN_DEGREES = 90
private const val THREE_QUARTER_TURN_DEGREES = 270
