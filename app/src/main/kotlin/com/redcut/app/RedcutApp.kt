package com.redcut.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.redcut.app.logging.plantLoggingTrees
import com.redcut.core.media.PreviewFrames
import com.redcut.core.media.ThumbnailStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Process-wide entry point, and the root of the Hilt graph.
 *
 * `@HiltAndroidApp` is what generates the application-level component every other
 * injected type hangs off — the composition root spec §4.1 describes. Note what is
 * NOT here: no manual `object Graph`/service locator, and no `Timber` call inside a
 * feature. The graph is declared by annotations in `:app/di`, and `:feature:*` only
 * ever receives what it declares in a constructor.
 *
 * Logging is planted here rather than in `MainActivity` because an Application is the
 * earliest process-wide hook: a crash during Activity creation should already be
 * logged, not logged from the second thing that runs.
 */
@HiltAndroidApp
class RedcutApp : Application() {

    /**
     * The thumbnail cache, held here because `onTrimMemory` is where spec §9.3 says to drop it
     * and an Application is the only thing in the app that receives that callback.
     */
    @Inject
    lateinit var thumbnails: ThumbnailStore

    /**
     * The preview's frame cache, dropped on the same signal for the same reason.
     *
     * Held separately because it is a different cache: 640 px frames are ~1 MB each where a thumbnail
     * is a few kilobytes, so this is the one that actually matters when memory is short — the
     * thumbnails could be re-decoded from scratch on every scroll and nobody would notice.
     */
    @Inject
    lateinit var previewFrames: PreviewFrames

    /**
     * A scope that outlives every screen.
     *
     * `onTrimMemory` is a callback, not a coroutine, and the cache's `clear()` is suspend
     * (it takes the same mutex a decode in flight is about to write through). A scope tied to
     * an Activity would be wrong — there is no Activity — and `GlobalScope` would be a leak
     * nobody can cancel. One application-scoped job, cancelled by the process ending, is the
     * accurate lifetime for "drop the caches".
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        plantLoggingTrees()
    }

    /**
     * Drops the thumbnail cache when the system asks for memory back (spec §9.3).
     *
     * The threshold is `>=` `TRIM_MEMORY_RUNNING_LOW` on purpose, and the reason is that the
     * levels above it are not all "more urgent" in the same direction: `TRIM_MEMORY_BACKGROUND`
     * and `TRIM_MEMORY_UI_HIDDEN` mean the app is no longer visible, which is an even better
     * moment to give 16 MB of bitmaps back than a running-low warning is. The cache is
     * regenerable by construction (§10.4: the cache directory may be deleted at any time), so
     * clearing it early costs a re-decode and nothing else.
     *
     * The suppression is scoped here and is about one thing: `TRIM_MEMORY_*` is deprecated as of
     * API 35, in favour of the platform's newer memory-pressure signals. It is NOT dead code for
     * this app — `minSdk` is 26, and every device between 26 and 34 sends exactly this level and
     * nothing else. Dropping it would mean never trimming on the devices with the least memory,
     * which is the opposite of what §9.3 asks for. Revisit when the API 35 replacement can be
     * used unconditionally; until then the honest form is "use the deprecated signal where it is
     * the only signal", stated in one place.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            applicationScope.launch {
                thumbnails.clear()
                previewFrames.clear()
            }
        }
    }
}
