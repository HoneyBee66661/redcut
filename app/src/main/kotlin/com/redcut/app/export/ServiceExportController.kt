package com.redcut.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.redcut.core.common.logging.RedcutLogger
import com.redcut.core.media.ExportController
import com.redcut.core.media.ExportState
import com.redcut.domain.render.RenderGraph
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The export port's one implementation, and the bridge the two halves of an export meet on: the
 * export sheet calls [start]/[cancel] here, `ExportService` — a component this class deliberately
 * knows only as an `Intent` target — publishes what the encode is doing back into [state].
 *
 * ### Why a service and not a coroutine on this singleton
 *
 * FR-5.10: an export must survive app backgrounding and screen-off. A coroutine on a process-lifetime
 * scope dies with the process's scheduling in the background; a foreground service with the
 * `mediaProcessing` type is the platform's contract that the work outlives the UI. This class holds
 * the state and the intent plumbing; the service holds the encode. The two meet in one process, so
 * the handoff is a `@Volatile` field written before `startForegroundService` and read in
 * `onStartCommand` — the IPC start gives the happens-before, and the graph never serialises through
 * an `Intent` extra (it can exceed the transaction budget on long timelines).
 *
 * What is NOT here yet, named rather than discovered later: §8.3 asks the request to be PERSISTED so
 * process death can offer a resume. Phase 4.4 owns resume; until then the request is in-memory and
 * `START_NOT_STICKY`, which is the honest answer for "does a killed export come back?" — no, and the
 * sheet shows [ExportState.Failed] on the next visit rather than pretending.
 */
@Singleton
class ServiceExportController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: RedcutLogger,
) : ExportController {

    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)

    override val state: StateFlow<ExportState> = mutableState.asStateFlow()

    @Volatile
    private var pendingGraph: RenderGraph? = null

    override fun start(graph: RenderGraph) {
        if (graph.videoLayers.isEmpty()) {
            logger.d(TAG, "refusing an export of an empty graph")
            mutableState.value = ExportState.Failed(NOTHING_TO_EXPORT)
            return
        }
        val current = mutableState.value
        if (current is ExportState.Preparing || current is ExportState.Running) {
            logger.d(TAG, "an export is already in progress; ignoring the second start")
            return
        }
        pendingGraph = graph
        mutableState.value = ExportState.Preparing
        val intent = Intent(context, ExportService::class.java)
            .setAction(ExportService.ACTION_EXPORT)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun cancel() {
        val current = mutableState.value
        if (current !is ExportState.Preparing && current !is ExportState.Running) return
        val intent = Intent(context, ExportService::class.java)
            .setAction(ExportService.ACTION_CANCEL)
        try {
            // startService, not the foreground variant: the service is already up (the state above
            // says so), and the cancel is a message to it, not a reason to start it.
            context.startService(intent)
        } catch (@Suppress("SwallowedException") refused: IllegalStateException) {
            // The state and the service can race — a terminal state published between the check and
            // this send means the service already stopped itself. There is then nothing to cancel,
            // which is the outcome the user wanted anyway.
            logger.d(TAG, "cancel arrived after the service stopped; nothing to cancel")
        }
    }

    override fun dismiss() {
        when (val current = mutableState.value) {
            is ExportState.Succeeded, is ExportState.Failed, ExportState.Cancelled ->
                mutableState.value = ExportState.Idle
            is ExportState.Idle, ExportState.Preparing, is ExportState.Running -> {
                logger.d(TAG, "dismiss ignored in $current; there is no result to dismiss")
            }
        }
    }

    /**
     * The service's half of the handoff: the graph [start] staged, or null when there is none.
     *
     * Taking is consuming — a graph is exported once, and a second `ACTION_EXPORT` for the same
     * staged graph would be a bug this returns null on rather than doubles.
     */
    fun takePendingGraph(): RenderGraph? {
        val staged = pendingGraph
        pendingGraph = null
        return staged
    }

    /**
     * Publishes the service's view of the encode into [state]. Public because the service is the
     * other half of this class, not because it is a second front door: the export sheet still only
     * ever sees the [ExportController] binding.
     */
    fun publish(next: ExportState) {
        mutableState.value = next
    }

    private companion object {
        const val TAG = "ServiceExportController"
        const val NOTHING_TO_EXPORT = "There is nothing on the timeline to export"
    }
}
