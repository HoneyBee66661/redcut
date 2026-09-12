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
import com.redcut.core.common.timeline.ClipRect
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.media.ThumbnailKey
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
)

/** The clip track: everything below the ruler. */
internal data class Track(val top: Float, val height: Float)

/** Ruler ticks. */
internal fun DrawScope.drawRuler(geometry: TimelineGeometry, rulerHeight: Float, color: Color) {
    geometry.rulerTicks().forEach { tickUs ->
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
        }
    }
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

/** The playhead, drawn over everything and only when it is on screen. */
internal fun DrawScope.drawPlayhead(geometry: TimelineGeometry, playheadUs: Long, color: Color) {
    geometry.playheadPx(playheadUs)?.let { x ->
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = PLAYHEAD_WIDTH_PX,
        )
    }
}

private const val RULER_TICK_WIDTH_PX = 1f
private const val SELECTION_BORDER_PX = 3f
private const val PLAYHEAD_WIDTH_PX = 2f
