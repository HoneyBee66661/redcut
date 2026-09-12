package com.redcut.core.media

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.redcut.core.common.RedcutResult
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.ProbedSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one URI produced: a probed source, or a reason it could not be read.
 *
 * The failure case carries the DOMAIN's [ImportRejection.Unreadable] rather than a raw
 * exception, because the two failure kinds the user sees — "this codec is unsupported"
 * (the file was read, the policy refused it) and "this file could not be opened" (it could
 * not be read at all) — have to arrive in one report. Producing the second kind here means
 * the caller concatenates two lists of the same type instead of inventing a mapping.
 */
sealed interface SourceReadResult {
    data class Read(val source: ProbedSource) : SourceReadResult
    data class Unreadable(val rejection: ImportRejection.Unreadable) : SourceReadResult
}

/**
 * Reads content URIs into probed sources (FR-1.1, FR-1.3, FR-1.5).
 *
 * The interface exists so the editor's import path is testable on the JVM: the view model
 * takes this, and a test supplies one that returns fixtures without a content resolver, a
 * device, or a real file.
 */
interface MediaSourceReader {
    /**
     * Reads every URI, in the order given (FR-1.2: the selection order IS the timeline
     * order). One bad file does not abort the batch: the result list is positional with
     * the input, so the caller can report exactly which of five selections failed.
     */
    suspend fun read(uris: List<String>): List<SourceReadResult>
}

/**
 * The SAF implementation.
 *
 * Three responsibilities, each with a reason:
 *
 * 1. **Take a persistable permission (FR-1.5).** A URI handed over by `ACTION_OPEN_DOCUMENT`
 *    is readable for the lifetime of the grant, which by default ends with the process. A
 *    project that survives a reboot — the whole point of a project file — needs
 *    `takePersistableUriPermission`, or every project silently loses its media. Failure to
 *    take it is logged rather than fatal: some providers refuse, and the import is still
 *    correct for this session.
 *
 * 2. **Resolve a display name.** `ProbedSource.displayName` is what every user-facing
 *    message is built from ("\"clip.mp4\" uses MPEG-4 Part 2…"), so a nameless source makes
 *    every rejection anonymous. `OpenableColumns.DISPLAY_NAME` is the SAF-blessed way to
 *    ask; the URI's last path segment is the fallback, and only for a provider that
 *    answers nothing.
 *
 * 3. **Probe, and translate a probe failure into a rejection.** The probe returns
 *    `RedcutResult`; the failure's message is the platform's own words, which is what makes
 *    "could not be opened: Permission denied" more useful than "import failed".
 */
@Singleton
class SafMediaSourceReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: MediaProbe,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val logger: RedcutLogger,
) : MediaSourceReader {

    override suspend fun read(uris: List<String>): List<SourceReadResult> = withContext(io) {
        uris.map { readOne(it) }
    }

    private suspend fun readOne(uri: String): SourceReadResult {
        val displayName = displayName(uri)
        persistPermission(uri)

        return when (val probed = probe.probe(uri)) {
            is RedcutResult.Success ->
                SourceReadResult.Read(
                    ProbedSource(uri = uri, displayName = displayName, probe = probed.value),
                )

            is RedcutResult.Failure ->
                SourceReadResult.Unreadable(
                    ImportRejection.Unreadable(
                        displayName = displayName,
                        reason = probed.error.message,
                    ),
                )
        }
    }

    /**
     * Asks for the grant to outlive this process (FR-1.5).
     *
     * `FLAG_GRANT_READ_URI_PERMISSION` only: an editor reads media, it never writes back to
     * the user's gallery, and requesting write access on import is the kind of permission
     * creep the manifest comment in `:app` already refuses at the declaration level.
     */
    private fun persistPermission(uri: String) {
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        taken.onFailure { error ->
            logger.w(TAG, "could not persist read permission for $uri: ${error.message}")
        }
    }

    /** `OpenableColumns.DISPLAY_NAME`, or the URI's last segment when the provider is mute. */
    private fun displayName(uri: String): String {
        val cursor = runCatching {
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
        }.getOrNull()

        val name = cursor?.use { it.readDisplayName() }
        return name?.takeIf { it.isNotBlank() }
            ?: Uri.parse(uri).lastPathSegment
            ?: uri
    }

    private fun Cursor.readDisplayName(): String? {
        if (!moveToFirst()) return null
        val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private companion object {
        const val TAG = "SafMediaSourceReader"
    }
}

/** Binds the SAF reader to the interface. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaSourceReaderModule {

    @Binds
    abstract fun bindMediaSourceReader(impl: SafMediaSourceReader): MediaSourceReader
}
