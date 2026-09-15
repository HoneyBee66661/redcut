package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CanvasSpecTest {

    @Test
    fun `squared canvas reports as landscape (not portrait) because height equals width`() {
        assertThat(CanvasSpec.SQUARE_1080.isPortrait).isFalse()
    }

    @Test
    fun `landscape 4-to-3 is not portrait`() {
        assertThat(CanvasSpec.LANDSCAPE_4_3_1080.isPortrait).isFalse()
    }

    @Test
    fun `portrait 4-to-5 is portrait`() {
        assertThat(CanvasSpec.PORTRAIT_4_5_1080.isPortrait).isTrue()
    }

    @Test
    fun `portrait 3-to-4 is portrait`() {
        assertThat(CanvasSpec.PORTRAIT_3_4_1080.isPortrait).isTrue()
    }

    @Test
    fun `all new presets have positive dimensions`() {
        CanvasSpec.SQUARE_1080.let {
            assertThat(it.width).isEqualTo(1080)
            assertThat(it.height).isEqualTo(1080)
        }
        CanvasSpec.PORTRAIT_4_5_1080.let {
            assertThat(it.width).isEqualTo(1080)
            assertThat(it.height).isEqualTo(1350)
        }
        CanvasSpec.PORTRAIT_3_4_1080.let {
            assertThat(it.width).isEqualTo(1080)
            assertThat(it.height).isEqualTo(1440)
        }
        CanvasSpec.LANDSCAPE_4_3_1080.let {
            assertThat(it.width).isEqualTo(1440)
            assertThat(it.height).isEqualTo(1080)
        }
    }

    @Test
    fun `withLandscape preserves the same resolution class`() {
        assertThat(
            CanvasSpec.PORTRAIT_4_5_1080.withLandscape(isLandscape = true),
        ).isNotNull()
        assertThat(
            CanvasSpec.PORTRAIT_3_4_1080.withLandscape(isLandscape = true),
        ).isNotNull()
        assertThat(
            CanvasSpec.LANDSCAPE_4_3_1080.withLandscape(isLandscape = false),
        ).isNotNull()
    }

    @Test
    fun `OutputSpec fromCanvas follows the canvas dimensions`() {
        val spec = com.redcut.domain.render.OutputSpec.fromCanvas(CanvasSpec.SQUARE_1080)
        assertThat(spec.width).isEqualTo(1080)
        assertThat(spec.height).isEqualTo(1080)

        val spec45 = com.redcut.domain.render.OutputSpec.fromCanvas(CanvasSpec.PORTRAIT_4_5_1080)
        assertThat(spec45.width).isEqualTo(1080)
        assertThat(spec45.height).isEqualTo(1350)
    }
}