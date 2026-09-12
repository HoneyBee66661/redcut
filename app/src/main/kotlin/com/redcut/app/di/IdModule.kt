package com.redcut.app.di

import com.redcut.core.common.IdSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

/**
 * The app's [IdSource]: random UUIDs.
 *
 * Random rather than a counter, and that is a correctness decision, not a style one. A
 * counter restarts at zero in every process, so the second session to import a clip into a
 * reopened project would mint ids that collide with the first session's — and the collision
 * would surface as a clip that silently overwrites another one, in a file the user has
 * already saved twice. A UUID cannot collide across sessions, devices, or a project copied
 * between them.
 *
 * The trade-off is that ids are not readable in a saved project or in a test. That cost is
 * paid here and nowhere else: tests inject their own [IdSource] (see `EditorViewModelTest`),
 * so the deterministic ids exist exactly where they are useful.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object IdModule {

    @Provides
    @Singleton
    fun provideIdSource(): IdSource = IdSource { UUID.randomUUID().toString() }
}
