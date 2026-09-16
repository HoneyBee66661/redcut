package com.redcut.feature.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.redcut.core.media.PreviewRenderer
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
        // The renderer is handed to the screen, not built by it: the UI holds the INTERFACE (§6.8 rule
        // D6) and `:app` decided which implementation it is, so no composable here can name a Media3
        // type even by accident.
        renderer = viewModel.previewRenderer,
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
    renderer: PreviewRenderer,
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
            onIntent = onIntent,
            renderer = renderer,
            onImportClick = onImportClick,
            onBack = onBack,
            onPreviewFrame = onPreviewFrame,
        )

        TimelineControls(state = state, renderer = renderer, onIntent = onIntent)

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
 * The Cut tools (FR-2.2–2.6), drawn as a tool strip rather than a row of text buttons.
 *
 * Every button's enabled state AND its explanation come from `availabilityFor` — the same function the
 * ViewModel's command path consults before it applies anything. One rule, two readers: a button that
 * offered what the command would refuse is the classic way an editor feels broken.
 *
 * ### The LibreCuts pattern, re-stated in Compose
 *
 * The reference build draws this strip as a horizontal `LinearLayout` of 60 dp columns, each column an
 * icon in a 40 dp circle above an 11 sp label. This composable is that layout in Compose's vocabulary
 * — [CutToolButton] is the column — and the mapping is deliberate rather than stylistic: a word per
 * tool (the previous row) could not show six tools and their state at a glance, and the label under an
 * icon is what the user's thumb actually reads. The width and heights are the reference build's, so the
 * strip's density matches the muscle memory the screenshots were approved with.
 *
 * When NOTHING can be cut, the reason is shown. When only some tools are blocked, it is not: the
 * reasons differ per tool (delete is unavailable on the last clip while split is fine), and printing
 * one tool's reason under a row of four would be worse than printing none. That rule is
 * [cutToolsReason]; the line it feeds is [CutToolReason], which is drawn in BOTH cases — see there for
 * why the empty one is not free.
 */
@Composable
internal fun CutTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val rows = remember(state.document, state.playheadUs) {
        CUT_TOOLS.map { tool -> tool to state.document.availabilityFor(tool, state.playheadUs) }
    }
    val reason = cutToolsReason(rows.map { (_, availability) -> availability })

    Column(modifier = Modifier.fillMaxWidth()) {
        // Horizontally scrollable, CapCut-style, and that is a fix rather than a style choice: six tool
        // columns at 60 dp each are 360 dp of row before padding, and a phone is 360 dp wide, so a plain
        // `Row` overflows and drops the last tools off the edge. A tool strip that cannot show all its
        // tools is the same class of bug as a strip too short to render them — the one the device pass
        // just found.
        //
        // The vertical padding is part of the strip, not of a button, so the columns keep one shared
        // edge — the reference build's `paddingVertical="8dp"` on the LinearLayout.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = TOOL_STRIP_PADDING_V),
        ) {
            rows.forEach { (tool, availability) ->
                CutToolButton(
                    tool = tool,
                    enabled = availability is CutAvailability.Available,
                    onClick = { onIntent(EditorIntent.ApplyCut(tool)) },
                )
            }
        }
        CutToolReason(reason)
    }
}

/**
 * One tool of the strip: the icon-above-label column, disabled greyed.
 *
 * The dimensions are the reference build's toolbar button — a 60 dp-wide column, a 40 dp icon tile, an
 * 11 sp `sans-serif-medium` label 4 dp under it — because the restyle is a port of a layout the product
 * already approved, not a new design. Disabled draws both halves dimmed at Material's own disabled
 * opacity: a greyed glyph over a full-strength label would disagree with itself about whether the tap
 * does anything.
 *
 * The whole column is the target rather than the icon alone: at 60 dp it is comfortably wider than the
 * 48 dp touch floor, and a tap that lands on the label is a tap on the tool — the mistake a 40 dp icon
 * alone would invite.
 */
@Composable
private fun CutToolButton(tool: CutTool, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TOOL_WIDTH)
            .clip(TOOL_SHAPE)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = TOOL_PADDING_V),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TOOL_ICON_TILE)
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tool.icon(),
                contentDescription = tool.label(),
                modifier = Modifier.size(TOOL_ICON),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OPACITY)
                },
            )
        }
        Text(
            text = tool.label(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            fontSize = TOOL_LABEL_SP,
            maxLines = 1,
            modifier = Modifier.padding(top = TOOL_LABEL_GAP),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OPACITY)
            },
        )
    }
}

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
 * A tool's strip glyph. Here rather than in the domain for the reason [CutTool.label] is.
 *
 * The auto-mirrored variants are the ones named where the glyph carries a direction: they are the
 * non-deprecated homes of those symbols, and the build compiles with `-Werror`, so the old
 * `Icons.Filled` location is not an option.
 */
internal fun CutTool.icon(): ImageVector = when (this) {
    CutTool.SPLIT -> Icons.AutoMirrored.Filled.CallSplit
    CutTool.CUT_LEFT -> Icons.Filled.ChevronLeft
    CutTool.CUT_RIGHT -> Icons.Filled.ChevronRight
    CutTool.DELETE -> Icons.Filled.Delete
    CutTool.MERGE -> Icons.AutoMirrored.Filled.MergeType
    CutTool.DUPLICATE -> Icons.Filled.ContentCopy
}

/**
 * What the explanation row says: the ONE reason every tool shares, or "" when there is nothing to say.
 *
 * Pulled out of [CutTools] so the rule can be read and tested on its own — it is a fact about a list of
 * availabilities and nothing else, and it has two halves. The line appears only when NOTHING can be cut
 * (a reason under a row where three of four tools still work would misdescribe those three), and only
 * when the tools AGREE on it (delete's reason on a row where split is fine says the wrong thing about
 * split). Six identical sentences are one reason, which is what the `distinct` is for.
 *
 * "" rather than null because the caller draws the row either way: the empty string is what "no reason"
 * looks like in a slot that is always the same size, and [CutToolReason] turns it into blank text.
 */
internal fun cutToolsReason(availabilities: List<CutAvailability>): String {
    val allBlocked = availabilities.none { it is CutAvailability.Available }
    val reasons = availabilities.mapNotNull { availability ->
        (availability as? CutAvailability.Unavailable)?.reason
    }.distinct()
    return if (allBlocked && reasons.size == 1) reasons.first() else ""
}

/**
 * How many `bodySmall` lines the explanation row always occupies.
 *
 * Two, because that is what the longest reason needs on a 360 dp screen at the default font scale: the
 * reasons are sentences ("The timeline must keep at least one clip; delete is unavailable on the last
 * one."), not labels.
 */
private const val REASON_LINES = 2

/**
 * The explanation line: drawn ALWAYS, and always [REASON_LINES] lines tall.
 *
 * It used to be drawn only when there was something to say, and that made the toolbar's height a
 * function of what it said. The reason appears exactly as the playhead leaves the last clip,
 * `BottomToolbar` wraps its content, and the preview and the tracks above it take `weight(1f)` each — so
 * the line appearing pushed both flexible halves up by the height of a line, and the line vanishing
 * dropped them straight back. Dragging a clip's end onto the playhead read as a wobble, which is what
 * the device pass reported: *"saat gua scroll clip ke arah kiri dan end of clip menyentuh playhead, ui
 * agak naik beberapa pixel seperti shaking"*. Nothing was jittering geometrically; a row was being added
 * and removed, and this row is the one that was.
 *
 * So the row is a SLOT, and both of the text's bounds are load-bearing. `minLines` is the half that fixes
 * the reported crossing: it reserves the room in the state where there is nothing to say, so the row is as
 * tall with an empty string in it as it is with a reason. `maxLines` is the ceiling, and without it a
 * longer reason — a bigger font scale, a narrower screen — would push the preview on the way in, which is
 * the same jump arriving from the other side. The ellipsis that ceiling can produce is the deliberate
 * cost of it, and it is the cheaper of the two: a truncated tail at a very large font scale beats a
 * preview that moves under the user's finger.
 *
 * The text is `" "` rather than `""` when there is nothing to say, and that is not cosmetic: Compose
 * measures an EMPTY string as zero height, `minLines` included, so an empty string would hand the jump
 * back in the one state this row exists to hold open. A space is a line with no glyph in it — nothing
 * paints, and the line is measured like any other line of the same style.
 */
@Composable
private fun CutToolReason(reason: String) {
    Text(
        text = reason.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall,
        minLines = REASON_LINES,
        maxLines = REASON_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

private val CUT_TOOLS = listOf(
    CutTool.SPLIT,
    CutTool.CUT_LEFT,
    CutTool.CUT_RIGHT,
    CutTool.DELETE,
    CutTool.MERGE,
    CutTool.DUPLICATE,
)

/**
 * The tool strip's geometry — the reference build's toolbar button, restated (see [CutToolButton]).
 *
 * [TOOL_LABEL_SP] is a `TextUnit` rather than a `dp` because it is a type size, and the strip's height
 * stability the reason row depends on is a function of all of them together: every column is the same
 * fixed stack, so the row is as tall for a disabled tool as for an enabled one.
 */
private val TOOL_WIDTH = 60.dp
private val TOOL_ICON_TILE = 40.dp
private val TOOL_ICON = 24.dp
private val TOOL_LABEL_GAP = 4.dp
private val TOOL_PADDING_V = 6.dp
private val TOOL_STRIP_PADDING_V = 8.dp
private val TOOL_LABEL_SP = 11.sp
private val TOOL_SHAPE = RoundedCornerShape(8.dp)

/** Material's own disabled opacity — the value a disabled `TextButton`'s content draws at. */
private const val DISABLED_OPACITY = 0.38f

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
