package com.redcut.app

import android.app.Application
import com.redcut.app.logging.plantLoggingTrees
import dagger.hilt.android.HiltAndroidApp

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
    override fun onCreate() {
        super.onCreate()
        plantLoggingTrees()
    }
}
