package com.redcut.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.redcut.core.media.ExportState

/**
 * The export sheet (FR-5.1, FR-5.8, FR-5.12): resolution choice, export, progress, share.
 *
 * The screen is deliberately thin about the export itself — it knows the [ExportController] port
 * through the view model and nothing past it. The encode runs in a foreground service this module
 * cannot name (§4.1 rule 2), so what is shown here is a *state*, not a process: a percent while it
 * runs (FR-5.9's UI half), the saved Uri when it is done, and the share sheet (FR-5.12) that hands
 * that Uri to whatever app the user picks. Cancel lives on the system's notification rather than
 * in this screen, because the cancel that matters is the one that reaches the service when the
 * user is looking at something else (FR-5.9's cancel is Phase 4.4's ETA-and-in-sheet pass).
 */
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leaving folds a terminal state to Idle (a no-op mid-export, the port decides), so
            // the next visit opens on the choice the user left rather than a stale result.
            TextButton(
                onClick = {
                    viewModel.dismiss()
                    onBack()
                },
            ) { Text("Back") }
            Text(
                text = "Export",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        when (val exportState = uiState.exportState) {
            ExportState.Idle -> ChoiceContent(uiState = uiState, viewModel = viewModel)
            ExportState.Preparing, is ExportState.Running -> ProgressContent(exportState)
            is ExportState.Succeeded -> SuccessContent(savedUri = exportState.savedUri)
            is ExportState.Failed -> FailureContent(
                reason = exportState.reason,
                onRetry = viewModel::dismiss,
            )
            ExportState.Cancelled -> CancelledContent(
                onDone = {
                    viewModel.dismiss()
                    onBack()
                },
            )
        }
    }
}

/**
 * The idle sheet: the two FR-5.1 resolutions and the export button. An empty timeline is a
 * disabled button's problem, not an error's — the user simply has not put anything on the
 * timeline yet.
 */
@Composable
private fun ChoiceContent(uiState: ExportViewModel.UiState, viewModel: ExportViewModel) {
    if (!uiState.hasTimeline) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Nothing to export yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Add a clip to the timeline first",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ExportResolution.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = uiState.resolution == option,
                        role = Role.RadioButton,
                        onClick = { viewModel.selectResolution(option) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = uiState.resolution == option,
                    onClick = { viewModel.selectResolution(option) },
                )
                Text(text = option.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(
            onClick = viewModel::startExport,
            enabled = !uiState.isExporting,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Export")
        }
        Text(
            text = "MP4 · H.264 · saved to Movies/RedCut",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The encode in flight (FR-5.9's progress half). Preparing shows an indeterminate bar on purpose:
 * zero percent is a number Media3 has not measured yet, and showing it would claim a certainty
 * the encode has not earned.
 */
@Composable
private fun ProgressContent(exportState: ExportState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (exportState) {
            is ExportState.Running -> {
                LinearProgressIndicator(
                    progress = { exportState.progressPercent / PERCENT_MAX_FLOAT },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Exporting · ${exportState.progressPercent}%",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            else -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Starting the export",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        Text(
            text = "The export continues while the app is in the background",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The finished export (FR-5.8, FR-5.12): where it went, and the share sheet that hands the
 * MediaStore Uri — the same Uri, unmodified — to another app.
 */
@Composable
private fun SuccessContent(savedUri: Uri) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Export complete",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Saved to Movies/RedCut",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = { shareExportedVideo(context, savedUri) },
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text("Share")
        }
    }
}

@Composable
private fun FailureContent(reason: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Export failed",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text("Back")
        }
    }
}

@Composable
private fun CancelledContent(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Export cancelled",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onDone, modifier = Modifier.padding(top = 24.dp)) {
            Text("Done")
        }
    }
}

/**
 * FR-5.12's share sheet: `ACTION_SEND` with the MediaStore Uri as the stream. The read grant is
 * what makes the Uri usable by the receiving app — the export is the row's owner, and a share
 * without the grant is a share of something the other app may not read. The chooser, not a
 * dedicated target, is the whole feature: the user picks the app, as FR-5.12 says.
 */
private fun shareExportedVideo(context: Context, savedUri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = MIME_VIDEO_MP4
        putExtra(Intent.EXTRA_STREAM, savedUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** One place for the share type; it is the same string the service names the file with. */
private const val MIME_VIDEO_MP4 = "video/mp4"

/** The bar's denominator: an Int constant keeps the division a Float without a bare literal. */
private const val PERCENT_MAX_FLOAT = 100f
