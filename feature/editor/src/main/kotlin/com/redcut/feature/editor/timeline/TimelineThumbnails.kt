package com.redcut.feature.editor.timeline

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.redcut.core.media.ThumbnailKey
import com.redcut.core.media.ThumbnailStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thumbnails for the timeline, as `ImageBitmap`s.
 *
 * Spec §7.1 draws clip thumbnails as TEXTURES rather than composables, which in Compose means
 * `drawImage` on an `ImageBitmap` inside the timeline's own `Canvas` — not an `Image()` per slice.
 * A `LazyRow` of images is the thing the spec says will jank at 50 clips.
 *
 * ### What this caches, and what it deliberately does not
 *
 * The PIXELS are cached by [ThumbnailStore] behind a 16 MB byte budget (§9.3). This class holds only
 * the `ImageBitmap` wrappers, so a scroll does not allocate a new wrapper per frame per thumbnail.
 * Wrapping is cheap but not free, and the budget for wrappers is a COUNT (they are a few dozen bytes
 * of handle each), not a byte ceiling — pretending otherwise would double the accounting the store
 * already does correctly.
 *
 * A second cache with its own eviction also means the two can disagree; that is acceptable here
 * because a wrapper for a bitmap the store has already evicted is still a VALID image (the wrapper
 * holds its own reference until it is dropped). The failure mode of a stale entry is memory the GC
 * collects, never a wrong picture.
 */
@Singleton
class TimelineThumbnails @Inject constructor(
    private val store: ThumbnailStore,
) {
    // Access-ordered so the eviction below takes the least recently READ wrapper, matching the
    // store's own policy: the slices the user is looking at survive a scroll off-screen and back.
    private val wrappers = LinkedHashMap<ThumbnailKey, ImageBitmap>(0, LOAD_FACTOR, true)
    private val mutex = Mutex()

    /** The thumbnail for one slice, loaded on first request. */
    suspend fun image(sourceId: String, uri: String, positionUs: Long): ImageBitmap? {
        val key = ThumbnailKey(sourceId, positionUs)
        mutex.withLock { wrappers[key] }?.let { return it }

        // Deliberately outside the lock: the decode is the slow part and it is bounded by the
        // broker's decoder slots (§9.1), not by this mutex.
        val decoded = store.thumbnail(sourceId, uri, positionUs) ?: return null
        val image = decoded.asImageBitmap()

        mutex.withLock {
            wrappers[key] = image
            while (wrappers.size > MAX_WRAPPERS) {
                val oldest = wrappers.keys.first()
                wrappers.remove(oldest)
            }
        }
        return image
    }

    private companion object {
        /** The JDK default, named (see ByteLruCache for the same constant's reasoning). */
        const val LOAD_FACTOR = 0.75f

        /** Wrappers held at once. ~40 bytes each, so this is a rounding error next to the pixels. */
        const val MAX_WRAPPERS = 256
    }
}
