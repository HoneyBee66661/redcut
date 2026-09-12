package com.redcut.app.di

import com.redcut.core.common.di.DefaultDispatcher
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.di.MainDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher bindings (spec §9.2's table).
 *
 * The qualifiers themselves live in `:core:common` — where they can be used without an
 * Android classpath, which is the whole reason they are `javax.inject.Qualifier` rather
 * than Hilt's. This module supplies the actual dispatchers, because `Dispatchers.IO`
 * belongs to `kotlinx-coroutines-android` and that is an Android artifact.
 *
 * | Qualifier | Dispatcher | First real consumer |
 * |---|---|---|
 * | [MainDispatcher] | `Dispatchers.Main.immediate` | editor intents (Phase 1.5) |
 * | [DefaultDispatcher] | `Dispatchers.Default` | `TimelineCompiler` calls (Phase 1.5) |
 * | [IoDispatcher] | `Dispatchers.IO` | probing, thumbnails, project files (1.3, 1.4, 4.7) |
 *
 * `Main.immediate` rather than plain `Main`: an intent handled on the main thread
 * should not be re-posted to the back of its own queue — that is the difference
 * between a tap feeling instant and feeling late (§9.2's note).
 *
 * These providers currently have no consumer, and that is worth saying out loud rather
 * than hiding: Phase 0.5 is the wiring task, and the alternative — letting each feature
 * reach for `Dispatchers.IO` itself — is exactly the habit this module exists to
 * prevent. The consumers are named above so an unused provider is a known debt with a
 * date, not an orphan.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DispatchersModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}
