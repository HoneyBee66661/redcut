package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.FitMode
import org.junit.jupiter.api.Test

class FitModeMathTest {

    @Test
    fun `fit mode keeps aspect ratio with uniform scale at or below one`() {
        // Source 1920x1080 into canvas 1080x1920 (portrait 9:16)
        val result = FitModeMath.compute(FitMode.FIT, 1920, 1080, 1080, 1920)

        // Source is wider (1.78) than canvas (0.562): height-limited, scale = canvasAspect / sourceAspect
        assertThat(result.scaleX).isEqualTo(result.scaleY)
        assertThat(result.scaleX).isLessThan(1f)
    }

    @Test
    fun `fill mode scales to cover the canvas, cropping the excess`() {
        // Source 1920x1080 into canvas 1080x1920 (portrait 9:16)
        val result = FitModeMath.compute(FitMode.FILL, 1920, 1080, 1080, 1920)

        // Fill: scale up so the content covers the canvas entirely.
        // canvasAspect = 1080/1920 = 0.5625; sourceAspect = 1920/1080 = 1.777...
        // sourceAspect > canvasAspect: width-limited, scale = 1.0 (the source width already fills)
        assertThat(result.scaleX).isEqualTo(result.scaleY)
    }

    @Test
    fun `stretch mode ignores aspect ratio`() {
        val result = FitModeMath.compute(FitMode.STRETCH, 1920, 1080, 1080, 1920)

        assertThat(result.scaleX).isEqualTo(1f)
        assertThat(result.scaleY).isEqualTo(1f)
        assertThat(result.offsetX).isEqualTo(0f)
        assertThat(result.offsetY).isEqualTo(0f)
    }

    @Test
    fun `fit mode with matching aspect ratios produces scale of one`() {
        val result = FitModeMath.compute(FitMode.FIT, 1080, 1920, 1080, 1920)

        assertThat(result.scaleX).isEqualTo(1f)
        assertThat(result.scaleY).isEqualTo(1f)
    }
}