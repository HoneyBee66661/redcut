package com.redcut.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.redcut.core.common.ErrorCode
import com.redcut.core.common.RedcutError
import com.redcut.core.common.RedcutResult
import com.redcut.core.common.di.IoDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.domain.document.SourceProbe
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports what a media file contains (FR-1.3).
 *
 * An interface, and a one-method one, because the interesting half of import is not here:
 * [com.redcut.domain.document.SourceImportPolicy] decides what may enter a document, and
 * it does so in the pure tier. This type's job is narrow and honest — report the facts, or
 * say it could not report them.
 *
 * It is also what makes the editor's import path testable without a device: the view model
 * takes a reader (which takes a probe), and a JVM test supplies one that returns fixtures.
 */
interface MediaProbe {
    /**
     * Reads [uri] and reports its facts.
     *
     * Suspend, and runs on the IO dispatcher: probing touches the content resolver, the
     * file system and (on a cold cache) the media extractor's buffers, none of which may
     * happen on the main thread.
     */
    suspend fun probe(uri: String): RedcutResult<SourceProbe>
}

/**
 * The platform implementation (FR-1.3).
 *
 * Two platform APIs, used for different things on purpose:
 *
 *  * **[MediaExtractor] for the track layout** — it is the only one of the two that says
 *    which codecs the file actually carries (`video/avc`, `audio/mp4a-latm`) and whether a
 *    video track exists at all. That is the field [SourceImportPolicy] refuses sources on,
 *    so getting it from a real track enumeration rather than a container-level MIME string
 *    is the difference between rejecting an unsupported codec and rejecting the container.
 *  * **[MediaMetadataRetriever] as the fallback** for duration, frame size and rotation,
 *    because extractor tracks do not always carry them (`KEY_FRAME_RATE` and `KEY_ROTATION`
 *    are frequently absent on real-world files), while the retriever often can. Reading the
 *    metadata inside a `use { }` block is not optional: a retriever that is not released
 *    pins a file descriptor per import.
 *
 * Neither API is Media3 (spec §6.8 rule D1 confines Media3 to `:engine:media3`).
 */
@Singleton
class AndroidMediaProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val logger: RedcutLogger,
) : MediaProbe {

    override suspend fun probe(uri: String): RedcutResult<SourceProbe> = withContext(io) {
        // One pass per platform API, each guarded: a file the extractor cannot open is a
        // file to report as unreadable, not an exception to carry up through a coroutine.
        val tracks = readTracks(uri)
        val metadata = readMetadata(uri)

        if (tracks == null && metadata == null) {
            return@withContext RedcutResult.Failure(
                RedcutError.of(
                    code = ErrorCode.SOURCE_NOT_FOUND,
                    message = "The probe could not open this file.",
                ),
            )
        }

        val probe = SourceProbe(
            // Duration: the extractor's value is in milliseconds and only present when the
            // file has a track; the retriever reports milliseconds too. Zero means "not
            // reported", and the policy refuses rather than guesses.
            durationUs = (tracks?.durationMs ?: metadata?.durationMs ?: 0L) * 1_000L,
            width = tracks?.width ?: metadata?.width ?: 0,
            height = tracks?.height ?: metadata?.height ?: 0,
            rotationDegrees = tracks?.rotation ?: metadata?.rotation ?: 0,
            frameRate = tracks?.frameRate ?: 0f,
            videoCodec = tracks?.videoMime ?: "",
            audioCodec = tracks?.audioMime,
            hasAudio = tracks?.hasAudio ?: false,
        )

        logger.d(TAG, "probed $uri -> ${probe.width}x${probe.height} ${probe.videoCodec}")
        RedcutResult.Success(probe)
    }

    /** The track enumeration half. Null when the extractor cannot open the file. */
    private fun readTracks(uri: String): TrackFacts? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var videoMime: String? = null
            var audioMime: String? = null
            var width = 0
            var height = 0
            var frameRate = 0f
            var rotation = 0
            var durationMs = 0L

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.stringOrNull(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoMime = mime
                    width = format.intOrZero(MediaFormat.KEY_WIDTH)
                    height = format.intOrZero(MediaFormat.KEY_HEIGHT)
                    frameRate = format.floatOrZero(MediaFormat.KEY_FRAME_RATE)
                    // KEY_ROTATION is API 23+ and optional; absent is 0, which is the
                    // normal case for a file whose pixels are already the right way up.
                    rotation = format.intOrZero(MediaFormat.KEY_ROTATION)
                    // KEY_DURATION is in MICROseconds while MediaMetadataRetriever reports
                    // milliseconds; the merge below works in milliseconds for both.
                    durationMs = format.longOrZero(MediaFormat.KEY_DURATION) / US_PER_MS
                } else if (mime.startsWith("audio/")) {
                    audioMime = mime
                }
            }

            return TrackFacts(
                durationMs = durationMs,
                width = width,
                height = height,
                rotation = rotation,
                frameRate = frameRate,
                videoMime = videoMime,
                audioMime = audioMime,
                hasAudio = audioMime != null,
            )
        } catch (e: IOException) {
            // A moved file, or a container the platform cannot parse.
            logUnreadable("extractor", uri, e)
            null
        } catch (e: IllegalArgumentException) {
            // A malformed data source. Same outcome for the caller: unreadable — and the
            // metadata pass still gets its chance to say something more useful.
            logUnreadable("extractor", uri, e)
            null
        } catch (e: SecurityException) {
            // A SAF grant that has since been revoked (FR-1.5's failure mode).
            logUnreadable("extractor", uri, e)
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * The metadata half. Null when the retriever cannot open the file.
     *
     * The suppression is scoped to this function and is not a convenience:
     * `MediaMetadataRetriever` documents a bare `RuntimeException` for a data source it
     * cannot handle, so there is no narrower type to catch, and catching it is what keeps
     * one unreadable file from taking the whole import (and the app) down with it. It lives
     * here rather than in config/detekt/detekt.yml because it is a fact about one platform
     * API, not a policy about this codebase — the same reasoning the scoped suppression in
     * UndoStack.kt records.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readMetadata(uri: String): MetadataFacts? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            MetadataFacts(
                durationMs = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION),
                width = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
        } catch (e: IOException) {
            logUnreadable("retriever", uri, e)
            null
        } catch (e: IllegalArgumentException) {
            logUnreadable("retriever", uri, e)
            null
        } catch (e: RuntimeException) {
            // MediaMetadataRetriever documents a bare RuntimeException for a data source it
            // cannot handle, and there is no narrower type to catch. Named explicitly rather
            // than left to a broad `Exception` catch so the reach is visible in review.
            logUnreadable("retriever", uri, e)
            null
        } finally {
            retriever.release()
        }
    }

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.longOrZero(key: String): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L

    private fun MediaFormat.floatOrZero(key: String): Float =
        if (containsKey(key)) runCatching { getFloat(key) }.getOrDefault(0f) else 0f

    private fun MediaMetadataRetriever.extractLong(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0L

    private fun MediaMetadataRetriever.extractInt(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0

    private data class TrackFacts(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val frameRate: Float,
        val videoMime: String?,
        val audioMime: String?,
        val hasAudio: Boolean,
    )

    private data class MetadataFacts(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
    )

    /** One place where "could not read this file" is logged, so both passes say it alike. */
    private fun logUnreadable(reader: String, uri: String, error: Exception) {
        logger.d(TAG, "$reader could not read $uri: ${error.message}")
    }

    private companion object {
        const val TAG = "AndroidMediaProbe"
    }
}

/**
 * Microseconds in a millisecond.
 *
 * The two platform APIs disagree about duration units — `MediaFormat.KEY_DURATION` is in
 * microseconds, `MediaMetadataRetriever` reports milliseconds — so the merge happens in
 * milliseconds (the retriever's unit) and one conversion is named here rather than written
 * as a bare `1_000L` at the call site.
 */
private const val US_PER_MS = 1_000L

/** Binds the platform probe to the interface. The only place the two meet. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaProbeModule {

    @Binds
    abstract fun bindMediaProbe(impl: AndroidMediaProbe): MediaProbe
}
