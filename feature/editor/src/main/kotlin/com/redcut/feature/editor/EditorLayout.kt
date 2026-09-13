package com.redcut.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.redcut.domain.document.FrameStep
import com.redcut.feature.editor.timeline.TimelineCanvas

/**
 * The layout's fixed strip heights.
 *
 * ### Why these are dp and not the revision's percentages
 *
 * The revision gives the bottom half as 5 / 85 / 10, and those numbers are the INTENT. On a phone they are
 * not buildable as written: 5 % of half a 800 dp screen is ~20 dp, and a Material button is 48 dp — so the
 * strip's contents were clipped away entirely. The device pass saw the result: *"tombol import export
 * hilang … tombol exit editor juga tidak ada … strip undo redo tidak ada"*. Nothing was mis-wired; the
 * slices were too short to render their buttons.
 *
 * So the two single-row strips are FIXED heights (a Material button is 48 dp, so 48 dp fits exactly one
 * row), the toolbar WRAPS its content because it holds two rows and a reason line, and the preview and the
 * tracks share what is left with equal weights. On a 800 dp screen that is within a few percent of the
 * revision's proportions AND every control is reachable; the percentages are documented rather than
 * transcribed, because a number that hides the buttons is not the requirement.
 */
private val TRANSPORT_HEIGHT = 48.dp
private val BAR_HEIGHT = 48.dp

/**
 * The layout's flexible weights: the preview and the tracks, equal, after the fixed strips.
 *
 * `PREVIEW_HALF` and `TRACKS_HALF` are named rather than inline `1f` so the intent ("these two ARE the
 * halves") survives the next reader.
 */
private const val PREVIEW_HALF = 1f
private const val TRACKS_HALF = 1f

/**
 * The editor's layout (docs/UI_REVISION_1.md): portrait, two halves, control strips at real heights.
 *
 * ```
 * +--------------------------------------+   <- status bar inset
 * | Exit  untitled 1        [Import][Export]  |  BAR_HEIGHT
 * |              the preview                  |  weight 1
 * +--------------------------------------+
 * | ↶ ↷    ◀ 412 ms ▶ (play)          ◇  |  TRANSPORT_HEIGHT
 * | +----------------------------------+  |
 * | |      the tracks, outlined        |  |  weight 1, inset 10% left / 4dp top
 * | +----------------------------------+  |
 * +--------------------------------------+
 * | stage tabs                            |  wraps content
 * | Cut tools / inspector                 |
 * +--------------------------------------+
 * ```
 */

/** The top half: the preview, with the bar that exits, imports and exports. */
@Composable
internal fun ColumnScope.PreviewHalf(
    state: EditorUiState,
    onImportClick: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
) {
    Column(modifier = Modifier.fillMaxWidth().weight(PREVIEW_HALF)) {
        TopBar(
            state = state,
            onImportClick = onImportClick,
            onExport = onExport,
            onBack = onBack,
        )
        StagePreview(
            state = state,
            onPreviewFrame = onPreviewFrame,
            onThumbnail = onThumbnail,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * Exit flush left, the project's name beside it, Import and Export flush right.
 *
 * A fixed [BAR_HEIGHT] rather than a share of the half: see the note on the strip heights. Export is
 * disabled on an empty document — present rather than hidden, so the button does not appear the moment the
 * user does the thing it needs. That is the only enablement rule HERE, and it is a fact about the DOCUMENT;
 * every other control's rule lives with the thing it acts on.
 *
 * Extracted from `EditorScreen` when that file reached `detekt`'s function-length limit, and the limit was
 * right again: the screen is "bar, banner, tabs, stage, tools, timeline" as a list of things, and a bar
 * drawn inline makes that list harder to see rather than shorter to read.
 *
 * The `Resolution` toggle that briefly lived on the import button is GONE, by the user's word: *"tombol import
 * dan export kembali seperti desain awal, hilangkan tombol resolusi, karena relevan saat kita tekan tombol
 * export kita setting resolusi dll di popup khusus"*. The setting belongs in the export sheet, where the user
 * is already choosing what to produce — tracked as task B2.
 */
@Composable
private fun TopBar(
    state: EditorUiState,
    onImportClick: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("Exit") }
        Text(
            text = state.document.name,
            style = MaterialTheme.typography.titleSmall,
            // One line, ellipsised, and it may not push the buttons: a project named by a phrase would
            // otherwise shove Export off the edge of a 360 dp screen — the same failure the device pass
            // reported, arriving from the other direction.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
        )
        Box(modifier = Modifier.weight(1f))
        TextButton(onClick = onImportClick) { Text("Import") }
        TextButton(onClick = onExport, enabled = state.document.clips.isNotEmpty()) {
            Text("Export")
        }
    }
}

/**
 * The transport strip: undo and redo flush left, the playhead's position and the play controls centred,
 * the keyframe placeholder flush right.
 *
 * Play is inert and says so, because the preview is a still frame until the composition player lands; a
 * button that looked live and did nothing would be worse than one that is visibly waiting. The keyframe
 * button is in its place and does nothing yet, tracked as its own task.
 */
@Composable
internal fun ColumnScope.TimelineControls(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val (canUndo, canRedo) = when (val history = state.history) {
        is HistoryState.Ready -> history.canUndo to history.canRedo
        HistoryState.Busy -> false to false
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(TRANSPORT_HEIGHT).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onIntent(EditorIntent.Undo) },
            enabled = canUndo,
        ) { Text(UNDO_ARROW) }
        TextButton(
            onClick = { onIntent(EditorIntent.Redo) },
            enabled = canRedo,
        ) { Text(REDO_ARROW) }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FrameStepButtons(state = state, onIntent = onIntent)
                TextButton(onClick = {}, enabled = false) { Text(PLAY_GLYPH) }
            }
        }
        TextButton(onClick = {}, enabled = false) { Text(KEYFRAME_GLYPH) }
    }
}

/**
 * The tracks: the scrollable body, flush to the screen's edges.
 *
 * The gutter and the outline that the previous round added are GONE, both by the user's word: *"hilangkan
 * outline container"* and *"body track mentok kiri"*. The 10 % left inset had been making room for a track
 * header column — and the same device pass asked for track SELECTION, so the header is not the design; the
 * user's answer to how to select a track was tapping the lane's background (*"gue ikut rekomendasi lu"*).
 *
 * Nothing is clamped on the right either: content runs off the screen as the user drags. The left edge is
 * bounded only by the playhead, which is what `scrollCentering` already guarantees — time 0 can reach the
 * playhead and no further, so the head of the first clip never leaves the screen on the right
 * (*"left clip head saat clip discroll ke kanan, berhenti di playhead"*).
 */
@Composable
internal fun ColumnScope.TracksSlice(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
) {
    Box(modifier = Modifier.fillMaxWidth().weight(TRACKS_HALF)) {
        TimelineCanvas(
            document = state.document,
            playheadUs = state.playheadUs,
            selection = state.selection,
            tool = state.tool,
            onIntent = onIntent,
            onThumbnail = onThumbnail,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The bottom strip: the stage selector and the tools for the selected stage.
 *
 * The revision removed the stage bar from the top of the screen, so the selector lives here, where the
 * tools it selects between already are.
 */
@Composable
internal fun ColumnScope.BottomToolbar(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    // NO fixed height, and no weight: a toolbar that WRAPS its content cannot clip it. The first version
    // gave it a fixed 88 dp while `CutTools` needs up to ~116 dp — a row of 48 dp buttons plus the line that
    // explains why they are all disabled — so the Cut buttons would have been cut off in exactly the case the
    // device pass had just complained about. Wrapping also means the toolbar costs only what it shows.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        TabRow(selectedTabIndex = state.stage.ordinal) {
            Stage.entries.forEach { stage ->
                Tab(
                    selected = stage == state.stage,
                    onClick = { onIntent(EditorIntent.SelectStage(stage)) },
                    text = { Text(stage.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            when (state.stage) {
                Stage.Cut -> CutTools(state = state, onIntent = onIntent)
                Stage.Edit -> Inspector(state = state, onIntent = onIntent)
                Stage.Effect -> Text(
                    text = "The effect stage arrives with the render graph's effects.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * FR-2.9's frame-step control: "◀ 412 ms ▶".
 *
 * The millisecond readout is not decoration: a one-frame move is a few pixels of timeline, so without a
 * number the user cannot tell a step that worked from a tap that missed.
 */
@Composable
internal fun FrameStepButtons(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onIntent(EditorIntent.StepPlayhead(FrameStep.BACK)) }) {
            Text("◀", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = "${state.playheadUs / MICROS_PER_MILLI} ms",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        TextButton(onClick = { onIntent(EditorIntent.StepPlayhead(FrameStep.FORWARD)) }) {
            Text("▶", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The stage's own body: the preview frame, or the sentence that says what is missing. */
@Composable
private fun StagePreview(
    state: EditorUiState,
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        StageBody(state = state, onThumbnail = onThumbnail, onPreviewFrame = onPreviewFrame)
    }
}

/**
 * The root's inset for the status bar.
 *
 * A separate modifier because the reason is a device pass rather than a preference: the revision puts the
 * bar flush at the top of the half, and on a phone that is under the battery and signal indicators.
 */
@Composable
internal fun Modifier.statusBarInset(): Modifier = windowInsetsPadding(WindowInsets.statusBars)

private const val UNDO_ARROW = "↶"
private const val REDO_ARROW = "↷"
private const val PLAY_GLYPH = "▶"
private const val KEYFRAME_GLYPH = "◇"
private const val MICROS_PER_MILLI = 1_000L
