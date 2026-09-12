package com.redcut.app.di

import com.redcut.app.BuildConfig
import com.redcut.app.logging.TimberRedcutLogger
import com.redcut.core.common.logging.NoOpRedcutLogger
import com.redcut.core.common.logging.RedcutLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Which [RedcutLogger] the graph hands out.
 *
 * Debug builds get the Timber bridge; release builds get
 * [NoOpRedcutLogger]. That is spec §3.1's "release-mode no-op tree" applied at the
 * seam instead of at the caller: a feature does not branch on build type, and there is
 * no path by which a release build logs because someone forgot to check
 * `BuildConfig.DEBUG` at a call site.
 *
 * The choice is made from `BuildConfig.DEBUG`, the same flag the tree planting in
 * `plantLoggingTrees()` uses — two places, one source of truth. `d()` and `i()` are
 * dropped in release by the no-op anyway, so the two cannot disagree in a way a user
 * would notice.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LoggingModule {

    @Provides
    @Singleton
    fun provideRedcutLogger(): RedcutLogger =
        if (BuildConfig.DEBUG) TimberRedcutLogger else NoOpRedcutLogger
}
