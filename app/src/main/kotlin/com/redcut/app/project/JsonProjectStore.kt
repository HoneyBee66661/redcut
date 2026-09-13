package com.redcut.app.project

import android.content.Context
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.project.ProjectCodec
import com.redcut.domain.project.ProjectStore
import com.redcut.domain.project.ProjectSummary
import com.redcut.domain.project.SavedProject
import com.redcut.domain.project.summary
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The project store, on the filesystem (FR-6.x, and the device pass's "my clip disappeared").
 *
 * ### Why the filesystem rather than DataStore, which the app already has
 *
 * DataStore is for PREFERENCES — small, keyed, rewritten wholesale. A project is a document that grows
 * with the timeline, and it wants to be a file: one per project, so saving one is one write, so a corrupt
 * project cannot take another one with it, and so a future "export my project" is a copy rather than a
 * serialisation. The preferences store keeps exactly one thing, the id of the project the user was last
 * in — which is precisely a preference.
 *
 * ### The two files, and what happens when they disagree
 *
 * `projects/<id>.json` is the project; `last_project` is its id. If the pointer names a file that is not
 * there (the user cleared app storage, a half-restored backup), [latest] answers null and the editor
 * opens empty rather than failing — the same choice the media reader makes about an unreadable file
 * (FR-1.4): report, do not throw.
 */
@Singleton
class JsonProjectStore @Inject constructor(
    // `@param:` for the field-target warning Kotlin 2.2 raises on a constructor property.
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val logger: RedcutLogger,
) : ProjectStore {

    private val directory: File get() = File(context.filesDir, PROJECTS_DIR)
    private val pointer: File get() = File(context.filesDir, POINTER_FILE)

    override suspend fun save(project: SavedProject) {
        withContext(io) {
            directory.mkdirs()
            File(directory, "${project.id}.json").writeText(ProjectCodec.encode(project))
            pointer.writeText(project.id)
            logger.d(TAG, "saved ${project.name}: ${project.document.clips.size} clip(s)")
        }
    }

    override suspend fun latest(): SavedProject? = withContext(io) {
        val id = pointer.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (id.isEmpty()) return@withContext null
        val file = File(directory, "$id.json")
        if (!file.exists()) {
            logger.d(TAG, "the last project ($id) is gone; opening empty")
            return@withContext null
        }
        ProjectCodec.decode(file.readText())
    }

    override suspend fun summaries(): List<ProjectSummary> = withContext(io) {
        // Every project file, decoded for a summary. A file that will not decode contributes nothing: it
        // cannot collide with a new project, and refusing to name or list anything because one old file is
        // corrupt would be a strange way to fail.
        directory.listFiles { file -> file.extension == JSON_EXTENSION }
            .orEmpty()
            .mapNotNull { file -> ProjectCodec.decode(file.readText())?.summary() }
    }

    override suspend fun savedNames(): List<String> = summaries().map { it.name }

    private companion object {
        const val TAG = "JsonProjectStore"

        /** A storage contract: renaming this directory orphans every saved project. */
        const val PROJECTS_DIR = "projects"

        /** Also a contract. One id, rewritten on every save. */
        const val POINTER_FILE = "last_project"

        const val JSON_EXTENSION = "json"
    }
}

/** Binds the filesystem store to the port the features depend on. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProjectStoreModule {

    @Binds
    abstract fun bindProjectStore(impl: JsonProjectStore): ProjectStore
}
