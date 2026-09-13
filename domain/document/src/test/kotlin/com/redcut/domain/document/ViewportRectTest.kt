package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ViewportRectTest {

    @Test
    fun `the rect aspect ratio is locked to the canvas spec`() {
        val portrait = ViewportRect(canvasSpec = CanvasSpec.PORTRAIT_1080)
        val landscape = ViewportRect(canvasSpec = CanvasSpec.LANDSCAPE_1080)

        assertThat(portrait.aspectRatio).isEqualTo(1080f / 1920f)
        assertThat(landscape.aspectRatio).isEqualTo(1920f / 1080f)

        // Across any zoom level, pixel aspect ratio matches the canvas aspect ratio
        val zoomedPortrait = portrait.copy(zoom = 2.5f)
        val pixels = zoomedPortrait.toPixels(frameWidthPx = 1080f, frameHeightPx = 1920f)
        assertThat(pixels.aspectRatio).isEqualTo(1080f / 1920f)
    }

    @Test
    fun `zoom is clamped within minimum and maximum bounds`() {
        val tooSmall = ViewportRect(zoom = 0.5f).clamped()
        val tooBig = ViewportRect(zoom = 10.0f).clamped()

        assertThat(tooSmall.zoom).isEqualTo(ViewportRect.MIN_ZOOM)
        assertThat(tooBig.zoom).isEqualTo(ViewportRect.MAX_ZOOM)
    }

    @Test
    fun `at minimum zoom the rect fills the frame and cannot pan`() {
        val rect = ViewportRect(zoom = 1.0f)
        val panned = rect.pannedBy(deltaX = 0.2f, deltaY = -0.3f)

        assertThat(panned.centerX).isEqualTo(ViewportRect.DEFAULT_CENTER)
        assertThat(panned.centerY).isEqualTo(ViewportRect.DEFAULT_CENTER)
        assertThat(panned.left).isEqualTo(0f)
        assertThat(panned.right).isEqualTo(1f)
        assertThat(panned.top).isEqualTo(0f)
        assertThat(panned.bottom).isEqualTo(1f)
    }

    @Test
    fun `zoomed rect cannot leave the frame boundaries`() {
        // At 2x zoom, rect size is 0.5 x 0.5. Half width is 0.25.
        // Valid center range is [0.25, 0.75].
        val rect = ViewportRect(zoom = 2.0f).clamped()

        val pannedOffLeft = rect.pannedBy(deltaX = -1.0f, deltaY = 0f)
        assertThat(pannedOffLeft.centerX).isEqualTo(0.25f)
        assertThat(pannedOffLeft.left).isEqualTo(0f)
        assertThat(pannedOffLeft.right).isEqualTo(0.5f)

        val pannedOffRight = rect.pannedBy(deltaX = 1.0f, deltaY = 0f)
        assertThat(pannedOffRight.centerX).isEqualTo(0.75f)
        assertThat(pannedOffRight.left).isEqualTo(0.5f)
        assertThat(pannedOffRight.right).isEqualTo(1.0f)

        val pannedOffTop = rect.pannedBy(deltaX = 0f, deltaY = -1.0f)
        assertThat(pannedOffTop.centerY).isEqualTo(0.25f)
        assertThat(pannedOffTop.top).isEqualTo(0f)
        assertThat(pannedOffTop.bottom).isEqualTo(0.5f)

        val pannedOffBottom = rect.pannedBy(deltaX = 0f, deltaY = 1.0f)
        assertThat(pannedOffBottom.centerY).isEqualTo(0.75f)
        assertThat(pannedOffBottom.top).isEqualTo(0.5f)
        assertThat(pannedOffBottom.bottom).isEqualTo(1.0f)
    }

    @Test
    fun `zooming in scales about the rect centre`() {
        val rect = ViewportRect(centerX = 0.5f, centerY = 0.5f, zoom = 1.0f)
        val zoomed = rect.zoomedBy(2.0f)

        assertThat(zoomed.centerX).isEqualTo(0.5f)
        assertThat(zoomed.centerY).isEqualTo(0.5f)
        assertThat(zoomed.zoom).isEqualTo(2.0f)
        assertThat(zoomed.width).isEqualTo(0.5f)
        assertThat(zoomed.height).isEqualTo(0.5f)
    }

    @Test
    fun `snap targets are the centre lines`() {
        val rect = ViewportRect()

        assertThat(rect.snapTargetX).isEqualTo(0.5f)
        assertThat(rect.snapTargetY).isEqualTo(0.5f)
    }

    @Test
    fun `round-trip to and from TransformSpec preserves coordinates and zoom`() {
        val original = ViewportRect(centerX = 0.6f, centerY = 0.4f, zoom = 2.0f).clamped()
        val transform = original.toTransformSpec()

        val restored = ViewportRect.fromTransformSpec(transform)
        assertThat(restored.centerX).isEqualTo(original.centerX)
        assertThat(restored.centerY).isEqualTo(original.centerY)
        assertThat(restored.zoom).isEqualTo(original.zoom)
    }

    @Test
    fun `a rect 2 px off centre snaps to the centre lines when a clip is selected`() {
        val frameW = 1080f
        val frameH = 1920f
        // 2 px off centre on each axis at 2x zoom
        val rect = ViewportRect(
            centerX = 0.5f + (2f / frameW),
            centerY = 0.5f + (2f / frameH),
            zoom = 2.0f,
        ).clamped()

        val snapped = rect.snappedToCentre(
            hasSelection = true,
            frameWidthPx = frameW,
            frameHeightPx = frameH,
        )

        assertThat(snapped.centerX).isEqualTo(0.5f)
        assertThat(snapped.centerY).isEqualTo(0.5f)
    }

    @Test
    fun `a rect well off centre does not snap`() {
        val frameW = 1080f
        val frameH = 1920f
        // 50 px off centre on each axis (well above default 8 px threshold)
        val rect = ViewportRect(
            centerX = 0.5f + (50f / frameW),
            centerY = 0.5f + (50f / frameH),
            zoom = 2.0f,
        ).clamped()

        val snapped = rect.snappedToCentre(
            hasSelection = true,
            frameWidthPx = frameW,
            frameHeightPx = frameH,
        )

        assertThat(snapped.centerX).isEqualTo(rect.centerX)
        assertThat(snapped.centerY).isEqualTo(rect.centerY)
    }

    @Test
    fun `snapping is inert when no clip is selected`() {
        val frameW = 1080f
        val frameH = 1920f
        // 2 px off centre, but hasSelection is false
        val rect = ViewportRect(
            centerX = 0.5f + (2f / frameW),
            centerY = 0.5f + (2f / frameH),
            zoom = 2.0f,
        ).clamped()

        val result = rect.snappedToCentre(
            hasSelection = false,
            frameWidthPx = frameW,
            frameHeightPx = frameH,
        )

        assertThat(result.centerX).isEqualTo(rect.centerX)
        assertThat(result.centerY).isEqualTo(rect.centerY)
    }

    @Test
    fun `top-level snappedToCentre delegates to rect`() {
        val frameW = 1080f
        val frameH = 1920f
        val rect = ViewportRect(
            centerX = 0.5f + (2f / frameW),
            centerY = 0.5f,
            zoom = 2.0f,
        ).clamped()

        val snapped = snappedToCentre(
            rect = rect,
            hasSelection = true,
            frameWidthPx = frameW,
            frameHeightPx = frameH,
        )

        assertThat(snapped.centerX).isEqualTo(0.5f)
    }
}
