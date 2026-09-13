package com.redcut.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.redcut.domain.document.FrameStep

/**
 * The layout's weights. Named because they ARE the revision's percentages: a reader who sees
 * `weight(TRACKS_SLICE)` can check it against the spec without decoding a float, and the outer column's
 * total is what makes `PREVIEW_HALF` half the screen.
 */
internal const val PREVIEW_HALF = 1f
internal const val PREVIEW_BAR = 0.05f
internal const val PREVIEW_BODY = 0.95f
internal const val TRANSPORT_SLICE = 0.05f
internal const val TRACKS_SLICE = 0.85f
internal const val TOOLBAR_SLICE = 0.10f

/**
 * The editor's layout, as the revision specifies it: portrait, split in half, and then sliced by
 * percentages (docs/UI_REVISION_1.md).
 *
 * ```
 * +--------------------------------------+
 * | exit   project      [import][export] |  5% of the top half  (a bar)
 * |                                      |
 * |            the preview               |  the rest of the top half
 * |                                      |
 * +--------------------------------------+
 * | ↶ ↷        (play)      (keyframe)     |  5%  of the bottom half
 * |                                      |
 * |        the tracks, under a red line   |  85% of the bottom half
 * |                                      |
 * +--------------------------------------+
 * | the toolbar: what the stage shows     |  10% of the bottom half
 * +--------------------------------------+
 * ```
 *
 * ### Why weights rather than measured heights
 *
 * The percentages are of the SCREEN, and a `Column`'s weights are already relative to each other — so the
 * whole layout is one `Column` with weights `1 / 0.05 / 0.85 / 0.10` out of a total of 2.0: the preview is
 * exactly half, and the other three are 5 %, 85 % and 10 % of that other half. Writing the arithmetic in one
 * place beats four `fillMaxHeight(0.025f)`-style fractions, which say the same thing in a way a reader has
 * to compute.
 *
 * ### What is NOT here
 *
 * The `TabRow` that used to switch stages is gone: the stage bar the spec sketched does not exist in this
 * layout, and the bottom toolbar is what changes with the stage — so the stage selector lives INSIDE that
 * toolbar, where the tools it selects between already are.
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
        // 5 % of this half. Exit is flush left, per the revision: it is the way OUT of the project, so it
        // sits where a back gesture would look for it.
        Row(
            modifier = Modifier.fillMaxWidth().weight(PREVIEW_BAR).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Exit") }
            Text(
                text = state.document.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
            Box(modifier = Modifier.weight(1f))
            // Import before Export, and the two states the revision asked for: with clips, the import
            // button is a resolution toggle and export is live; without them, export is
            // present-but-disabled rather than hidden, so the button does not appear once the user has
            // done the thing it needs.
            TextButton(onClick = onImportClick) {
                Text(if (state.document.clips.isEmpty()) "Import" else "Resolution")
            }
            TextButton(onClick = onExport, enabled = state.document.clips.isNotEmpty()) {
                Text("Export")
            }
        }
        StagePreview(
            state = state,
            onPreviewFrame = onPreviewFrame,
            onThumbnail = onThumbnail,
            modifier = Modifier.fillMaxWidth().weight(PREVIEW_BODY),
        )
    }
}

/**
 * The bottom half's 5 %: the transport and the two things either side of it.
 *
 * Undo and redo flush left, play/pause centred, keyframe flush right — the revision's arrangement, and the
 * one every editor uses: the transport in the middle is where a thumb reaches for it.
 *
 * Play is a PLACEHOLDER and says so, because the preview is a still frame until the composition player
 * lands. A button that looked live and did nothing would be worse than one that is visibly waiting.
 */
@Composable
internal fun ColumnScope.TimelineControls(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    // Total over the sealed type, like the history bar it replaces: the day `HistoryState.Busy` gains a
    // field (an export's progress) this fails to compile until it is handled, which is the point of
    // modelling "busy" at all (§7.3). And the buttons are driven by the flags rather than by a depth
    // count, so the UI cannot disagree with the stack about whether an undo is available.
    val (canUndo, canRedo) = when (val history = state.history) {
        is HistoryState.Ready -> history.canUndo to history.canRedo
        HistoryState.Busy -> false to false
    }

    Row(
        modifier = Modifier.fillMaxWidth().weight(TRANSPORT_SLICE).padding(horizontal = 8.dp),
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
                // FR-2.9's frame-step buttons sit WITH the transport rather than in a group of their own:
                // they move the playhead by one frame, which is the same job the play button does over a
                // longer distance. The revision listed three groups in this slice, and frame-stepping is
                // part of the middle one — leaving it out would have removed the only UI FR-2.9 has.
                FrameStepButtons(state = state, onIntent = onIntent)
                TextButton(onClick = {}, enabled = false) { Text(PLAY_GLYPH) }
            }
        }
        TextButton(onClick = {}, enabled = false) { Text(KEYFRAME_GLYPH) }
    }
}

/**
 * FR-2.9's frame-step control: "◀ frame | 412 ms | frame ▶".
 *
 * The millisecond readout is not decoration: a one-frame move is a few pixels of timeline, so without a
 * number the user cannot tell a step that worked from a tap that missed.
 *
 * It lives in this file now because the layout is where it is placed (inside the transport group, in the
 * bottom half's 5 % slice). While extracting the layout, the cleanup that removed the old toolbar took this
 * with it — a reminder that "delete the unused composables" needs the list of what is still WANTED, not
 * just what is currently referenced.
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

private const val MICROS_PER_MILLI = 1_000L

/**
 * The bottom half's 10 %: the stage selector and the tools for the selected stage.
 *
 * The stage selector lives here rather than in a bar of its own because the revision removed that bar: the
 * preview is always the preview, and this strip is the part of the screen that changes with the stage. With
 * the stages gone from the top, a compact `TabRow` here is what keeps them reachable.
 */
@Composable
internal fun ColumnScope.BottomToolbar(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().weight(TOOLBAR_SLICE)) {
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
            modifier = Modifier.fillMaxWidth().weight(1f),
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

/** A row of transport glyphs, kept out of the layout code so the layout reads as a layout. */
private const val UNDO_ARROW = "↶"
private const val REDO_ARROW = "↷"
private const val PLAY_GLYPH = "▶"
private const val KEYFRAME_GLYPH = "◇"

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
