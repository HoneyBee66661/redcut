package com.redcut.core.media

import android.net.Uri
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.flow.StateFlow

/**
 * The export port (FR-5, spec Phase 4.1–4.5) — the same shape the preview has: a feature declares
 * what it needs, `:core:media` holds the interface, and the composition root binds the engine that
 * answers it. The feature that starts an export may not see `:engine:media3` (§4.1 rule 2), so this
 * is what it sees instead: hand over a compiled graph, watch [state], nothing else.
 *
 * The graph rather than the document is the currency here, for the same reason
 * [PreviewRenderer.attach] takes one: the compiled graph IS the contract the export must render
 * (§12.3), and compiling the document at the chosen [com.redcut.domain.render.OutputSpec] —
 * `TimelineCompiler.compile(document, preset)` — is the caller's job, done through the same
 * compiler the preview path uses. A port that accepted a document and compiled it itself would let
 * a second compile path grow one feature at a time.
 */
interface ExportController {

    /**
     * The export's state, from nothing to a saved file. Observed, never polled: the export runs in
     * a foreground service the UI cannot see, and this flow is the whole of what it publishes.
     */
    val state: StateFlow<ExportState>

    /**
     * Starts exporting [graph] — the caller's compiled render of the document at the target
     * [com.redcut.domain.render.OutputSpec].
     *
     * One export at a time: a start while another is preparing or running is ignored (logged, not
     * queued), because a queue of encodes is a queue of codecs the broker will not grant and the
     * user did not ask for two files. An empty graph is refused up front with a [ExportState.Failed]
     * rather than half-way through a service start.
     */
    fun start(graph: RenderGraph)

    /**
     * Asks the export in flight to stop. The request is a no-op when nothing is exporting, and the
     * answer arrives through [state] as [ExportState.Cancelled] — asking is not the same as knowing.
     */
    fun cancel()

    /**
     * Returns a terminal state ([ExportState.Succeeded], [ExportState.Failed],
     * [ExportState.Cancelled]) to [ExportState.Idle], so a sheet that is visited again shows the
     * choice the user left it on rather than the last run's corpse. Not a reset of the service —
     * the service has already stopped itself by the time a terminal state is showing.
     */
    fun dismiss()
}

/**
 * What an export is doing. The lifecycle is linear — [Idle] → [Preparing] → [Running] → one of the
 * three terminals ([Succeeded], [Failed], [Cancelled]) — and [dismiss] folds the terminals back to
 * [Idle].
 */
sealed interface ExportState {

    /** Nothing is happening; the sheet may show its resolution choice. */
    data object Idle : ExportState

    /**
     * The foreground service is starting (its `startForeground` window) and the encode has not
     * produced a measured percentage yet. Shown as "starting" rather than 0%: zero is a number
     * Media3 has not earned.
     */
    data object Preparing : ExportState

    /** Encoding, [progressPercent] of the way through as Media3 measures it. */
    data class Running(val progressPercent: Int) : ExportState

    /**
     * The file is published to `Movies/RedCut` (FR-5.8) and [savedUri] is its MediaStore Uri — the
     * exact thing the share sheet (FR-5.12) hands to `ACTION_SEND`.
     */
    data class Succeeded(val savedUri: Uri) : ExportState

    /** The export did not finish; [reason] is a sentence the sheet can show as-is. */
    data class Failed(val reason: String) : ExportState

    /** A requested cancellation (FR-5.9) landed. */
    data object Cancelled : ExportState
}
