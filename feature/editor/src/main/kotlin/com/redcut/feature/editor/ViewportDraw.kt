package com.redcut.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.redcut.domain.document.ViewportBoundsPx
import com.redcut.domain.document.ViewportRect
import kotlin.math.abs

/**
 * Drawing primitives for the preview viewport rectangle overlay (spec §WS F / Task F4).
 *
 * User's requirement (§1 row 12):
 * "zoom visual hanya pada area top 50% screen dengan catatan, clip sedang diselect.
 *  ada juga snap vertical horizontal center"
 *
 * Renders the locked-aspect-ratio viewport rect border, corner handles, and horizontal/vertical
 * centre snap guide lines over the preview area.
 */

private const val RECT_STROKE_WIDTH_PX = 2f
private const val CORNER_STROKE_WIDTH_PX = 4f
private const val CORNER_LENGTH_PX = 24f
private const val SNAP_LINE_WIDTH_PX = 1.5f
private const val SNAP_TOLERANCE_PX = 1f
private const val DASH_ON_PX = 10f
private const val DASH_OFF_PX = 10f
private const val HALF_DIVISOR = 2f

/**
 * Colours used to render the preview viewport rect and snap guides.
 * Captured outside DrawScope where MaterialTheme is available.
 */
internal data class ViewportPaint(
    val rectBorder: Color,
    val cornerHandle: Color,
    val snapLine: Color,
)

/**
 * Draws the viewport rectangle overlay and centre snap lines over the preview area.
 */
internal fun DrawScope.drawViewportOverlay(rect: ViewportRect, paint: ViewportPaint) {
    val frameWidth = size.width
    val frameHeight = size.height
    if (frameWidth <= 0f || frameHeight <= 0f) return

    val bounds = rect.toPixels(frameWidth, frameHeight)

    // Snap guidelines when snapped to horizontal or vertical centre
    val isSnappedH = abs(rect.centerY - rect.snapTargetY) * frameHeight <= SNAP_TOLERANCE_PX
    val isSnappedV = abs(rect.centerX - rect.snapTargetX) * frameWidth <= SNAP_TOLERANCE_PX

    if (isSnappedH) {
        drawSnapLine(
            start = Offset(0f, frameHeight / HALF_DIVISOR),
            end = Offset(frameWidth, frameHeight / HALF_DIVISOR),
            color = paint.snapLine,
        )
    }

    if (isSnappedV) {
        drawSnapLine(
            start = Offset(frameWidth / HALF_DIVISOR, 0f),
            end = Offset(frameWidth / HALF_DIVISOR, frameHeight),
            color = paint.snapLine,
        )
    }

    // Viewport rect outline
    drawRect(
        color = paint.rectBorder,
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        style = Stroke(width = RECT_STROKE_WIDTH_PX),
    )

    // Corner handles
    drawCornerHandles(bounds, paint.cornerHandle)
}

private fun DrawScope.drawSnapLine(start: Offset, end: Offset, color: Color) {
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = SNAP_LINE_WIDTH_PX,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON_PX, DASH_OFF_PX)),
    )
}

private fun DrawScope.drawCornerHandles(bounds: ViewportBoundsPx, color: Color) {
    val length = minOf(CORNER_LENGTH_PX, bounds.width / HALF_DIVISOR, bounds.height / HALF_DIVISOR)
    val l = bounds.left
    val r = bounds.right
    val t = bounds.top
    val b = bounds.bottom

    // Top-left
    drawHandle(color, Offset(l, t), Offset(l + length, t))
    drawHandle(color, Offset(l, t), Offset(l, t + length))

    // Top-right
    drawHandle(color, Offset(r, t), Offset(r - length, t))
    drawHandle(color, Offset(r, t), Offset(r, t + length))

    // Bottom-left
    drawHandle(color, Offset(l, b), Offset(l + length, b))
    drawHandle(color, Offset(l, b), Offset(l, b - length))

    // Bottom-right
    drawHandle(color, Offset(r, b), Offset(r - length, b))
    drawHandle(color, Offset(r, b), Offset(r, b - length))
}

private fun DrawScope.drawHandle(color: Color, start: Offset, end: Offset) {
    drawLine(color = color, start = start, end = end, strokeWidth = CORNER_STROKE_WIDTH_PX)
}
