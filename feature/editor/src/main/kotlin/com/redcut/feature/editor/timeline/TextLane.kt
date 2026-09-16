package com.redcut.feature.editor.timeline

import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.domain.document.ClipEdge
import com.redcut.feature.editor.EditorIntent
import com.redcut.feature.editor.TimelineMarks

/**
 * The caption's lane on the timeline (FR-4.3's card 4): a band under the tracks, one item per caption,
 * its EDGES draggable and its body tappable.
 *
 * ### Why the lane is a reading, not a track
 *
 * A caption IS on its own track in the user's model — the card's words — and the timeline draws that as
 * this band: one text lane below the media lanes, in the position a TEXT_OVERLAY track occupies in the
 * stacking order (over the picture, which is what "drawn over the picture" means spatially too). The
 * document does not store a [com.redcut.domain.document.Track] for it yet: captions are effects with
 * absolute ranges ([com.redcut.domain.document.SetTextRange]), not placed items, so the lane is a
 * projection of the effect stack — [com.redcut.feature.editor.textLaneItems] — rather than geometry over
 * a track's contents. Promoting captions to real track items is a schema conversation this card does not
 * have to have; the lane is what the user sees either way, and it is drawn and hit-tested against the
 * same geometry the other lanes use.
 *
 * ### The gestures, and who owns the band's finger
 *
 * An EDGE press opens a trim ([textTrimGesture], the timeline's own trim lifecycle): the edge follows the
 * finger as a preview and the lift records ONE "Text timing" entry. A press on a BODY is not consumed
 * here — it falls through to the tap detector, which selects the caption (`SelectTextOverlay`), and to
 * the pan gesture, which scrolls. Two gestures, split by zone, the same
 * arbitration the clips' edges and bodies use.
 */

/** How wide a caption's draggable edge zone is, in dp — the clips' own touch floor, restated. */
private const val TEXT_EDGE_ZONE_DP = 24f

/** The band label's line, like the audio lane's: size in sp, baseline in dp inside the band. */
private const val TEXT_LABEL_TEXT_SIZE_SP = 10f
private const val TEXT_LABEL_INSET_DP = 8f
private const val TEXT_LABEL_BASELINE_DP = 16f

/** The item's label inset, and the padding the rounded body keeps from the band's edges. */
private const val TEXT_ITEM_INSET_DP = 8f
private const val TEXT_ITEM_TOP_DP = 20f
private const val TEXT_ITEM_BOTTOM_DP = 8f
private const val TEXT_ITEM_CORNER_DP = 6f

/** The band a lane occupies: its top in LANE space (the track area under the ruler), height, items. */
internal data class TextLane(
    val topPx: Float,
    val heightPx: Float,
    val items: List<TextLaneItem>,
) {
    val bottomPx: Float get() = topPx + heightPx
}

/** One caption as the lane draws and hit-tests it: its range, and the words to label it with. */
internal data class TextLaneItem(
    val effectId: String,
    val content: String,
    val startUs: Long,
    val endUs: Long,
) {
    val durationUs: Long get() = endUs - startUs
}

/** The edge drag's four callbacks, grouped for the reason [TrimGestures] is. */
internal class TextTrimGestures(
    val begin: (effectId: String, edge: ClipEdge, us: Long) -> Unit,
    val update: (us: Long) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * Draws the text lane: the band, then each caption's body, its label, and the marks of an open drag.
 *
 * Under the media lanes, always — a caption drawn ABOVE the tracks would read as a track header, and the
 * stacking order of the render is what the lane order mirrors. The band is the timeline's structural grey
 * (a lane is furniture, the captions are the content), and the bodies are rounded like the clips so the
 * strip reads as one family of objects.
 */
internal fun DrawScope.drawTextLane(
    lane: TextLane,
    geometry: TimelineGeometry,
    paint: TimelinePaint,
    marks: TimelineMarks,
) {
    // The bands live in LANE space (0 = the top of the track area, under the ruler), and the draw adds
    // the ruler back — the same subtraction the gesture side keeps, and the same one drawTimeline adds
    // when it places a band. Without it the lane would paint over the ruler's strip.
    val bandTop = RULER_HEIGHT_DP.dp.toPx() + lane.topPx
    val bandBottom = bandTop + lane.heightPx
    drawRect(
        color = paint.ruler,
        topLeft = Offset(0f, bandTop),
        size = Size(size.width, lane.heightPx),
    )
    val label = Paint().apply {
        isAntiAlias = true
        textSize = TEXT_LABEL_TEXT_SIZE_SP.sp.toPx()
        color = paint.onRuler.toArgb()
    }
    drawContext.canvas.nativeCanvas.drawText(
        TEXT_LANE_LABEL,
        TEXT_LABEL_INSET_DP.dp.toPx(),
        bandTop + TEXT_LABEL_BASELINE_DP.dp.toPx(),
        label,
    )
    lane.items.forEach { item ->
        val left = geometry.pxFor(item.startUs) - geometry.visibleStartPx
        val right = geometry.pxFor(item.endUs) - geometry.visibleStartPx
        if (right <= 0f || left >= size.width) return@forEach
        drawTextItem(
            item = item,
            left = left,
            right = right,
            lane = lane,
            paint = paint,
            marks = marks,
        )
    }
}

/**
 * One caption body: a rounded band with its words, the selection outline, and the edge being dragged.
 *
 * The body is inset from the band's top so the lane's own label has a line to live on — the same pairing
 * the audio body uses, for the same reason: the label is what proves the lane is there.
 */
private fun DrawScope.drawTextItem(
    item: TextLaneItem,
    left: Float,
    right: Float,
    lane: TextLane,
    paint: TimelinePaint,
    marks: TimelineMarks,
) {
    val bandTop = RULER_HEIGHT_DP.dp.toPx() + lane.topPx
    val bandBottom = bandTop + lane.heightPx
    val top = bandTop + TEXT_ITEM_TOP_DP.dp.toPx()
    val bottom = bandBottom - TEXT_ITEM_BOTTOM_DP.dp.toPx()
    val rect = Rect(
        left = left + TEXT_ITEM_INSET_DP.dp.toPx(),
        top = top,
        right = right - TEXT_ITEM_INSET_DP.dp.toPx(),
        bottom = bottom,
    )
    if (rect.width <= 0f) return
    val selected = marks.selectedTextId == item.effectId
    drawPath(
        path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = rect,
                    cornerRadius = CornerRadius(
                        TEXT_ITEM_CORNER_DP.dp.toPx(),
                        TEXT_ITEM_CORNER_DP.dp.toPx(),
                    ),
                ),
            )
        },
        color = if (selected) paint.textItemSelected else paint.textItem,
    )
    val label = Paint().apply {
        isAntiAlias = true
        textSize = TEXT_LABEL_TEXT_SIZE_SP.sp.toPx()
        color = paint.onRuler.toArgb()
    }
    drawContext.canvas.nativeCanvas.drawText(
        item.content,
        rect.left + TEXT_ITEM_INSET_DP.dp.toPx(),
        top + TEXT_LABEL_BASELINE_DP.dp.toPx(),
        label,
    )
    marks.draggedTextEdge?.takeIf { marks.textTrimmedId == item.effectId }?.let { edge ->
        val x = if (edge == ClipEdge.IN) rect.left else rect.right
        drawLine(
            color = paint.trimEdge,
            start = Offset(x, top),
            end = Offset(x, bottom),
            strokeWidth = TRIM_EDGE_PX,
        )
    }
}

/**
 * The text lane's edge drag: a press in an edge zone of a caption body opens a trim.
 *
 * The shape is [trimGesture]'s and the two endings are the same two: past the touch slop, `end()` commits
 * ONE entry; short of it, `cancel()` rolls back and the press falls through to the tap behaviour — which
 * here means the tap detector selects the caption, because a tap on a body is a selection and not an
 * edit. Presses that miss every edge zone return WITHOUT consuming, which is what leaves the body to the
 * tap and the pan underneath.
 *
 * [onBodyTap] is the band's own answer to a body press: the tap detector routes it here so the band can
 * select a caption instead of clearing the selection the way track-less space does.
 */
internal fun onTextLaneTap(
    screenX: Float,
    lane: TextLane,
    geometry: TimelineGeometry,
    onIntent: (EditorIntent) -> Unit,
    selectedEffectId: String?,
) {
    val us = geometry.usFor(geometry.contentPxFor(screenX))
    val hit = lane.items.firstOrNull { it.startUs <= us && us < it.endUs }
    when {
        hit == null -> onIntent(EditorIntent.ClearSelection)
        hit.effectId == selectedEffectId -> onIntent(EditorIntent.ClearSelection)
        else -> onIntent(EditorIntent.SelectTextOverlay(hit.effectId))
    }
}

/**
 * The edge press: [screenX]/[screenY] resolve to one caption's IN or OUT zone, or to nothing.
 *
 * A separate function from the gesture below so the HIT has a test — the zone arithmetic (a third of the
 * body, capped) is the rule a tap and a drag disagree over, and it is arithmetic, which is what this
 * package puts tests on.
 */
internal fun Density.textLaneEdgeHit(
    screenX: Float,
    screenY: Float,
    lane: TextLane,
    geometry: TimelineGeometry,
): Pair<TextLaneItem, ClipEdge>? {
    if (screenY < lane.topPx || screenY > lane.bottomPx) return null
    val zonePx = TEXT_EDGE_ZONE_DP.dp.toPx()
    return lane.items.firstNotNullOfOrNull { item ->
        val left = geometry.pxFor(item.startUs) - geometry.visibleStartPx
        val right = geometry.pxFor(item.endUs) - geometry.visibleStartPx
        val zone = minOf((right - left) / 3f, zonePx)
        when {
            screenX >= left && screenX <= left + zone -> item to ClipEdge.IN
            screenX <= right && screenX >= right - zone -> item to ClipEdge.OUT
            else -> null
        }
    }
}

/**
 * The press-drag-lift on an edge. See the KDoc above for the two endings.
 *
 * Keyed like the other detectors on things a gesture cannot change; the live geometry and lane arrive
 * through [rememberUpdatedState] at the call site.
 */
internal suspend fun AwaitPointerEventScope.textTrimGesture(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    lane: TextLane,
    actions: TextTrimGestures,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    // Lane space: the bands are measured from the top of the TRACK AREA, so the ruler strip comes off the
    // pointer's y — the same subtraction trimGesture adds before hit-testing the clips.
    val hit = textLaneEdgeHit(
        screenX = down.position.x,
        screenY = down.position.y - rulerHeightPx,
        lane = lane,
        geometry = geometry,
    ) ?: return
    val us = geometry.usFor(geometry.contentPxFor(down.position.x))

    down.consume()
    actions.begin(hit.first.effectId, hit.second, us)

    var moved = false
    var pointer = nextPointer(down)
    while (pointer != null && pointer.pressed) {
        pointer.consume()
        val travelled = (pointer.position - down.position).getDistance()
        if (!moved && travelled > viewConfiguration.touchSlop) {
            moved = true
        }
        if (moved) {
            actions.update(geometry.usFor(geometry.contentPxFor(pointer.position.x)))
        }
        pointer = nextPointer(down)
    }

    if (moved) {
        actions.end()
    } else {
        actions.cancel()
    }
}

private const val TEXT_LANE_LABEL = "TEXT"
