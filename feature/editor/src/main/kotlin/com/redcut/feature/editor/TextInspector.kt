package com.redcut.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.redcut.domain.document.TextAlignment
import com.redcut.domain.document.TextFontFace
import com.redcut.domain.document.TextOverlayBox
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.textOverlayById
import kotlin.math.roundToInt

/**
 * The caption's inspector rows (FR-4.3, card 2 of 4): font, size, colour, stroke, background,
 * alignment.
 *
 * The rows appear on the Effect stage when a caption is SELECTED — by tapping the caption on the preview
 * or on its timeline lane — and they are the same shape as the Edit stage's [Inspector] rows: a slider
 * drag is `BeginTextStyle` → `UpdateTextStyle` per frame → `EndTextStyle` (one undo entry per drag, the
 * adjust-gesture lifecycle), and a tap control is the same three intents fired in one tap, which is why
 * the swatches and segments need no gesture code of their own.
 *
 * Every row writes the caption's WHOLE [TextSpec]: it copies the spec the document holds and changes its
 * own field, so the command the gesture produces is the whole-value `SetTextStyle` — one writer, no field
 * of the caption editable from two vocabularies.
 *
 * The colour rows are swatch rows rather than a colour wheel, and that is the MVP the card asks for: a
 * closed set of argb values the caption model can hold exactly. A custom picker would need a field the
 * spec does not constrain and a wheel this tier cannot test; the swatches keep every choice one the
 * document stores verbatim.
 */
@Composable
internal fun TextInspector(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val effectId = (state.selection as? Selection.Text)?.effectId
    val caption = effectId?.let { state.document.textOverlayById(it) }
    if (caption == null) {
        Text(
            text = "Tap a caption on the preview or the timeline to change its words' style.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val spec = caption.spec

    TextSizeRow(spec = spec, effectId = caption.id, state = state, onIntent = onIntent)
    ColourRow(
        label = "Colour",
        current = spec.colorArgb,
        onSelect = { argb -> spec.copy(colorArgb = argb) },
        effectId = caption.id,
        onIntent = onIntent,
    )
    StrokeWidthRow(spec = spec, effectId = caption.id, state = state, onIntent = onIntent)
    ColourRow(
        label = "Stroke colour",
        current = spec.strokeColorArgb,
        onSelect = { argb -> spec.copy(strokeColorArgb = argb) },
        effectId = caption.id,
        onIntent = onIntent,
    )
    BackgroundRow(spec = spec, effectId = caption.id, onIntent = onIntent)
    AlignmentRow(spec = spec, effectId = caption.id, onIntent = onIntent)
    FontRow(spec = spec, effectId = caption.id, onIntent = onIntent)
}

/**
 * The size slider, live — the [SliderRow] shape for a caption.
 *
 * The begin-once check reads [ToolState.StylingText]: firing `BeginTextStyle` per frame would re-publish
 * the state for nothing, and the check also handles a finger that slides from one row onto another.
 */
@Composable
private fun TextSizeRow(
    spec: TextSpec,
    effectId: String,
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val open = state.tool as? ToolState.StylingText
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Size · ${spec.fontSizeSp.roundToInt()} sp",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = spec.fontSizeSp,
            valueRange = SIZE_RANGE,
            onValueChange = { size ->
                if (open?.effectId != effectId) {
                    onIntent(EditorIntent.BeginTextStyle(effectId, spec))
                }
                onIntent(EditorIntent.UpdateTextStyle(spec.copy(fontSizeSp = size)))
            },
            onValueChangeFinished = { onIntent(EditorIntent.EndTextStyle) },
        )
    }
}

/**
 * One colour swatch row: the label, then the swatch set, the current one ringed.
 *
 * A tap is a whole gesture (begin, the new spec, end) fired in one click — the [SwitchRow] pattern, which
 * is why this needs no gesture state of its own.
 */
@Composable
private fun ColourRow(
    label: String,
    current: Int,
    onSelect: (Int) -> TextSpec,
    effectId: String,
    onIntent: (EditorIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SWATCH_ARGBS.forEach { argb ->
                val selected = argb == current
                Box(
                    modifier = Modifier
                        .size(SWATCH_SIZE)
                        .clip(CircleShape)
                        .background(color = Color(argb))
                        .border(
                            width = if (selected) SWATCH_BORDER_SELECTED else SWATCH_BORDER,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        )
                        .clickable {
                            val next = onSelect(argb)
                            onIntent(EditorIntent.BeginTextStyle(effectId, next))
                            onIntent(EditorIntent.UpdateTextStyle(next))
                            onIntent(EditorIntent.EndTextStyle)
                        },
                )
            }
        }
    }
}

/**
 * The stroke row: a width slider where 0 means no outline, above its own colour row.
 *
 * 0 is drawn as the readout "off" rather than "0 sp", because "no stroke" is a state the user chooses,
 * not a measurement of nothing.
 */
@Composable
private fun StrokeWidthRow(
    spec: TextSpec,
    effectId: String,
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val open = state.tool as? ToolState.StylingText
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val readout = if (spec.strokeWidthSp > 0f) {
            "${spec.strokeWidthSp.roundToInt()} sp"
        } else {
            "off"
        }
        Text(text = "Stroke · $readout", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = spec.strokeWidthSp,
            valueRange = STROKE_RANGE,
            onValueChange = { width ->
                if (open?.effectId != effectId) {
                    onIntent(EditorIntent.BeginTextStyle(effectId, spec))
                }
                onIntent(EditorIntent.UpdateTextStyle(spec.copy(strokeWidthSp = width)))
            },
            onValueChangeFinished = { onIntent(EditorIntent.EndTextStyle) },
        )
    }
}

/**
 * The background row: a switch, and the swatch row for its colour once it is on.
 *
 * Off is `null` on the spec rather than a transparent colour, for the reason the field's KDoc gives: the
 * absence of a band is a different thing from a band the footage shows through, and the null is what an
 * old caption decodes to. The switch and the swatches are two rows, so turning the band on keeps the
 * colour it already had rather than resetting it — the same value a drag would have previewed.
 */
@Composable
private fun BackgroundRow(spec: TextSpec, effectId: String, onIntent: (EditorIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Background",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(
            checked = spec.backgroundArgb != null,
            onCheckedChange = { on ->
                val next = if (on) {
                    spec.copy(backgroundArgb = DEFAULT_BACKGROUND_ARGB)
                } else {
                    spec.copy(backgroundArgb = null)
                }
                onIntent(EditorIntent.BeginTextStyle(effectId, next))
                onIntent(EditorIntent.UpdateTextStyle(next))
                onIntent(EditorIntent.EndTextStyle)
            },
        )
    }
    if (spec.backgroundArgb != null) {
        ColourRow(
            label = "Background colour",
            current = spec.backgroundArgb ?: 0,
            onSelect = { argb -> spec.copy(backgroundArgb = argb) },
            effectId = effectId,
            onIntent = onIntent,
        )
    }
}

/**
 * The alignment row: START / CENTER / END against the caption's box, the segmented control the canvas-fit
 * row uses.
 *
 * What it aligns AGAINST is [TextOverlayBox] — the box is the thing that makes the field visible — and
 * the preview draws each value differently, so this row is not decoration the export ignores.
 */
@Composable
private fun AlignmentRow(spec: TextSpec, effectId: String, onIntent: (EditorIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text = "Alignment", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextAlignment.entries.forEach { alignment ->
                val selected = alignment == spec.alignment
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(SEGMENTED_SHAPE)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .border(
                            width = if (selected) SEGMENTED_BORDER_WIDTH else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = SEGMENTED_SHAPE,
                        )
                        .clickable(enabled = !selected) {
                            val next = spec.copy(alignment = alignment)
                            onIntent(EditorIntent.BeginTextStyle(effectId, next))
                            onIntent(EditorIntent.UpdateTextStyle(next))
                            onIntent(EditorIntent.EndTextStyle)
                        }
                        .padding(vertical = SEGMENTED_PADDING_V),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = alignment.label(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * The font row: the bundled faces as one segmented set (FR-4.3's "font", MVP).
 *
 * The set is the domain's [TextFontFace], read from the model rather than restated here, so a face the
 * schema gains later appears in this row and nowhere else has to learn about it.
 */
@Composable
private fun FontRow(spec: TextSpec, effectId: String, onIntent: (EditorIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text = "Font", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextFontFace.entries.forEach { face ->
                val selected = face == spec.font
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(SEGMENTED_SHAPE)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .border(
                            width = if (selected) SEGMENTED_BORDER_WIDTH else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = SEGMENTED_SHAPE,
                        )
                        .clickable(enabled = !selected) {
                            val next = spec.copy(font = face)
                            onIntent(EditorIntent.BeginTextStyle(effectId, next))
                            onIntent(EditorIntent.UpdateTextStyle(next))
                            onIntent(EditorIntent.EndTextStyle)
                        }
                        .padding(vertical = SEGMENTED_PADDING_V),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = face.label(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun TextFontFace.label(): String = when (this) {
    TextFontFace.DEFAULT -> "Default"
    TextFontFace.SERIF -> "Serif"
    TextFontFace.MONOSPACE -> "Mono"
}

private fun TextAlignment.label(): String = when (this) {
    TextAlignment.START -> "Start"
    TextAlignment.CENTER -> "Center"
    TextAlignment.END -> "End"
}

/** The size slider's span: from a caption nobody can read to one that fills a lower third. */
private val SIZE_RANGE = 12f..96f

/** The stroke slider's span. 0 is "off", which the readout above renders as the word. */
private val STROKE_RANGE = 0f..16f

/**
 * The swatch set: white, black, yellow, red, blue, green — the colours a caption's words are asked for
 * first. Argb literals rather than theme colours, because these are the words' INK over the user's
 * footage, not the app's furniture.
 */
private val SWATCH_ARGBS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFFFEB3B.toInt(),
    0xFFF44336.toInt(),
    0xFF2196F3.toInt(),
    0xFF4CAF50.toInt(),
)

private val SWATCH_SIZE = 28.dp
private val SWATCH_BORDER = 1.dp
private val SWATCH_BORDER_SELECTED = 2.dp

/**
 * The band a switched-on background starts with: dark and translucent, so the words stay readable over
 * whatever the frame holds without fully covering it. The value the preview draws and the export burns —
 * it is a spec value, not a theme colour, for the reason the swatch set is.
 */
private const val DEFAULT_BACKGROUND_ARGB = 0x99000000

private val SEGMENTED_SHAPE = RoundedCornerShape(8.dp)
private val SEGMENTED_BORDER_WIDTH = 1.dp
private val SEGMENTED_PADDING_V = 6.dp
