package com.redcut.feature.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.redcut.feature.editor.timeline.TimelineCanvas

/**
 * The editor destination: the ViewModel owner.
 *
 * The split between this and [EditorScreen] is the point — everything that needs Hilt or
 * a lifecycle lives here, and the screen below is a pure function of state plus a
 * callback. That is what makes the screen previewable, screenshot-testable (Phase 12.2)
 * and reviewable without a DI graph in your head.
 */
@Composable
fun EditorRoute(
    onExport: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // The SAF picker (FR-1.1). `OpenMultipleDocuments` is the multi-select form of
    // ACTION_OPEN_DOCUMENT, and it needs no storage permission at all: the read grant comes
    // with the URIs the user picked, which is exactly why the requirement specifies SAF
    // rather than READ_MEDIA_VIDEO.
    //
    // The launcher lives HERE, not in EditorScreen: it is an Activity-result concern, and the
    // screen below stays a pure function of state plus callbacks — the property that keeps it
    // previewable and (Phase 12.2) screenshot-testable.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        viewModel.onIntent(EditorIntent.ImportMedia(uris.map(Uri::toString)))
    }

    EditorScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onImportClick = { pickMedia.launch(VIDEO_MIME_TYPES) },
        onExport = onExport,
        onBack = onBack,
        onThumbnail = viewModel::timelineThumbnail,
    )
}

/**
 * What the picker may show (FR-1.1). Video only: an audio bed is FR-1.6 and image overlays
 * are FR-1.7, both `Should` and both later — offering them now would mean accepting files the
 * probe and the policy are not yet asked to handle.
 */
private val VIDEO_MIME_TYPES = arrayOf("video/*")

/**
 * The stage host (spec §7.1).
 *
 * The stage bar is a `TabRow` for now, and a `TabRow` is honest: the spec's sketch shows
 * a segmented stage bar, but where it sits relative to the preview and timeline depends
 * on the timeline existing (Phase 1.5). What is real here is the shape — stage in state,
 * one screen, no navigation — so the timeline lands inside it rather than replacing it.
 *
 * The preview and timeline regions are the `Box` below: a sentence naming the phase that
 * fills them, because a fake timeline is worse than an obviously absent one.
 */
@Composable
internal fun EditorScreen(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    onImportClick: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Projects") }
            Text(
                text = state.document.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            Box(modifier = Modifier.weight(1f))
            // Import sits before Export because that is the order of the user's work: a
            // document with no clips has nothing to export, and the button states it.
            TextButton(onClick = onImportClick) { Text("Import") }
            TextButton(onClick = onExport, enabled = state.document.clips.isNotEmpty()) {
                Text("Export")
            }
        }

        state.import?.let { report ->
            ImportBanner(
                report = report,
                onDismiss = { onIntent(EditorIntent.DismissImport) },
            )
        }

        TabRow(selectedTabIndex = state.stage.ordinal) {
            Stage.entries.forEach { stage ->
                Tab(
                    selected = stage == state.stage,
                    onClick = { onIntent(EditorIntent.SelectStage(stage)) },
                    text = { Text(stage.label) },
                )
            }
        }

        StageBody(state = state)

        // The timeline sits between the preview and the history bar, which is the layout §7.1
        // draws. It is given a fixed height rather than a weight: the preview is what should grow
        // when the screen does, and a timeline that stretched with the window would show more
        // empty track rather than more clips.
        TimelineCanvas(
            document = state.document,
            playheadUs = state.playheadUs,
            selection = state.selection,
            onIntent = onIntent,
            onThumbnail = onThumbnail,
            modifier = Modifier
                .fillMaxWidth()
                .height(TIMELINE_HEIGHT_DP.dp)
                .padding(horizontal = 8.dp),
        )

        HistoryBar(history = state.history, onIntent = onIntent)
    }
}

/**
 * The stage's body, which takes the room the timeline does not.
 *
 * An extension on `ColumnScope` because `Modifier.weight` only exists in a column or row: the
 * weight is the reason this cannot be a plain composable.
 *
 * Until Phase 1.11 (the preview) this is a sentence per stage. The preview — Media3's
 * `CompositionPlayer` — replaces the whole function, not the text inside it.
 */
@Composable
private fun ColumnScope.StageBody(state: EditorUiState) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = state.stage.detail,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Undo / redo, driven by [HistoryState].
 *
 * Two things worth noting. The `when` is total over the sealed type, so the day
 * `HistoryState.Busy` gains a field (an export's progress) this function fails to compile
 * until it is handled — which is the point of modelling "busy" at all (§7.3). And the
 * buttons are driven by the flags rather than by `undoDepth > 0`: the UI must not be able
 * to disagree with the stack about whether an undo is available.
 */
@Composable
private fun HistoryBar(history: HistoryState, onIntent: (EditorIntent) -> Unit) {
    val (canUndo, canRedo, label) = when (history) {
        is HistoryState.Ready -> Triple(history.canUndo, history.canRedo, history.topLabel)
        HistoryState.Busy -> Triple(false, false, null)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onIntent(EditorIntent.Undo) }, enabled = canUndo) {
            Text(label?.let { "Undo $it" } ?: "Undo")
        }
        TextButton(onClick = { onIntent(EditorIntent.Redo) }, enabled = canRedo) {
            Text("Redo")
        }
    }
}

/**
 * The timeline's height.
 *
 * Fixed rather than a weight: the preview is what should grow when the window does. A timeline
 * that stretched would show more empty track instead of more clips, which is the opposite of what
 * a taller screen is for.
 */
private const val TIMELINE_HEIGHT_DP = 96f

/**
 * What the last import did (FR-1.2, FR-1.4).
 *
 * A banner rather than a toast or a snackbar, and for a specific reason: FR-1.4's rejections
 * are *per file*, so an import of five videos can produce up to four explanations. A
 * transient message can hold one line and disappears while it is being read; this stays until
 * dismissed, which is the only honest way to show a list the user may need to act on (relink
 * a source, re-encode a file).
 *
 * It draws nothing for a clean import with rejections absent — the summary line is still
 * worth showing, because "3 clips added" is the confirmation that the gesture worked.
 */
@Composable
private fun ImportBanner(report: ImportReport, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text = report.summaryLine(), style = MaterialTheme.typography.titleSmall)
        report.messages.forEach { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** The one-line headline: what was added, and whether anything was refused. */
private fun ImportReport.summaryLine(): String {
    val clips = if (importedCount == 1) "clip" else "clips"
    return when {
        importedCount == 0 && hasRejections -> "Nothing was imported"
        hasRejections -> "Added $importedCount $clips, refused ${rejected.size}"
        else -> "Added $importedCount $clips"
    }
}
