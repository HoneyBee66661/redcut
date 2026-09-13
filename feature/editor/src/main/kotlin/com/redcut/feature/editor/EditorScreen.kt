package com.redcut.feature.editor

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.redcut.domain.document.CutAvailability
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.FrameStep
import com.redcut.domain.document.availabilityFor
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
 * The screen's top row: where the user is, what the project is called, and the two actions that are not
 * about the timeline.
 *
 * Extracted when `EditorScreen` reached `detekt`'s function-length limit, and the limit was right again:
 * the screen is "toolbar, banner, tabs, stage, tools, timeline" as a list of things, and a toolbar drawn
 * inline makes that list harder to see rather than shorter to read.
 *
 * Export is disabled on an empty document, and that is the only enablement rule here: it is a fact about
 * the DOCUMENT, while every other control's rule lives with the thing it acts on.
 */
@Composable
private fun EditorToolbar(
    state: EditorUiState,
    onImportClick: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
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
        // Import sits before Export because that is the order of the user's work: a document with no
        // clips has nothing to export, and the button says so.
        TextButton(onClick = onImportClick) { Text("Import") }
        TextButton(onClick = onExport, enabled = state.document.clips.isNotEmpty()) {
            Text("Export")
        }
    }
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
    Column(modifier = Modifier.fillMaxSize()) {
        EditorToolbar(
            state = state,
            onImportClick = onImportClick,
            onExport = onExport,
            onBack = onBack,
        )

        // Only refusals reach the banner; a clean import was toasted by the route and its report cleared.
        state.import
            ?.takeIf { report -> report.rejected.isNotEmpty() }
            ?.let { report ->
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

        StageBody(state = state, onThumbnail = onThumbnail, onPreviewFrame = onPreviewFrame)

        // The stage's own tools, under its tabs: the Cut tools only make sense while the Cut stage is
        // open, and a global row of them would be a row of disabled buttons in the other stages.
        if (state.stage == Stage.Cut) {
            CutTools(state = state, onIntent = onIntent)
        }

        // The Edit stage's controls (task 2.1). Shown only in the Edit stage: the Cut tools belong to
        // cutting, and speed sliders under a cut gesture would be controls for something the user is not
        // doing. The stage is already the screen's own state, so no new plumbing.
        if (state.stage == Stage.Edit) {
            Inspector(state = state, onIntent = onIntent)
        }

        // Frame-stepping sits with the timeline rather than with the Cut tools: it moves the PLAYHEAD,
        // so it is useful in every stage, and FR-2.9's whole purpose is to place the playhead exactly
        // before another tool acts on it.
        FrameStepButtons(state = state, onIntent = onIntent)

        // The timeline sits between the preview and the history bar, which is the layout §7.1
        // draws. It is given a fixed height rather than a weight: the preview is what should grow
        // when the screen does, and a timeline that stretched with the window would show more
        // empty track rather than more clips.
        TimelineCanvas(
            document = state.document,
            playheadUs = state.playheadUs,
            selection = state.selection,
            tool = state.tool,
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
private fun CutTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val rows = remember(state.document, state.playheadUs) {
        CUT_TOOLS.map { tool -> tool to state.document.availabilityFor(tool, state.playheadUs) }
    }
    val allBlocked = rows.none { (_, availability) -> availability is CutAvailability.Available }
    val reasons = rows.mapNotNull { (_, availability) ->
        (availability as? CutAvailability.Unavailable)?.reason
    }.distinct()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row {
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

/**
 * FR-2.9's frame-step buttons.
 *
 * Always visible, unlike the Cut tools: whoever needs a frame-accurate playhead needs these, and the
 * millisecond readout between them is what makes the step visible — one frame is 33 ms, which is a
 * third of a blink, and a button whose effect you cannot see reads as a button that did nothing.
 */
@Composable
private fun FrameStepButtons(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onIntent(EditorIntent.StepPlayhead(FrameStep.BACK)) }) {
            Text("◀ frame", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = "${state.playheadUs / 1_000} ms",
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = { onIntent(EditorIntent.StepPlayhead(FrameStep.FORWARD)) }) {
            Text("frame ▶", style = MaterialTheme.typography.labelLarge)
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
