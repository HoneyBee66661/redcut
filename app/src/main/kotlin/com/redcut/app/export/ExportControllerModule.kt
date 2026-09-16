package com.redcut.app.export

import com.redcut.core.media.ExportController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the export port the export sheet holds to the service-backed controller it gets.
 *
 * The same shape as the project store's binding, for the same reason: the feature declares *what it
 * needs* ([ExportController], from `:core:media` so no feature ever sees an engine), `:app` decides
 * *what that is*, and the implementation's name appears in exactly one file — this one — so changing
 * the export's mechanics (a WorkManager resume in Phase 4.4, say) is a change here and nowhere else.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportControllerModule {

    @Binds
    abstract fun bindExportController(impl: ServiceExportController): ExportController
}
