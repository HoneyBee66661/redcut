package com.redcut.app.di

import android.content.Context
import com.redcut.core.common.di.MainDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.PreviewRenderer
import com.redcut.engine.media3.Media3PreviewRenderers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Binds the preview renderer the editor asks for to the Media3 one it gets.
 *
 * This module is the reason `:feature:editor` can hold the interface at all (§6.8 rule D6) while rule 2
 * keeps it from depending on an engine: the editor declares *what it needs*, `:app` decides *what that
 * is*, and the only module that names Media3 is `:engine:media3`.
 *
 * ### Why the scope is the ViewModel, and not the process
 *
 * A renderer holds a Media3 player, and a player holds a hardware decoder (§9.1). The right lifetime
 * for "the preview of the thing this screen is editing" is the screen: process-scoped would mean a
 * decoder held from the first editor visit until the process died, and activity-scoped would mean one
 * surviving a rotation with a surface that no longer exists. `@ViewModelScoped` gives one renderer per
 * editor screen, and `EditorViewModel.onCleared` is what releases it — explicitly, rather than through
 * a `@PreDestroy` hook, so the release is visible at the call site that owns the lifetime.
 *
 * The dispatcher is [MainDispatcher] because Media3 players are confined to a single thread with a
 * `Looper`, and the main thread is the one the composable that attaches to them is already on.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object PreviewRendererModule {

    @Provides
    @ViewModelScoped
    fun providePreviewRenderer(
        @ApplicationContext context: Context,
        @MainDispatcher main: CoroutineDispatcher,
        logger: RedcutLogger,
    ): PreviewRenderer = Media3PreviewRenderers.create(
        context = context,
        main = main,
        logger = logger,
    )
}
