package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CanvasSpec
import org.junit.jupiter.api.Test

/**
 * The export presets (FR-5.1 – FR-5.7): the two resolutions and their bitrates. The
 * geometry is asserted against [CanvasSpec]'s own entries because the presets are
 * DEFINED in terms of them — an assertion of the value would just restate the
 * constant under test.
 */
class ExportPresetsTest {

    @Test
    fun `at 1080p a portrait canvas yields the portrait entry with both bitrates set`() {
        val preset = ExportPresets.at1080p(CanvasSpec.PORTRAIT_1080)

        assertThat(preset.width).isEqualTo(CanvasSpec.PORTRAIT_1080.width)
        assertThat(preset.height).isEqualTo(CanvasSpec.PORTRAIT_1080.height)
        assertThat(preset.videoBitrate).isEqualTo(ExportPresets.VIDEO_BITRATE_1080P)
        assertThat(preset.audioBitrate).isEqualTo(ExportPresets.AUDIO_BITRATE_1080P)
    }

    @Test
    fun `at 1080p a landscape canvas yields the landscape entry`() {
        val preset = ExportPresets.at1080p(CanvasSpec.LANDSCAPE_1080)

        assertThat(preset.width).isEqualTo(CanvasSpec.LANDSCAPE_1080.width)
        assertThat(preset.height).isEqualTo(CanvasSpec.LANDSCAPE_1080.height)
        assertThat(preset.videoBitrate).isEqualTo(ExportPresets.VIDEO_BITRATE_1080P)
        assertThat(preset.audioBitrate).isEqualTo(ExportPresets.AUDIO_BITRATE_1080P)
    }

    @Test
    fun `at 720p the geometry matches the 720p entries at the lower bitrate rung`() {
        val portrait = ExportPresets.at720p(CanvasSpec.PORTRAIT_1080)
        val landscape = ExportPresets.at720p(CanvasSpec.LANDSCAPE_720)

        assertThat(portrait.width).isEqualTo(CanvasSpec.PORTRAIT_720.width)
        assertThat(portrait.height).isEqualTo(CanvasSpec.PORTRAIT_720.height)
        assertThat(portrait.videoBitrate).isEqualTo(ExportPresets.VIDEO_BITRATE_720P)
        assertThat(portrait.audioBitrate).isEqualTo(ExportPresets.AUDIO_BITRATE_720P)
        assertThat(landscape.width).isEqualTo(CanvasSpec.LANDSCAPE_720.width)
        assertThat(landscape.height).isEqualTo(CanvasSpec.LANDSCAPE_720.height)
    }

    @Test
    fun `the presets keep the frame rate the spec pins`() {
        assertThat(ExportPresets.at1080p(CanvasSpec.PORTRAIT_1080).fps).isEqualTo(OutputSpec.FPS)
        assertThat(ExportPresets.at720p(CanvasSpec.LANDSCAPE_1080).fps).isEqualTo(OutputSpec.FPS)
    }

    @Test
    fun `a canvas of neither bucket resolves by orientation to the landscape entry`() {
        // A square canvas is not portrait (height is not greater than width), so the
        // orientation rule hands it to the landscape bucket: FR-5.1 allows only the two
        // resolutions, and FR-5.7 derives the orientation rather than storing it.
        val preset = ExportPresets.at1080p(CanvasSpec.SQUARE_1080)

        assertThat(preset.isPortrait).isFalse()
        assertThat(preset.width).isEqualTo(CanvasSpec.LANDSCAPE_1080.width)
        assertThat(preset.height).isEqualTo(CanvasSpec.LANDSCAPE_1080.height)
    }
}
