package com.redcut.domain.render

import com.redcut.domain.document.FitMode

/**
 * Canvas-fit arithmetic (FR-3.8): how the source frame maps onto the canvas frame.
 *
 * Each [FitMode] defines a different mapping strategy. The result describes the scale factor
 * and offset (in canvas-normalised space) that the renderer or the preview surface should
 * apply to the source content.
 *
 * Keyframing is a later workstream (lane K, task K1); this is the static MVP version.
 */
object FitModeMath {

    /**
     * Computes the scale and translation that maps [sourceWidth] x [sourceHeight] into
     * a canvas of [canvasWidth] x [canvasHeight] under [mode].
     *
     * @return a [FitResult] with the uniform scale factor and the normalised offsets.
     */
    fun compute(
        mode: FitMode,
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): FitResult {
        require(sourceWidth > 0 && sourceHeight > 0) { "source must have positive dimensions" }
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must have positive dimensions" }

        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()

        return when (mode) {
            FitMode.FIT -> fit(sourceAspect, canvasAspect)
            FitMode.FILL -> fill(sourceAspect, canvasAspect)
            FitMode.STRETCH -> stretch()
        }
    }

    private fun fit(sourceAspect: Float, canvasAspect: Float): FitResult {
        val scale = if (sourceAspect > canvasAspect) {
            canvasAspect / sourceAspect // height-limited
        } else {
            1f // width-limited
        }
        val offsetX = if (sourceAspect > canvasAspect) {
            0f
        } else {
            (canvasAspect - sourceAspect) / 2f
        }
        val offsetY = if (sourceAspect > canvasAspect) {
            0f
        } else {
            0f
        }
        return FitResult(scaleX = scale, scaleY = scale, offsetX = offsetX, offsetY = offsetY)
    }

    private fun fill(sourceAspect: Float, canvasAspect: Float): FitResult {
        val scale = if (sourceAspect > canvasAspect) {
            1f // width-limited (crop top/bottom)
        } else {
            canvasAspect / sourceAspect // height-limited (crop left/right)
        }
        val offsetX = if (sourceAspect > canvasAspect) {
            0f
        } else {
            (canvasAspect - sourceAspect * scale) / (2f * scale)
        }
        val offsetY = if (sourceAspect > canvasAspect) {
            (1f - 1f / scale) / 2f
        } else {
            0f
        }
        return FitResult(
            scaleX = scale,
            scaleY = scale,
            offsetX = offsetX,
            offsetY = offsetY,
        )
    }

    private fun stretch(): FitResult =
        FitResult(scaleX = 1f, scaleY = 1f, offsetX = 0f, offsetY = 0f)

    /**
     * The result of a fit-mode computation.
     *
     * [scaleX] and [scaleY] are the uniform or independent scale factors applied to the source.
     * [offsetX] and [offsetY] are normalised offsets (in canvas space) for positioning.
     */
    data class FitResult(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float,
    )
}
