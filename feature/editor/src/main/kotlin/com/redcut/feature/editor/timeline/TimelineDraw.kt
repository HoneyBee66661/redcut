package com.redcut.feature.editor.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.redcut.core.common.timeline.ClipRect
import com.redcut.core.common.timeline.ClipSpan
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.media.ThumbnailKey
import com.redcut.domain.document.Clip
import com.redcut.domain.document.ClipEdge
import kotlin.math.roundToInt

/**
 * The drawing primitives of the timeline.
 *
 * Split from [TimelineCanvas] by responsibility, and the split is worth explaining because it is
 * not cosmetic: the composable decides *what* to draw (state in, draw calls out) while these
 * functions know *how* a rectangle, a filmstrip slice and a playhead are painted. A file that did
 * both grew past the point where either half could be read in one go — and `detekt`'s function
 * count, which is what forced the split, is a proxy for that rather than the reason.
 *
 * Every function here takes the geometry rather than computing a position itself: the arithmetic is
 * tested in `:core:common`'s fast tier, and a draw function that did its own would put it beyond the
 * reach of any test.
 */

/** The colours the canvas draws with. Captured outside `DrawScope`, where `MaterialTheme` is legal. */
internal data class TimelinePaint(
    val clip: Color,
    val selectedClip: Color,
    val selectionBorder: Color,
    val ruler: Color,
    val playhead: Color,
    /** The edge a trim gesture is dragging (FR-2.1): brighter than the selection, because it is moving. */
    val trimEdge: Color,
    /** The slot a reorder drag would drop into (FR-2.7). */
    val reorderMarker: Color,
)

/**
 * The insertion marker for a reorder drag (FR-2.7): a bar where the clip would start.
 *
 * A bar rather than a highlight on the neighbouring clip, because the drop lands in the GAP that opens
 * up when the clip moves — and showing the gap is what makes "it will go here" unambiguous when the
 * neighbouring slots look alike.
 */
internal fun DrawScope.drawReorderMarker(screenX: Float, track: Track, color: Color) {
    drawLine(
        color = color,
        start = Offset(screenX, track.top),
        end = Offset(screenX, track.top + track.height),
        strokeWidth = REORDER_MARKER_PX,
    )
}

/** The clip track: everything below the ruler. */
internal data class Track(val top: Float, val height: Float)

/**
 * Everything the timeline derives from the document and the viewport.
 *
 * A value rather than free-floating locals, so the composable can read as "derive this, then draw it"
 * and the derivation lives in one readable pipeline: clips → spans → geometry → rects → slices →
 * images. It is also what keeps the draw call's parameter list short enough to read.
 */
internal data class TimelineLayer(
    val geometry: TimelineGeometry,
    val rects: List<ClipRect>,
    val slices: List<SliceRequest>,
    val images: Map<ThumbnailKey, ImageBitmap>,
    /**
     * The clips and their spans, carried along because the gesture layer needs the same maps the
     * filmstrip does: turning a screen position into a source time means asking the clip under the
     * finger ([Clip.sourceTimeFor]) and subtracting where its span starts.
     */
    val clipsById: Map<String, Clip>,
    val spansByClip: Map<String, ClipSpan>,
)

/**
 * What the current gesture is showing: the playhead, the edge being trimmed, the clip being dragged
 * and the slot it would land in.
 *
 * Every field but the playhead is null when its gesture is not running, which is why they are grouped
 * — five unrelated parameters trailing a draw call is how a signature stops being read.
 */
internal data class TimelineMarks(
    val playheadUs: Long,
    val selectedClipId: String? = null,
    val trimmedClipId: String? = null,
    val draggedEdge: ClipEdge? = null,
    val draggedClipId: String? = null,
    val markerUs: Long? = null,
)

/** The whole timeline, painted. The order is the layering: ruler, clips, marker, playhead. */
internal fun DrawScope.drawTimeline(
    layer: TimelineLayer,
    paint: TimelinePaint,
    marks: TimelineMarks,
) {
    val rulerHeight = RULER_HEIGHT_DP.dp.toPx()
    // FIXED height, not `size.height - rulerHeight` (UI revision 2: "tinggi track body timeline fixed, tidak
    // fitting container"). The canvas may be taller than the track; the leftover is empty lane, drawn as
    // lane — a taller screen should show more track, not a taller clip. It also stops the playhead's 3×
    // height from growing with the window until it crosses the whole screen.
    val track = Track(top = rulerHeight, height = layer.geometry.trackHeightPx)
    // ONE tick selection, drawn twice (UI revision 2, task A3): along the ruler strip, and along each clip's
    // top edge. Asked for once here rather than by each caller, because "the same ticks" is the requirement —
    // a clip's marks that came from a second call could only ever be accidentally identical.
    val ticks = layer.geometry.rulerTicks()

    drawRuler(ticks, layer.geometry, rulerHeight, paint.ruler)
    layer.rects.forEach { rect ->
        drawClip(
            rect = rect,
            slices = layer.slices.filter { it.clipId == rect.clipId },
            images = layer.images,
            // The clip under the finger is outlined like a selected one: during a drag the user needs
            // to know which clip they picked up, and the drag has not selected it yet.
            selected = marks.selectedClipId == rect.clipId || marks.draggedClipId == rect.clipId,
            draggedEdge = marks.draggedEdge?.takeIf { marks.trimmedClipId == rect.clipId },
            topTicks = layer.geometry.ticksForClipTop(ticks, rect),
            geometry = layer.geometry,
            track = track,
            paint = paint,
        )
    }
    marks.markerUs?.let { us ->
        val screenX = layer.geometry.pxFor(us) - layer.geometry.visibleStartPx
        drawReorderMarker(screenX, track, paint.reorderMarker)
    }
    drawPlayhead(layer.geometry, track, marks.playheadUs, paint.playhead)
}

/**
 * Ruler ticks.
 *
 * [ticks] comes in rather than being asked for here, because the selection is shared with the marks drawn on
 * each clip's top edge ([drawClipTopTicks]): one selection, two drawings.
 */
internal fun DrawScope.drawRuler(
    ticks: List<Long>,
    geometry: TimelineGeometry,
    rulerHeight: Float,
    color: Color,
) {
    ticks.forEach { tickUs ->
        val x = geometry.pxFor(tickUs) - geometry.visibleStartPx
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, rulerHeight),
            strokeWidth = RULER_TICK_WIDTH_PX,
        )
    }
}

/**
 * One clip: its background, its filmstrip, and its selection outline.
 *
 * The filmstrip is clipped to the clip's rectangle, so a slice cannot bleed over a neighbour however
 * the clip has been zoomed or scrolled.
 */
internal fun DrawScope.drawClip(
    rect: ClipRect,
    slices: List<SliceRequest>,
    images: Map<ThumbnailKey, ImageBitmap>,
    selected: Boolean,
    draggedEdge: ClipEdge?,
    topTicks: List<Long>,
    geometry: TimelineGeometry,
    track: Track,
    paint: TimelinePaint,
) {
    val left = rect.startPx - geometry.visibleStartPx
    clipRect(
        left = left,
        top = track.top,
        right = left + rect.widthPx,
        bottom = track.top + track.height,
    ) {
        drawRect(
            color = if (selected) paint.selectedClip else paint.clip,
            topLeft = Offset(left, track.top),
            size = Size(rect.widthPx, track.height),
        )
        slices.forEach { slice -> drawSlice(slice, images, geometry, track) }
        if (selected) {
            drawRect(
                color = paint.selectionBorder,
                topLeft = Offset(left, track.top),
                size = Size(rect.widthPx, track.height),
                style = Stroke(width = SELECTION_BORDER_PX),
            )
            // A stripe along the top as well as the outline. The device pass asked for a clearer
            // indicator, and the reason the outline was not enough is worth writing down: a clip is
            // mostly a thumbnail, so a 3 px border on a bright frame reads as part of the picture, and
            // "which clip am I editing?" is the one question this has to answer at a glance.
            drawRect(
                color = paint.selectionBorder,
                topLeft = Offset(left, track.top),
                size = Size(rect.widthPx, SELECTION_STRIPE_PX),
            )
        }
        // After the selection stripe rather than under it: the clip being cut is usually the selected one, and
        // a time reference that disappears exactly then is the one case the user asked for it in.
        drawClipTopTicks(topTicks, geometry, track, paint.ruler)
        // The edge being dragged, drawn INSIDE the clip's own clipping: the moment a trim shortens a
        // clip to nothing, its edge line would otherwise scribble over the neighbour.
        draggedEdge?.let { edge -> drawTrimEdge(edge, left, rect.widthPx, track, paint) }
    }
}

/**
 * The ruler's ticks, repeated along a clip's TOP EDGE (UI revision 2, task A3).
 *
 * The user: *"beri ruler indikator waktu juga di top clip untuk memudahkan cut ops dan keyframing"* — a cut
 * point has to be readable against a time where the cut is made, not only in the strip above the body. The
 * marks ARE the ruler's ([TimelineGeometry.ticksForClipTop]), so the two readings are the same time at the
 * same pixel, and zooming in tightens both together.
 *
 * Short marks rather than full-height lines: what is under them is the clip the user is looking at, and a
 * line across it would compete with the two edges a trim drags.
 */
private fun DrawScope.drawClipTopTicks(
    ticks: List<Long>,
    geometry: TimelineGeometry,
    track: Track,
    color: Color,
) {
    ticks.forEach { tickUs ->
        val x = geometry.pxFor(tickUs) - geometry.visibleStartPx
        drawLine(
            color = color,
            start = Offset(x, track.top),
            end = Offset(x, track.top + CLIP_TOP_TICK_PX),
            strokeWidth = RULER_TICK_WIDTH_PX,
        )
    }
}

/**
 * The line under the finger during a trim.
 *
 * Drawn rather than annotated: the clip's rectangle has already changed size (the document is being
 * previewed live), so the line is what says WHICH edge is moving — at a glance, without reading a
 * number.
 */
private fun DrawScope.drawTrimEdge(
    edge: ClipEdge,
    left: Float,
    widthPx: Float,
    track: Track,
    paint: TimelinePaint,
) {
    val x = if (edge == ClipEdge.IN) left else left + widthPx
    drawLine(
        color = paint.trimEdge,
        start = Offset(x, track.top),
        end = Offset(x, track.top + track.height),
        strokeWidth = TRIM_EDGE_PX,
    )
}

/**
 * One thumbnail, aspect-preserving and centred vertically.
 *
 * Preserving the aspect ratio matters more than filling the track: a 16:9 frame stretched into a
 * 96 dp track would show every clip squeezed, which reads as a problem with the media rather than
 * as a layout choice.
 */
internal fun DrawScope.drawSlice(
    slice: SliceRequest,
    images: Map<ThumbnailKey, ImageBitmap>,
    geometry: TimelineGeometry,
    track: Track,
) {
    val image = images[slice.key] ?: return
    val aspectHeight = slice.widthPx * image.height / image.width
    val top = track.top + (track.height - aspectHeight) / 2f
    drawImage(
        image = image,
        dstOffset = IntOffset(
            x = (slice.leftPx - geometry.visibleStartPx).roundToInt(),
            y = top.roundToInt(),
        ),
        dstSize = IntSize(
            width = slice.widthPx.roundToInt(),
            height = aspectHeight.roundToInt(),
        ),
    )
}

/**
 * The playhead: the fixed reference line of UI revision 1.
 *
 * ### Three times the track, top-aligned, and red
 *
 * The user's spec, and each part earns its place. **Three times the track's height** because a line the
 * height of the track disappears into the filmstrip it crosses — this one starts at the track's top and
 * reaches well past the clips, so it is findable at a glance. **Top-aligned** to the track rather than to
 * the canvas, so it is the same line whatever is above it.
 *
 * **Red**, asked for by name, and deliberately NOT a theme colour: a playhead that changed with the theme
 * (or with the dimmed state of a disabled control near it) stops being the fixed reference the revision
 * makes it. It is also the one element on screen that is not derived from the document — the tracks move
 * under it — and its colour says so.
 *
 * The line is drawn where the geometry maps the playhead, which under the revision is the viewport's centre
 * whenever the timeline is long enough to scroll that far (`centredPlayheadPx`), and drifts towards an edge
 * only at the ends.
 */
internal fun DrawScope.drawPlayhead(
    geometry: TimelineGeometry,
    track: Track,
    playheadUs: Long,
    color: Color,
) {
    geometry.playheadPx(playheadUs)?.let { x ->
        drawLine(
            color = color,
            start = Offset(x, track.top),
            end = Offset(x, track.top + track.height * PLAYHEAD_TRACK_MULTIPLE),
            strokeWidth = PLAYHEAD_WIDTH_PX,
        )
    }
}

private const val RULER_TICK_WIDTH_PX = 1f
private const val SELECTION_BORDER_PX = 3f
private const val SELECTION_STRIPE_PX = 6f
/** How far a clip's top-edge tick reaches down the clip (task A3). A mark, not a division. */
private const val CLIP_TOP_TICK_PX = 6f
private const val PLAYHEAD_WIDTH_PX = 3f

/** UI revision 1: the line reaches three track-heights down, so it reads over the filmstrip. */
private const val PLAYHEAD_TRACK_MULTIPLE = 3f
private const val TRIM_EDGE_PX = 4f
private const val REORDER_MARKER_PX = 6f
