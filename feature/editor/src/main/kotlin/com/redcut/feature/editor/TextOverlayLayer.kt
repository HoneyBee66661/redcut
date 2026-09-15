package com.redcut.feature.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.TextAlignment
import com.redcut.domain.document.TextOverlayBox
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import com.redcut.domain.document.textOverlaysAt
import com.redcut.domain.document.toOverlayBox
import com.redcut.feature.editor.timeline.nextPointer

/**
 * The captions on the preview (FR-4.3): drawn where they sit, and draggable to somewhere else.
 *
 * ### What is drawn, and when
 *
 * The captions whose range covers the playhead, in stack order — the list is the DOCUMENT's answer
 * ([com.redcut.domain.document.textOverlaysAt]) rather than this file's, so the preview and any later
 * reader of "what is on screen at this moment" ask one question. A caption outside its range is not drawn
 * at all; the user moves the playhead to it.
 *
 * ### Why the text goes through the native canvas
 *
 * [DrawScope] has no text primitive. The timeline's audio label established the way round that (WS D3):
 * a platform [Paint] and `nativeCanvas.drawText`, which is also where the sp-to-px conversion and the
 * alignment have to happen anyway. The paint is REMEMBERED and mutated per caption rather than built per
 * caption per frame, and the same instance is handed to the drag so the two readers of "how big is this
 * text" cannot disagree.
 *
 * ### Why there is a drop shadow and no background box
 *
 * A caption is drawn on the user's footage, not on the app's surface: a fixed light fill over a dark
 * shadow is what keeps it legible over a bright frame without inventing a background colour the model
 * does not carry. FR-4.3 lists "background" among its Musts, and the field for it belongs to the
 * inspector's card (J-2) along with the font, stroke and spacing controls that share its panel — an
 * additive `TextSpec` field with a default, not a guess made here. The shadow is a paint call and no
 * model change at all.
 *
 * ### Where the coordinates come from
 *
 * Canvas fractions, mapped onto the preview's own box — the same convention [ViewportOverlay] uses for the
 * crop rect, and for the same reason: the stage is a `SurfaceView` the renderer draws, so the composables
 * over it are an overlay on the frame rather than the frame itself. A caption's box agreeing with the
 * export's pixel geometry is §12.3's business (the preview/export parity harness), not this layer's.
 *
 * ### The gesture, and who loses
 *
 * The press is hit-tested against each caption's own text bounds, grown by a touch target. A press that
 * misses every caption is NOT consumed, which is what leaves the crop gesture ([viewportGestures]) the
 * rest of the preview: this layer sits over it as a child, so it sees the press first, and consuming
 * everything would take the viewport away from every part of the frame a caption is not on.
 *
 * The four moments go through [EditorIntent.TextGesture] to the same [GestureSession] the trim and slider
 * drags use — the preview follows the finger as a PREVIEW and the lift records ONE history entry (§7.3).
 * Building the command per frame here instead would put thirty "Move text" entries behind one drag.
 */

/** A press this far outside a caption's text still picks it up, in dp. */
private const val CAPTION_TOUCH_PAD_DP = 8f

/** The dark offset copy under every glyph, which is what keeps light text readable over bright footage. */
private const val SHADOW_COLOR = 0xB3000000.toInt()
private const val SHADOW_OFFSET_DP = 1.5f

/** The start and end readout's own colour, and the gap between it and the box it belongs to. */
private const val READOUT_COLOR = 0xE6FFFFFF.toInt()
private const val READOUT_GAP_DP = 4f
private const val READOUT_TEXT_SIZE_SP = 10f

private const val MICROS_PER_MILLI = 1_000L
private const val HALF_DIVISOR = 2f

/**
 * The caption layer: what the user sees of FR-4.3 on the preview, and where they grab it.
 *
 * Takes the whole [EditorUiState] rather than a caption list, because it needs two things from it that
 * have to agree — the document's captions and the playhead they are tested against — and two parameters
 * read at two moments is how the drawn set and the draggable set come to differ.
 */
@Composable
internal fun TextOverlayLayer(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val captions = state.document.textOverlaysAt(state.playheadUs)
    // ONE paint, remembered: the draw mutates its size and colour per caption, an allocation per caption
    // per frame is churn in the frame path, and the drag measures with the same object.
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Box(modifier = modifier.captionDrag(captions = captions, paint = paint, onIntent = onIntent)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            captions.forEach { caption -> drawCaption(caption, paint) }
        }
    }
}

/**
 * The caption drag, attached over whatever it is given.
 *
 * ### Why the detector is keyed on the paint and reads everything else through [rememberUpdatedState]
 *
 * A gesture that changes its key mid-flight is CANCELLED. A caption drag previews the document on every
 * frame, so the caption list it was built from changes on every frame too — keying on it would kill the
 * detector after one pixel of movement, which is the same trap the timeline's detectors document (they are
 * keyed on the ruler's height and read the rest live). The paint is the one thing here a gesture cannot
 * change.
 */
@Composable
private fun Modifier.captionDrag(
    captions: List<AppliedEffect.Text>,
    paint: Paint,
    onIntent: (EditorIntent) -> Unit,
): Modifier {
    val currentCaptions by rememberUpdatedState(captions)
    val currentIntent by rememberUpdatedState(onIntent)
    return pointerInput(paint) {
        awaitEachGesture {
            captionDragGesture(
                captions = currentCaptions,
                paint = paint,
                onIntent = currentIntent,
            )
        }
    }
}

/**
 * The press-drag-lift of a caption, or nothing at all when the press missed.
 *
 * The shape is [com.redcut.feature.editor.timeline.trimGesture]'s, deliberately: a press that does not
 * move past the platform's touch slop is NOT a drag, so it rolls the preview back rather than committing a
 * "Move text" entry that changed nothing — a tap on a caption is not an edit.
 *
 * The caption the press landed on is looked up at that moment and its box read THEN: that box is the
 * anchor the drag moves away from, and re-reading it per frame would make the caption chase its own
 * preview.
 */
private suspend fun AwaitPointerEventScope.captionDragGesture(
    captions: List<AppliedEffect.Text>,
    paint: Paint,
    onIntent: (EditorIntent) -> Unit,
) {
    if (size.width <= 0 || size.height <= 0) return
    val down = awaitFirstDown(requireUnconsumed = false)
    val padPx = CAPTION_TOUCH_PAD_DP.dp.toPx()
    val target = captions.firstOrNull { caption ->
        captionTextBoundsPx(
            spec = caption.spec,
            box = caption.transform.toOverlayBox(),
            frameWidthPx = size.width.toFloat(),
            frameHeightPx = size.height.toFloat(),
            paint = paint,
        ).inflate(padPx).contains(down.position)
    }
    // Not on a caption: the press belongs to whatever is underneath, and consuming it here would take the
    // viewport's own gesture away from the rest of the preview.
    if (target == null) return
    val anchor = target.transform.toOverlayBox()

    down.consume()
    onIntent(EditorIntent.BeginTextDrag(target.id))

    var moved = false
    var pointer = nextPointer(down)
    while (pointer != null && pointer.pressed) {
        pointer.consume()
        val travelled = (pointer.position - down.position).getDistance()
        if (!moved && travelled > viewConfiguration.touchSlop) {
            moved = true
        }
        if (moved) {
            // The position is the grab point plus the finger's own travel, in canvas fractions: an
            // absolute answer rather than a delta, so a dropped frame cannot accumulate into drift.
            onIntent(
                EditorIntent.UpdateTextDrag(
                    centerX = anchor.centerX + (pointer.position.x - down.position.x) / size.width,
                    centerY = anchor.centerY + (pointer.position.y - down.position.y) / size.height,
                ),
            )
        }
        pointer = nextPointer(down)
    }

    if (moved) {
        onIntent(EditorIntent.EndTextDrag)
    } else {
        onIntent(EditorIntent.CancelTextDrag)
    }
}

/**
 * Draws one caption: its words, and the start and end they are visible for.
 *
 * The readout is FR-4.3's "start/end time" made visible at the moment the user is working on the caption —
 * it follows the box, so a caption dragged to the bottom of the frame carries its timing with it. The
 * editable pair of fields is the inspector's card; what this owes the requirement is that the range is
 * readable while the caption is on screen, and it is clamped so it cannot fall off the bottom.
 */
private fun DrawScope.drawCaption(caption: AppliedEffect.Text, paint: Paint) {
    val bounds = captionTextBoundsPx(
        spec = caption.spec,
        box = caption.transform.toOverlayBox(),
        frameWidthPx = size.width,
        frameHeightPx = size.height,
        paint = paint,
    )
    // `ascent` and `descent` rather than a `FontMetrics` object: this runs per caption per frame, and the
    // metrics accessor that returns an object would allocate one each time.
    val baseline = bounds.center.y - (paint.ascent() + paint.descent()) / HALF_DIVISOR
    drawShadowedText(caption.spec.content, bounds.left, baseline, paint, caption.spec.colorArgb)

    paint.textSize = READOUT_TEXT_SIZE_SP.sp.toPx()
    val gapPx = READOUT_GAP_DP.dp.toPx()
    val readoutBaseline = (bounds.bottom + gapPx - paint.ascent())
        .coerceAtMost(size.height - gapPx)
    drawShadowedText(
        text = captionRangeLabel(caption.timeRange),
        x = bounds.left,
        y = readoutBaseline,
        paint = paint,
        color = READOUT_COLOR,
    )
}

/** "0 ms – 3000 ms", the caption's own range. Milliseconds because that is what the transport shows. */
private fun captionRangeLabel(range: TimeRange): String =
    "${range.startUs / MICROS_PER_MILLI} ms – ${range.endUs / MICROS_PER_MILLI} ms"

/**
 * [text] at ([x], [y]) with a dark copy behind it, so it stays readable over whatever the frame holds.
 *
 * The shadow is drawn first and in the same pass as the fill rather than as a `Paint.setShadowLayer`,
 * which needs a software layer to draw at all — a text drawn twice is unconditionally visible.
 */
private fun DrawScope.drawShadowedText(
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
    color: Int,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val offsetPx = SHADOW_OFFSET_DP.dp.toPx()
    paint.color = SHADOW_COLOR
    canvas.drawText(text, x + offsetPx, y + offsetPx, paint)
    paint.color = color
    canvas.drawText(text, x, y, paint)
}

/**
 * Where a caption's text lands in pixels — the ONE measurement the draw and the hit test share.
 *
 * A `Density` extension so both callers can use it as they stand: [DrawScope] and [AwaitPointerEventScope]
 * are each already a `Density`, and the sp-to-px conversion is the reason one is needed at all.
 *
 * Vertical placement is the box's centre and horizontal placement is [TextSpec.alignment] against the
 * box's edges — which is what makes the alignment field something the user can see, and what the box is
 * for (see [TextOverlayBox]). The text is NOT wrapped or scaled to the box: `fontSizeSp` is the size
 * FR-4.3 names, so a long line overflows its box rather than shrinking, and the resize gesture that would
 * change that belongs to the card that adds it.
 */
internal fun Density.captionTextBoundsPx(
    spec: TextSpec,
    box: TextOverlayBox,
    frameWidthPx: Float,
    frameHeightPx: Float,
    paint: Paint,
): Rect {
    if (frameWidthPx <= 0f || frameHeightPx <= 0f) return Rect.Zero
    paint.textSize = spec.fontSizeSp.sp.toPx()
    val textWidth = paint.measureText(spec.content)
    val textHeight = paint.descent() - paint.ascent()
    val boxLeftPx = box.left * frameWidthPx
    val boxWidthPx = box.width * frameWidthPx
    val left = when (spec.alignment) {
        TextAlignment.START -> boxLeftPx
        TextAlignment.CENTER -> boxLeftPx + (boxWidthPx - textWidth) / HALF_DIVISOR
        TextAlignment.END -> boxLeftPx + boxWidthPx - textWidth
    }
    val top = box.centerY * frameHeightPx - textHeight / HALF_DIVISOR
    return Rect(left = left, top = top, right = left + textWidth, bottom = top + textHeight)
}

/**
 * The Effect stage's tools, which so far are the caption's (FR-4.3, spec task 3.6).
 *
 * One button, and it ADDS rather than edits: the caption it adds lands at the playhead in the model's own
 * caption band, and everything a user would then change about it — the words, the font, the size, the
 * colour — is the inspector's card. What this screen owes the requirement is the way IN to the feature:
 * without it there is no caption to drag, and the preview path would be unreachable code.
 *
 * Disabled on a document with no clips, the rule the Export button already follows ("present rather than
 * hidden, so the button does not appear the moment the user does the thing it needs"): a caption over
 * nothing renders nowhere, so [applyAddTextOverlay] refuses the same case, and the two readers of one
 * rule are named in its KDoc. There is no reason line under the row for the same reason Export has none —
 * a half-built stage's disabled button is not a state the user has to be talked out of.
 *
 * Living here keeps `EditorLayout` under detekt's function limit, and keeps the caption's UI beside the
 * caption's preview layer.
 */
@Composable
internal fun TextTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onIntent(EditorIntent.AddText) },
            enabled = state.document.clips.isNotEmpty(),
        ) {
            Text("Add text", style = MaterialTheme.typography.labelLarge)
        }
    }
}
