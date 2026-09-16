package com.redcut.domain.render

import com.redcut.domain.document.CanvasSpec

/**
 * The export presets (FR-5.1 – FR-5.7): the two fixed resolutions the MVP ships, each with the
 * bitrates that resolution is expected to spend.
 *
 * ### Why the geometry comes from [CanvasSpec]'s own entries
 *
 * FR-5.1 allows exactly two resolutions — 720p and 1080p — and FR-5.7 derives the orientation
 * from the canvas. [CanvasSpec] already names those four geometries (`PORTRAIT_720`,
 * `PORTRAIT_1080`, `LANDSCAPE_720`, `LANDSCAPE_1080`), so a preset that cannot be stated in
 * terms of them is a preset the document model has no word for; a preset whose geometry disagrees
 * with the canvas entry of the same name is a bug the type system was begging to catch. A canvas
 * that is neither (a square, a 4:5) resolves to its orientation's bucket: the export frame is one
 * of the two FR-5.1 sizes, and the letterboxing is the encoder's job (the engine maps the graph
 * onto that frame; see `RenderGraphMapper`'s output effects).
 *
 * ### The bitrates (FR-5.6)
 *
 * One rung per resolution for the MVP — the frame rate is fixed (FR-5.2) and the codec is fixed
 * (FR-5.3, FR-5.4), so resolution is the only axis a preset can sensibly move. The values are the
 * conventional H.264 targets for 30 fps content; [OutputSpec]'s nullable bitrates exist so that
 * preview can leave them unset, and these presets are the "export always fills them in" half of
 * that contract. A Low/Medium/High axis on top of resolution is a later phase's addition — it
 * would multiply the presets before the preflight (FR-5.11) exists to explain them.
 */
object ExportPresets {

    /** H.264 target for 1080p at the fixed 30 fps (FR-5.3, FR-5.6). */
    const val VIDEO_BITRATE_1080P: Int = 8_000_000

    /** H.264 target for 720p at the fixed 30 fps (FR-5.3, FR-5.6). */
    const val VIDEO_BITRATE_720P: Int = 5_000_000

    /** AAC-LC stereo target for a 1080p export (FR-5.4). */
    const val AUDIO_BITRATE_1080P: Int = 192_000

    /** AAC-LC stereo target for a 720p export (FR-5.4). */
    const val AUDIO_BITRATE_720P: Int = 128_000

    /**
     * The 1080p export target for [canvas]'s orientation: `1080x1920` portrait or `1920x1080`
     * landscape, with both bitrates set.
     */
    fun at1080p(canvas: CanvasSpec): OutputSpec =
        preset(canvas, portrait = CanvasSpec.PORTRAIT_1080, landscape = CanvasSpec.LANDSCAPE_1080)
            .copy(
                videoBitrate = VIDEO_BITRATE_1080P,
                audioBitrate = AUDIO_BITRATE_1080P,
            )

    /**
     * The 720p export target for [canvas]'s orientation: `720x1280` portrait or `1280x720`
     * landscape, with both bitrates set.
     */
    fun at720p(canvas: CanvasSpec): OutputSpec =
        preset(canvas, portrait = CanvasSpec.PORTRAIT_720, landscape = CanvasSpec.LANDSCAPE_720)
            .copy(
                videoBitrate = VIDEO_BITRATE_720P,
                audioBitrate = AUDIO_BITRATE_720P,
            )

    /**
     * The bucket [canvas]'s orientation names, stated through the enum entries of the same name
     * so the preset's geometry and the document's vocabulary cannot drift apart.
     */
    private fun preset(
        canvas: CanvasSpec,
        portrait: CanvasSpec,
        landscape: CanvasSpec,
    ): OutputSpec {
        val entry = if (canvas.isPortrait) portrait else landscape
        return OutputSpec(width = entry.width, height = entry.height)
    }
}
