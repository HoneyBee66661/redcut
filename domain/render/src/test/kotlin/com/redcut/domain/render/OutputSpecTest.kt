package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.EditDocument
import org.junit.jupiter.api.Test

/**
 * OutputSpec follows the canvas (FR-3.x, FR-5.7): geometry is DERIVED from the
 * document's canvas, so a project that changes canvas moves its export target too.
 */
class OutputSpecTest {

    @Test
    fun `OutputSpec fromCanvas follows the canvas dimensions`() {
        val spec = OutputSpec.fromCanvas(CanvasSpec.SQUARE_1080)
        assertThat(spec.width).isEqualTo(1080)
        assertThat(spec.height).isEqualTo(1080)

        val spec45 = OutputSpec.fromCanvas(CanvasSpec.PORTRAIT_4_5_1080)
        assertThat(spec45.width).isEqualTo(1080)
        assertThat(spec45.height).isEqualTo(1350)
    }

    @Test
    fun `an export target with no explicit override follows the canvas when the project changes`() {
        val project = EditDocument(id = "doc", name = "Doc")
        val moved = project.copy(canvas = CanvasSpec.SQUARE_1080)

        assertThat(OutputSpec.fromCanvas(project.canvas).height).isEqualTo(1920)
        assertThat(OutputSpec.fromCanvas(moved.canvas))
            .isEqualTo(OutputSpec(width = 1080, height = 1080))
    }
}
