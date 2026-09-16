package com.redcut.engine.media3

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.redcut.core.common.di.MainDispatcher
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.MediaResourceBroker
import com.redcut.domain.render.OutputSpec
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import androidx.media3.transformer.ExportResult as MediaExportResult

/**
 * How the export ends. The shape is LibreCuts' `RenderResult` kept and its FFmpeg half dropped: a
 * [Success] names the file it produced, a [Failure] carries a sentence the UI can show, and
 * [Cancelled] is its own outcome because a cancel the user asked for is not a failure (FR-5.9).
 */
sealed interface ExportResult {

    /** The encode finished; [outputFile] is a complete MP4 in app-private storage. */
    data class Success(val outputFile: File) : ExportResult

    /** The encode refused to finish; [message] says why, in words the export sheet can show. */
    data class Failure(val message: String) : ExportResult

    /** A requested cancellation. The output file is partial and the caller deletes it. */
    data object Cancelled : ExportResult
}

/**
 * The export half of §4.3: `RenderGraph` → `Composition` → `Transformer` → MP4.
 *
 * It consumes the SAME compiled graph the preview renderer consumes — [RenderGraphMapper] builds the
 * composition in both paths — which is the whole reason preview/export parity (§12.3) is a property
 * of the architecture instead of a test that might drift: whatever the mapper gets wrong is wrong in
 * both paths at once, and this class adds only the encoder settings ([OutputSpec]'s bitrates, FR-5.6)
 * and the target frame ([RenderGraphMapper.outputVideoEffects], FR-5.1/5.7).
 *
 * ### The broker is not optional here
 *
 * §9.1 is absolute: *"Nothing in the app may construct a `MediaCodec` (or a Media3
 * `Transformer`/`CompositionPlayer`) except through the broker."* The whole encode — construction,
 * start, poll, completion — runs inside one `withEncoder` slot, which is what makes "one export at a
 * time" true by construction rather than by a flag somewhere: a second export cannot even build its
 * `Transformer` until the first gives the slot back.
 *
 * ### Threading (§9.2)
 *
 * A `Transformer` must be built, started and cancelled from one thread with a `Looper`, and that
 * thread is [main] — the same one the preview's players are confined to. [export] hops there for the
 * whole encode and [cancel] must be called from it too (the service's `onStartCommand` is exactly
 * that thread). The progress callback therefore fires on main as well; the service owns anything that
 * leaves this thread.
 *
 * ### What Media3 does not let us pin
 *
 * FR-5.3 asks for H.264 High profile. Media3 1.9's `VideoEncoderSettings` documents that *profile
 * settings are ignored when using `DefaultEncoderFactory` and encoding to H264* — so the profile is
 * delegated to the platform encoder's default rather than requested and silently dropped; the
 * mime type, the bitrates and the AAC-LC profile (which IS honoured) are set here. Sample rate and
 * channel count follow the source; the 48 kHz figure in FR-5.4 is the dominant source rate, and
 * re-sampling every project to it is not this class's call to make.
 */
@OptIn(UnstableApi::class)
class Media3GraphExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val broker: MediaResourceBroker,
    @param:MainDispatcher private val main: CoroutineDispatcher,
    private val logger: RedcutLogger,
) {

    /**
     * The encode in flight, or null. Main-thread confined — the field exists so [cancel] can reach
     * the `Transformer` the poll loop is driving, without a second channel between them.
     */
    private var transformer: Transformer? = null

    /**
     * Set the moment a cancellation is requested, checked by the poll loop. It is the difference
     * between "the export ended badly" and "the export ended because we asked it to": Media3's
     * `cancel()` may surface as an error callback, and this flag is what maps that outcome to
     * [ExportResult.Cancelled] instead of [ExportResult.Failure].
     */
    private val cancelRequested = AtomicBoolean(false)

    /**
     * Encodes [graph] into [outputFile], reporting 0..100 through [onProgress] as Media3 measures it.
     *
     * Suspending rather than callback-driven because the caller is a service coroutine whose own
     * lifetime should bound the encode's: a cancelled caller cancels the `Transformer` (the catch
     * below), and the broker slot is released by the same `finally` either way.
     */
    suspend fun export(
        graph: RenderGraph,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ): ExportResult {
        require(graph.videoLayers.isNotEmpty()) { "an empty graph has nothing to encode" }
        cancelRequested.set(false)
        return broker.withEncoder {
            withContext(main) {
                val encode = buildTransformer(graph.output)
                transformer = encode
                try {
                    runEncode(encode, graph, outputFile, onProgress)
                } finally {
                    transformer = null
                }
            }
        }
    }

    /**
     * Asks the encode in flight to stop. A no-op when nothing is encoding.
     *
     * Main thread only — [Transformer.cancel] verifies the thread it was built on, and this class's
     * contract keeps that thread to [main]. The poll loop turns the request into
     * [ExportResult.Cancelled]; this call only needs to be loud enough that the loop notices.
     */
    fun cancel() {
        cancelRequested.set(true)
        transformer?.cancel()
    }

    /**
     * Starts the encode and waits for it to end, whatever way it ends.
     *
     * The completion is a [CompletableDeferred] rather than a suspending callback queue because there
     * are exactly two endings Media3 reports (completed, errored) and one we report ourselves (the
     * flag above): a one-shot deferred is the smallest thing that can carry all three. The loop never
     * exits normally — it ends by awaiting the deferred, by returning [ExportResult.Cancelled], or by
     * propagating the caller's cancellation after cancelling the `Transformer` in turn.
     *
     * The broad catch below is scoped on purpose and is about one thing: `Transformer.start` refuses
     * an unloadable graph by throwing platform exceptions (`IllegalArgumentException`,
     * `IllegalStateException`) whose exact type is an implementation detail of Media3. Narrowing to
     * the two known ones would let a third turn an export failure into a process crash; the export
     * contract says a refusal is a [ExportResult.Failure], so the family is caught and mapped.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runEncode(
        encode: Transformer,
        graph: RenderGraph,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ): ExportResult {
        val finished = CompletableDeferred<Unit>()
        encode.addListener(encodeListener(finished))
        try {
            val composition = RenderGraphMapper.toComposition(
                graph,
                RenderGraphMapper.outputVideoEffects(graph.output),
            )
            encode.start(composition, outputFile.path)
        } catch (refused: RuntimeException) {
            logger.d(TAG, "export refused at start: ${refused.message}")
            return ExportResult.Failure(refused.message ?: START_REFUSED)
        }

        return try {
            while (finished.isActive) {
                if (cancelRequested.get()) {
                    encode.cancel()
                    return ExportResult.Cancelled
                }
                publishProgress(encode, onProgress)
                delay(PROGRESS_POLL_MS)
            }
            finished.await()
            ExportResult.Success(outputFile)
        } catch (failed: ExportException) {
            if (cancelRequested.get()) {
                ExportResult.Cancelled
            } else {
                logger.d(TAG, "export failed: ${failed.message}")
                ExportResult.Failure(failed.message ?: ENCODE_FAILED)
            }
        } catch (cancelled: CancellationException) {
            // The caller went away (the service stopped). A Transformer nobody stops keeps
            // encoding into a file nobody will read, so the cancellation is forwarded.
            encode.cancel()
            throw cancelled
        }
    }

    /**
     * The one place the encoder is configured: the container's codecs (FR-5.3, FR-5.4, FR-5.5) and
     * [spec]'s bitrate presets (FR-5.6) when it carries them. A spec without bitrates is the preview
     * shape, and the platform defaults are the honest answer to a spec that declined to choose.
     */
    private fun buildTransformer(spec: OutputSpec): Transformer {
        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
        encoderFactoryFor(spec)?.let { builder.setEncoderFactory(it) }
        return builder.build()
    }

    private fun encoderFactoryFor(spec: OutputSpec): DefaultEncoderFactory? {
        val videoSettings = spec.videoBitrate?.let { bitrate ->
            VideoEncoderSettings.Builder().setBitrate(bitrate).build()
        }
        val audioSettings = spec.audioBitrate?.let { bitrate ->
            // AAC-LC (FR-5.4): the profile constant is the one Media3 honours for audio.
            AudioEncoderSettings.Builder()
                .setProfile(CodecProfileLevel.AACObjectLC)
                .setBitrate(bitrate)
                .build()
        }
        if (videoSettings == null && audioSettings == null) return null
        val builder = DefaultEncoderFactory.Builder(context)
        videoSettings?.let { builder.setRequestedVideoEncoderSettings(it) }
        audioSettings?.let { builder.setRequestedAudioEncoderSettings(it) }
        return builder.build()
    }

    private fun publishProgress(encode: Transformer, onProgress: (Int) -> Unit) {
        val holder = ProgressHolder()
        if (encode.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            onProgress(holder.progress.coerceIn(MIN_PERCENT, MAX_PERCENT))
        }
    }

    private fun encodeListener(finished: CompletableDeferred<Unit>): Transformer.Listener =
        object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: MediaExportResult) {
                finished.complete(Unit)
            }

            override fun onError(
                composition: Composition,
                exportResult: MediaExportResult,
                exportException: ExportException,
            ) {
                finished.completeExceptionally(exportException)
            }
        }

    private companion object {
        const val TAG = "Media3GraphExporter"

        /** How often progress is read; Media3 reports whole percentages, so faster is noise. */
        const val PROGRESS_POLL_MS = 200L

        const val MIN_PERCENT = 0
        const val MAX_PERCENT = 100

        const val START_REFUSED = "The export could not start on this device"
        const val ENCODE_FAILED = "The export failed while encoding"
    }
}
