package com.redcut.domain.document

import kotlinx.serialization.Serializable

/**
 * The preview viewport rectangle model (spec UI revision 2, §WS F / Task F1).
 *
 * User's requirement:
 * "zoom visual hanya pada area top 50% screen dengan catatan, clip sedang diselect.
 *  ada juga snap vertical horizontal center"
 *
 * A rect with a LOCKED aspect ratio derived from [CanvasSpec], pinch to zoom (about the
 * rect centre), and drag to pan. Coordinates [centerX] and [centerY] are normalised
 * to `0.0..1.0` within the canvas frame.
 *
 * [zoom] scales the visible portion of the frame: at `1.0` the viewport covers the entire
 * canvas frame, and larger values zoom in. The rect is strictly clamped so that it
 * cannot leave the frame boundaries `[0.0, 1.0]`.
 */
@Serializable
data class ViewportRect(
    val centerX: Float = DEFAULT_CENTER,
    val centerY: Float = DEFAULT_CENTER,
    val zoom: Float = MIN_ZOOM,
    val canvasSpec: CanvasSpec = CanvasSpec.PORTRAIT_1080,
) {
    init {
        require(zoom > 0f) { "zoom must be positive, was $zoom" }
    }

    /** The locked aspect ratio derived from [CanvasSpec]. */
    val aspectRatio: Float get() = canvasSpec.width.toFloat() / canvasSpec.height.toFloat()

    /** Normalised width of the viewport rect (`1.0 / zoom`). */
    val width: Float get() = 1f / zoom

    /** Normalised height of the viewport rect (`1.0 / zoom`). */
    val height: Float get() = 1f / zoom

    /** Normalised left edge of the viewport rect. */
    val left: Float get() = centerX - (width / HALF_DIVISOR)

    /** Normalised top edge of the viewport rect. */
    val top: Float get() = centerY - (height / HALF_DIVISOR)

    /** Normalised right edge of the viewport rect. */
    val right: Float get() = centerX + (width / HALF_DIVISOR)

    /** Normalised bottom edge of the viewport rect. */
    val bottom: Float get() = centerY + (height / HALF_DIVISOR)

    /** The snap target for the horizontal centre line (Y coordinate). */
    val snapTargetY: Float get() = DEFAULT_CENTER

    /** The snap target for the vertical centre line (X coordinate). */
    val snapTargetX: Float get() = DEFAULT_CENTER

    /**
     * Clamps the viewport so [zoom] is within [MIN_ZOOM]..[MAX_ZOOM] and the rect
     * cannot leave the `[0.0, 1.0]` frame.
     */
    fun clamped(): ViewportRect {
        val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val halfW = (1f / clampedZoom) / HALF_DIVISOR
        val halfH = (1f / clampedZoom) / HALF_DIVISOR
        val minX = halfW
        val maxX = 1f - halfW
        val minY = halfH
        val maxY = 1f - halfH
        val cx = if (minX <= maxX) centerX.coerceIn(minX, maxX) else DEFAULT_CENTER
        val cy = if (minY <= maxY) centerY.coerceIn(minY, maxY) else DEFAULT_CENTER
        return copy(
            centerX = cx,
            centerY = cy,
            zoom = clampedZoom,
        )
    }

    /**
     * Zooms about the rect centre by [factor], clamping the result.
     */
    fun zoomedBy(factor: Float): ViewportRect =
        copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)).clamped()

    /**
     * Pans by normalised [deltaX] and [deltaY], clamping the result so the rect stays in the frame.
     */
    fun pannedBy(deltaX: Float, deltaY: Float): ViewportRect =
        copy(centerX = centerX + deltaX, centerY = centerY + deltaY).clamped()

    /**
     * Resolves the viewport rect within a pixel frame of size [frameWidthPx] x [frameHeightPx].
     */
    fun toPixels(frameWidthPx: Float, frameHeightPx: Float): ViewportBoundsPx {
        val w = (1f / zoom) * frameWidthPx
        val h = (1f / zoom) * frameHeightPx
        val l = (centerX * frameWidthPx) - (w / HALF_DIVISOR)
        val t = (centerY * frameHeightPx) - (h / HALF_DIVISOR)
        return ViewportBoundsPx(left = l, top = t, width = w, height = h)
    }

    /**
     * Converts this viewport into a [TransformSpec] crop, mapping to [0.0, 1.0].
     */
    fun toTransformSpec(base: TransformSpec = TransformSpec()): TransformSpec =
        base.copy(
            cropLeft = left.coerceIn(0f, 1f),
            cropTop = top.coerceIn(0f, 1f),
            cropRight = right.coerceIn(0f, 1f),
            cropBottom = bottom.coerceIn(0f, 1f),
        )

    companion object {
        const val DEFAULT_CENTER = 0.5f
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 5.0f
        private const val HALF_DIVISOR = 2f

        /**
         * Reconstitutes a [ViewportRect] from a [TransformSpec].
         */
        fun fromTransformSpec(
            transform: TransformSpec,
            canvasSpec: CanvasSpec = CanvasSpec.PORTRAIT_1080,
        ): ViewportRect {
            val cropW = transform.cropRight - transform.cropLeft
            val cropH = transform.cropBottom - transform.cropTop
            val zoom = if (cropW > 0f) (1f / cropW).coerceIn(MIN_ZOOM, MAX_ZOOM) else MIN_ZOOM
            val cx = transform.cropLeft + (cropW / HALF_DIVISOR)
            val cy = transform.cropTop + (cropH / HALF_DIVISOR)
            return ViewportRect(
                centerX = cx,
                centerY = cy,
                zoom = zoom,
                canvasSpec = canvasSpec,
            ).clamped()
        }
    }
}

/**
 * Pixel bounds of a resolved [ViewportRect] in screen or canvas pixels.
 */
data class ViewportBoundsPx(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val aspectRatio: Float get() = if (height > 0f) width / height else 0f
}
