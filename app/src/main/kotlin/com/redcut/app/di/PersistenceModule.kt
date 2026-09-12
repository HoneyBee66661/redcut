package com.redcut.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The app's preferences store (spec §3.1 "Preferences: DataStore", §10.3).
 *
 * The delegate must be a file-level extension property — DataStore enforces ONE
 * instance per file, and creating a second one for the same name throws at runtime.
 * Declaring it here, privately, is what makes "there is exactly one" a property of the
 * module rather than a convention.
 *
 * ### What is deliberately NOT here
 *
 * No keys, and no `SettingsRepository`. Nothing in the MVP needs a preference yet — the
 * first arrivals are Phase 4.x (default export preset, last-used storage location) and
 * Phase 1.x (whether source permissions still resolve). Inventing a schema now would
 * mean inventing product decisions in an infrastructure commit, and the port that
 * features will depend on belongs in a shared module (a feature cannot depend on
 * `:app`, so the interface goes in `:core:*` and the DataStore implementation stays
 * here). This module establishes the mechanism: one instance, injected, on the right
 * dispatcher, with the file named.
 *
 * Note the file name is a storage contract: `redcut_settings.preferences_pb` in the
 * app's private `datastore/` directory. Renaming it later silently discards user
 * preferences, so it is named once, here.
 */
private val Context.redcutPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "redcut_settings",
)

@Module
@InstallIn(SingletonComponent::class)
internal object PersistenceModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.redcutPreferences
}
