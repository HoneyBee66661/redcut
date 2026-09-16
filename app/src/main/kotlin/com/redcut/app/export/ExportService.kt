package com.redcut.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.redcut.app.MainActivity
import com.redcut.app.R
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.ExportState
import com.redcut.domain.render.OutputSpec
import com.redcut.engine.media3.ExportResult
import com.redcut.engine.media3.Media3GraphExporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * The export foreground service (FR-5.10, spec Phase 4.1): the one component whose job is to still
 * be alive when the user locks the screen, and to say so — a `mediaProcessing` notification with the
 * percentage Media3 reports, per §8.3's pipeline shape.
 *
 * ### Why a service owns the encode
 *
 * An export is the longest thing the app does and the only one that must not stop when the UI does.
 * The service is started while the app is foregrounded (§8.3's note on API 31+), calls
 * `startForeground` synchronously in `onStartCommand`, and keeps the encode in a coroutine bound to
 * this instance; the system's promise is that a foreground service is not killed for backgrounding.
 * The service is `START_NOT_STICKY` and the request is in-memory: process death ends an export and
 * the sheet reports it, because a half-known resume contract is worse than an honest failure (the
 * persistence half of §8.3 is Phase 4.4).
 *
 * ### The file path, and why MediaStore is written once
 *
 * Media3 writes the MP4 to app-private cache; only a COMPLETE file is inserted into MediaStore and
 * then copied in (`Movies/RedCut`, FR-5.8). That order is §8.3's own rule — *"a cancelled or crashed
 * export leaves no partial file in MediaStore"* — and it is why a failed export costs a cache write
 * and nothing in the user's gallery. The cache file is deleted either way, and the gallery row is
 * removed again if the copy itself fails: an empty ghost in `Movies/` is a partial file by another
 * name.
 */
@AndroidEntryPoint
internal class ExportService : Service() {

    @Inject lateinit var controller: ServiceExportController

    @Inject lateinit var exporter: Media3GraphExporter

    @Inject lateinit var logger: RedcutLogger

    /**
     * Not main-thread and not injected (the dispatcher qualifiers deliberately exclude field
     * injection): the encode hops to main inside [Media3GraphExporter] — where the Transformer
     * lives — and everything else here is either thread-safe (`notify`, `stopSelf`) or runs before
     * the first suspension. Cancelled in [onDestroy] so a destroyed service cannot keep encoding.
     */
    private val scope = CoroutineScope(SupervisorJob())

    /** The last percentage actually shown; Media3's poll is denser than the notification needs. */
    private var shownPercent: Int = PERCENT_START

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXPORT -> handleExport()
            ACTION_CANCEL -> exporter.cancel()
            // A null intent is a restart of a dead service: with no staged graph there is nothing
            // to do, and pretending otherwise would start a foreground service that idles forever.
            else -> {
                logger.d(TAG, "no export action; stopping")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The export run: take the staged graph, go foreground, encode, publish, stop. Every path out
     * deletes the cache file and stops the service — a foreground service that lingers after its
     * work is a battery bug with a notification attached.
     *
     * The broad catch below is scoped on purpose, and about one thing: the export coroutine lives
     * in a Service, where an uncaught exception is a process crash during a user-visible operation.
     * Every predictable failure is already mapped inside the exporter and the publish; what reaches
     * here is a bug, and a bug in an export is still an export that failed — reported, not fatal.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleExport() {
        val graph = controller.takePendingGraph()
        if (graph == null) {
            stopSelf()
            return
        }
        shownPercent = PERCENT_START
        goForeground(progressNotification(PERCENT_START))
        scope.launch {
            val tempOutput = File(cacheDir, TEMP_OUTPUT_NAME)
            try {
                try {
                    val result = exporter.export(graph, tempOutput, ::onProgress)
                    settle(result, graph.output, tempOutput)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: RuntimeException) {
                    logger.d(TAG, "export crashed: ${failed.message}")
                    controller.publish(ExportState.Failed(failed.message ?: ENCODE_FAILED))
                    notifyResult(
                        title = getString(R.string.export_failed_title),
                        text = failed.message ?: ENCODE_FAILED,
                    )
                }
            } finally {
                tempOutput.delete()
                ServiceCompat.stopForeground(
                    this@ExportService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
        }
    }

    /**
     * Publishes one outcome to both audiences: the port state the sheet observes, and the
     * notification the backgrounded user reads.
     */
    private fun settle(result: ExportResult, spec: OutputSpec, tempOutput: File) {
        when (result) {
            is ExportResult.Success -> {
                val savedUri = publishToGallery(tempOutput, spec)
                if (savedUri == null) {
                    controller.publish(ExportState.Failed(COULD_NOT_SAVE))
                    notifyResult(
                        title = getString(R.string.export_failed_title),
                        text = COULD_NOT_SAVE,
                    )
                    return
                }
                controller.publish(ExportState.Succeeded(savedUri))
                notifyResult(
                    title = getString(R.string.export_complete_title),
                    text = getString(R.string.export_complete_text),
                )
            }
            is ExportResult.Failure -> {
                controller.publish(ExportState.Failed(result.message))
                notifyResult(title = getString(R.string.export_failed_title), text = result.message)
            }
            // The user asked for this from the notification itself; announcing a cancellation they
            // performed would be a notification about their own tap.
            ExportResult.Cancelled -> controller.publish(ExportState.Cancelled)
        }
    }

    /** The encode's progress: percent into the port state and, when it moves, the notification. */
    private fun onProgress(percent: Int) {
        controller.publish(ExportState.Running(percent))
        if (percent != shownPercent) {
            shownPercent = percent
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, progressNotification(percent))
        }
    }

    /**
     * The `mediaProcessing` startForeground (§8.3): the type exists from API 34 and is what makes
     * the system treat a long encode as work in progress rather than a battery suspect. Below 34
     * there is no type to ask for, and asking with the constant would be asking a platform that
     * does not know the word.
     */
    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun progressNotification(percent: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(getString(R.string.export_progress_title))
            .setContentText(getString(R.string.export_progress_percent, percent))
            .setProgress(PERCENT_MAX, percent, percent == PERCENT_START)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.export_cancel), cancelIntent)
            .build()
    }

    private fun notifyResult(title: String, text: String) {
        notificationManager.notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build(),
        )
    }

    /** Tapping a notification returns to the app, which is where the export sheet lives. */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_CODE_CONTENT,
        Intent(this, MainActivity::class.java).setFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        val progress = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            getString(R.string.export_progress_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val results = NotificationChannel(
            RESULT_CHANNEL_ID,
            getString(R.string.export_result_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannels(listOf(progress, results))
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Inserts the gallery row (FR-5.8) and copies the finished MP4 into it, returning the MediaStore
     * Uri the share sheet will hand out — or null when any step refuses, with the row removed again
     * so the gallery never shows the attempt.
     */
    private fun publishToGallery(outputFile: File, spec: OutputSpec): Uri? = try {
        val savedUri = contentResolver.insert(collection(), galleryValues(spec))
        if (savedUri != null && copyInto(savedUri, outputFile)) {
            logger.d(TAG, "published ${outputFile.length()} bytes to Movies/RedCut")
            savedUri
        } else {
            if (savedUri != null) contentResolver.delete(savedUri, null, null)
            logger.d(TAG, "the gallery write failed; the empty row is removed again")
            null
        }
    } catch (denied: SecurityException) {
        // Pre-Q devices need the legacy write grant for this insert; without it the export still
        // encoded, and the sheet shows the failure rather than the app crashing on it.
        logger.d(TAG, "MediaStore refused the insert: ${denied.message}")
        null
    } catch (failed: IOException) {
        logger.d(TAG, "the gallery copy failed: ${failed.message}")
        null
    }

    private fun galleryValues(spec: OutputSpec): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName(spec))
        put(MediaStore.MediaColumns.MIME_TYPE, MIME_VIDEO_MP4)
        // Q+ writes are scoped: the row's folder is part of the insert, not a permission. Below Q
        // the legacy volume's root is the only destination an insert can name.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$GALLERY_FOLDER",
            )
        }
    }

    private fun collection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun copyInto(savedUri: Uri, outputFile: File): Boolean =
        contentResolver.openOutputStream(savedUri)?.use { sink ->
            outputFile.inputStream().use { source -> source.copyTo(sink) }
            true
        } ?: false

    /**
     * The gallery filename, stamped with the wall clock. The repo's determinism lesson is about
     * values a test compares, and this is not one: the property a gallery name needs is that a
     * second export of the same project does not quietly collide with the first, and a timestamp
     * is the honest statement of "these are two different exports". LibreCuts reaches the same
     * name shape for the same reason.
     */
    private fun displayName(spec: OutputSpec): String =
        "redcut-${System.currentTimeMillis()}-${spec.width}x${spec.height}.mp4"

    private companion object {
        const val TAG = "ExportService"

        const val ACTION_EXPORT = "com.redcut.app.export.EXPORT"
        const val ACTION_CANCEL = "com.redcut.app.export.CANCEL"

        const val PROGRESS_CHANNEL_ID = "export_progress"
        const val RESULT_CHANNEL_ID = "export_result"
        const val PROGRESS_NOTIFICATION_ID = 1
        const val RESULT_NOTIFICATION_ID = 2

        const val REQUEST_CODE_CONTENT = 1
        const val REQUEST_CODE_CANCEL = 2

        const val PERCENT_START = 0
        const val PERCENT_MAX = 100

        /** The encode's private scratch file; deleted on every exit path. */
        const val TEMP_OUTPUT_NAME = "redcut-export.mp4"

        const val GALLERY_FOLDER = "RedCut"
        const val MIME_VIDEO_MP4 = "video/mp4"

        const val COULD_NOT_SAVE = "The file could not be saved to the gallery"
        const val ENCODE_FAILED = "The export failed while encoding"
    }
}
