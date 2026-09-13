package com.redcut.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.redcut.domain.document.ClipAdjustment
import com.redcut.domain.document.currentValueOf
import kotlin.math.roundToInt

/**
 * The Edit stage's inspector (task 2.1, FR-3.1–3.4, 3.9).
 *
 * A list of rows, one per [ClipAdjustment], each drawn from the SELECTED clip and each writing back
 * through the same mapping it reads from (`currentValueOf` / `adjust`), so a row cannot show a value the
 * document would refuse.
 *
 * ### One row per control, over the same two intents as every other gesture
 *
 * A slider drag is `BeginAdjust` → `UpdateAdjust` per frame → `EndAdjust`, which is the trim's shape and
 * exists for the same reason: the document follows the finger live, and the whole drag lands on the
 * history as ONE entry. A switch is the same three intents fired in one tap — which is why mute and
 * reverse needed no gesture code of their own.
 *
 * `BeginAdjust` is fired at most once per drag (the [ToolState.Adjusting] check below): firing it every
 * frame would re-publish the state for nothing, and the check also handles the case of a finger that
 * slides from one row onto another.
 */
@Composable
internal fun Inspector(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val clipId = (state.selection as? Selection.Clip)?.clipId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        if (clipId == null) {
            // Not a disabled row per control: with nothing selected there is no VALUE to show, and five
            // greyed sliders would be five wrong answers rather than one clear instruction.
            Text(
                text = "Select a clip to change its speed, volume, fades or direction.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        ClipAdjustment.entries.forEach { adjustment ->
            val value = state.document.currentValueOf(clipId, adjustment) ?: return@forEach
            AdjustmentRow(
                adjustment = adjustment,
                clipId = clipId,
                value = value,
                openTool = state.tool,
                onIntent = onIntent,
            )
        }
    }
}

/**
 * One control, as a slider or a switch — [ClipAdjustment.isSwitch] decides which, because that is a
 * property of the control rather than of this layout.
 */
@Composable
private fun AdjustmentRow(
    adjustment: ClipAdjustment,
    clipId: String,
    value: Float,
    openTool: ToolState,
    onIntent: (EditorIntent) -> Unit,
) {
    if (adjustment.isSwitch) {
        SwitchRow(adjustment, clipId, value, onIntent)
    } else {
        SliderRow(adjustment, clipId, value, openTool, onIntent)
    }
}

@Composable
private fun SwitchRow(
    adjustment: ClipAdjustment,
    clipId: String,
    value: Float,
    onIntent: (EditorIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = adjustment.label(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(
            checked = value >= ClipAdjustment.SWITCH_THRESHOLD,
            // A tap is a whole gesture: begin, the new value, end. Three intents rather than a fourth
            // "toggle" intent, so the history gets one entry by the same path a drag does.
            onCheckedChange = { on ->
                onIntent(EditorIntent.BeginAdjust(clipId, adjustment))
                onIntent(
                    EditorIntent.UpdateAdjust(if (on) 1f else 0f),
                )
                onIntent(EditorIntent.EndAdjust)
            },
        )
    }
}

@Composable
private fun SliderRow(
    adjustment: ClipAdjustment,
    clipId: String,
    value: Float,
    openTool: ToolState,
    onIntent: (EditorIntent) -> Unit,
) {
    val open = openTool as? ToolState.Adjusting
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${adjustment.label()} · ${adjustment.readout(value)}",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = value,
            valueRange = adjustment.range,
            onValueChange = { next ->
                if (open?.clipId != clipId || open.adjustment != adjustment) {
                    onIntent(EditorIntent.BeginAdjust(clipId, adjustment))
                }
                onIntent(EditorIntent.UpdateAdjust(next))
            },
            onValueChangeFinished = { onIntent(EditorIntent.EndAdjust) },
        )
    }
}

/** A control's word. Here rather than in the domain because it is a UI word, not a domain name. */
private fun ClipAdjustment.label(): String = when (this) {
    ClipAdjustment.SPEED -> "Speed"
    ClipAdjustment.VOLUME -> "Volume"
    ClipAdjustment.FADE_IN -> "Fade in"
    ClipAdjustment.FADE_OUT -> "Fade out"
    ClipAdjustment.MUTE -> "Mute"
    ClipAdjustment.REVERSE -> "Reverse"
}

/**
 * The value as a user reads it.
 *
 * Each control has its own unit and its own sensible precision — "2.0×" and "45 %" and "400 ms" — and
 * printing a Float's raw toString here would show `0.35` for a volume and `2.0` for a speed, which are
 * the same shape for two different measures.
 */
private fun ClipAdjustment.readout(value: Float): String = when (this) {
    ClipAdjustment.SPEED -> "${roundToInt(value * HUNDRED) / HUNDRED.toFloat()}×"
    ClipAdjustment.VOLUME -> "${roundToInt(value * HUNDRED)} %"
    ClipAdjustment.FADE_IN, ClipAdjustment.FADE_OUT -> "${value.roundToInt()} ms"
    ClipAdjustment.MUTE, ClipAdjustment.REVERSE -> {
        if (value >= ClipAdjustment.SWITCH_THRESHOLD) "on" else "off"
    }
}

private const val HUNDRED = 100f
