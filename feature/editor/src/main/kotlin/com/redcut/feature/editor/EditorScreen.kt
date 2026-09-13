package com.redcut.feature.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.redcut.domain.document.CutAvailability
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.availabilityFor

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

    // A successful import is MINOR INFORMATION, and the device pass asked for it as a toast rather than
    // a banner. A REFUSAL is not minor — it says a file the user picked cannot be used, and that needs a
    // surface they can read twice — so the banner stays for that case and only that case.
    //
    // Keyed on the report, so it fires once; the report is cleared immediately after, which is what stops
    // it firing again on a recomposition or when the user comes back to the screen.
    val context = LocalContext.current
    LaunchedEffect(state.import) {
        val report = state.import ?: return@LaunchedEffect
        if (report.rejected.isEmpty() && report.importedCount > 0) {
            Toast.makeText(context, report.toastText(), Toast.LENGTH_SHORT).show()
            viewModel.onIntent(EditorIntent.DismissImport)
        }
    }

    EditorScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onImportClick = { pickMedia.launch(VIDEO_MIME_TYPES) },
        onExport = onExport,
        onBack = onBack,
        onThumbnail = viewModel::timelineThumbnail,
        onPreviewFrame = viewModel::previewFrame,
    )
}

/**
 * FR-1.4's report, as a one-line toast.
 *
 * "Added 1 clip" rather than a sentence about a report: nothing is wrong, and the user asked for this to
 * be minor information. The plural branch is not cosmetic — an import of three files is one action, and
 * a message that read "Added 1 clip" three times would be three interruptions for one thought.
 */
private fun ImportReport.toastText(): String = when (importedCount) {
    1 -> "Added 1 clip"
    else -> "Added $importedCount clips"
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
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
) {
    // The strips are fixed heights and the preview and tracks share what is left — see the note on the
    // strip heights in EditorLayout.kt for why the revision's percentages cannot be transcribed directly.
    Column(modifier = Modifier.fillMaxSize().statusBarInset()) {
        PreviewHalf(
            state = state,
            onImportClick = onImportClick,
            onIntent = onIntent,
            onBack = onBack,
            onPreviewFrame = onPreviewFrame,
            onThumbnail = onThumbnail,
        )

        TimelineControls(state = state, onIntent = onIntent)

        TracksSlice(state = state, onIntent = onIntent, onThumbnail = onThumbnail)

        BottomToolbar(state = state, onIntent = onIntent)

        // Only refusals reach the banner; a clean import was toasted by the route and its report cleared.
        state.import
            ?.takeIf { report -> report.rejected.isNotEmpty() }
            ?.let { report ->
                ImportBanner(
                    report = report,
                    onDismiss = { onIntent(EditorIntent.DismissImport) },
                )
            }

        // The export sheet (FR-5.1), drawn here rather than in the route so the screen stays a pure
        // function of state plus callbacks — which is what keeps it previewable and screenshot-testable.
        // Its choices come from the DOCUMENT's canvas, so the sizes it offers are the project's own
        // orientation, and `onExport` is the flow the sheet's (still inert) Start button leads into.
        state.exportSheet?.let { sheet ->
            ExportSheetDialog(
                sheet = sheet,
                choices = exportResolutionsFor(state.document.canvas),
                onSelectResolution = { onIntent(EditorIntent.SetExportResolution(it)) },
                onDismiss = { onIntent(EditorIntent.DismissExport) },
                onStartExport = onExport,
            )
        }
    }
}

/**
 * The Cut tools (FR-2.2–2.6).
 *
 * Every button's enabled state AND its explanation come from `availabilityFor` — the same function the
 * ViewModel's command path consults before it applies anything. One rule, two readers: a button that
 * offered what the command would refuse is the classic way an editor feels broken.
 *
 * When NOTHING can be cut, the reason is shown. When only some tools are blocked, it is not: the
 * reasons differ per tool (delete is unavailable on the last clip while split is fine), and printing
 * one tool's reason under a row of four would be worse than printing none.
 */
@Composable
internal fun CutTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val rows = remember(state.document, state.playheadUs) {
        CUT_TOOLS.map { tool -> tool to state.document.availabilityFor(tool, state.playheadUs) }
    }
    val allBlocked = rows.none { (_, availability) -> availability is CutAvailability.Available }
    val reasons = rows.mapNotNull { (_, availability) ->
        (availability as? CutAvailability.Unavailable)?.reason
    }.distinct()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        // Horizontally scrollable, CapCut-style, and that is a fix rather than a style choice: six tool
        // buttons at roughly 72 dp each are ~432 dp of row, and a phone is 360 dp wide, so a plain `Row`
        // overflows and drops the last tools off the edge. A tool strip that cannot show all its tools is
        // the same class of bug as a strip too short to render them — the one the device pass just found.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEach { (tool, availability) ->
                TextButton(
                    onClick = { onIntent(EditorIntent.ApplyCut(tool)) },
                    enabled = availability is CutAvailability.Available,
                ) {
                    Text(tool.label(), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (allBlocked && reasons.size == 1) {
            Text(text = reasons.first(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val CUT_TOOLS = listOf(
    CutTool.SPLIT,
    CutTool.CUT_LEFT,
    CutTool.CUT_RIGHT,
    CutTool.DELETE,
    CutTool.MERGE,
    CutTool.DUPLICATE,
)

/** A tool's button word. Here rather than in the domain because it is a UI word, not a domain name. */
private fun CutTool.label(): String = when (this) {
    CutTool.SPLIT -> "Split"
    CutTool.CUT_LEFT -> "Cut left"
    CutTool.CUT_RIGHT -> "Cut right"
    CutTool.DELETE -> "Delete"
    CutTool.MERGE -> "Merge"
    CutTool.DUPLICATE -> "Duplicate"
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
