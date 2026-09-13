package com.redcut.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.redcut.domain.document.CanvasSpec

/**
 * The export sheet's state: the frame size the user is about to produce (FR-5.1).
 *
 * A size and nothing else, deliberately. The sheet is the home the deleted top-bar toggle never was — the
 * user's word put the setting here: *"relevan saat kita tekan tombol export kita setting resolusi dll di
 * popup khusus"* — and what it holds today is the one setting the toolbar used to half-offer. Format,
 * bitrate, progress and ETA arrive with the export itself (spec Phase 4.1-4.5), as FIELDS here rather than
 * as a second sheet, so that one place answers "what is about to be produced?".
 */
@Immutable
data class ExportSheet(val resolution: CanvasSpec)

/**
 * The two frame sizes FR-5.1 allows — 720p and 1080p, and nothing else — in [canvas]'s orientation.
 *
 * "Nothing else" is the requirement, not a starting point: the spec's own non-goals list *"resolutions
 * beyond 720p/1080p"*, so the sheet must not grow a third entry by accident. The ORIENTATION comes from the
 * project's canvas, because an export that silently flips a landscape project into portrait is a worse bug
 * than a missing option, and [CanvasSpec] already models both orientations (FR-5.7).
 */
internal fun exportResolutionsFor(canvas: CanvasSpec): List<CanvasSpec> = listOf(
    if (canvas.isPortrait) CanvasSpec.PORTRAIT_720 else CanvasSpec.LANDSCAPE_720,
    if (canvas.isPortrait) CanvasSpec.PORTRAIT_1080 else CanvasSpec.LANDSCAPE_1080,
)

/**
 * The size a project of [canvas] starts on: its own, when FR-5.1 allows it.
 *
 * Opening the sheet on the project's own size makes the common case — "export what I have been looking at"
 * — one tap, with the other size one tap away. The fallback is the larger of the two rather than a
 * hardcoded 1080p, so the rule stays true if [CanvasSpec] ever grows a size it does not know about.
 */
internal fun defaultExportResolutionFor(canvas: CanvasSpec): CanvasSpec {
    val allowed = exportResolutionsFor(canvas)
    return allowed.firstOrNull { it == canvas } ?: allowed.last()
}

/**
 * The export sheet (FR-5.1): what the Export button opens, and where the resolution now lives.
 *
 * ### Why a dialog, and why this one has no export in it yet
 *
 * The top bar's `Resolution` toggle was deleted by the user's word, so this sheet is what keeps the
 * capability: removing the toggle costs the user nothing if the setting is one tap further along the flow
 * they were already taking. A Material dialog rather than a new bottom-sheet or popup type because this is
 * the app's first dialog — there is no existing surface pattern to match, and inventing a second mechanism
 * for one screen is how two ways of doing the same thing start.
 *
 * `Start export` is PRESENT and DISABLED, which is this codebase's rule for a control whose phase has not
 * arrived (the transport's Play and keyframe buttons are the same): a button that looked live and did
 * nothing would be worse than one that is visibly waiting, and hiding it would hide the shape of the flow.
 * Its `onClick` is already wired to the export destination's callback, so spec task 4.2 has to delete one
 * `enabled = false` rather than go looking for where the flow was supposed to start.
 */
@Composable
internal fun ExportSheetDialog(
    sheet: ExportSheet,
    choices: List<CanvasSpec>,
    onSelectResolution: (CanvasSpec) -> Unit,
    onDismiss: () -> Unit,
    onStartExport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SHEET_ROW_GAP)) {
                Text(text = "Resolution", style = MaterialTheme.typography.labelLarge)
                choices.forEach { choice ->
                    ResolutionRow(
                        choice = choice,
                        selected = choice == sheet.resolution,
                        onSelect = { onSelectResolution(choice) },
                    )
                }
                Text(text = START_IS_INERT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onStartExport, enabled = false) { Text("Start export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One size, as a radio row.
 *
 * The ROW is the touch target ([selectable] carries both the click and the semantics), so the radio draws
 * the state and takes no click of its own: a 48 dp row is easier to hit than a 20 dp circle, and a radio
 * that is also its own target inside a clickable row is two targets for one choice.
 */
@Composable
private fun ResolutionRow(choice: CanvasSpec, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = choice.exportLabel(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "720p · 720×1280": the name FR-5.1 uses, then the frame it means.
 *
 * The name comes from the SHORT side, which is why it is computed rather than read off [CanvasSpec.height]:
 * a portrait 1080p frame is 1080x1920, and a label reading "1920p" would name it after the wrong axis. The
 * frame's own numbers follow, because "1080p" alone cannot tell the user the export will be portrait.
 */
private fun CanvasSpec.exportLabel(): String {
    val shortSide = minOf(width, height)
    return "${shortSide}p · $width×$height"
}

/** The gap between the sheet's rows. */
private val SHEET_ROW_GAP = 4.dp

/**
 * Why the sheet's only action does nothing yet.
 *
 * Said in the sheet rather than left to be discovered by tapping: the encoder is spec task 4.2, and a
 * disabled button with no explanation is a button the user reads as broken.
 */
private const val START_IS_INERT =
    "The export itself is spec task 4.2, so this button is waiting on it."
