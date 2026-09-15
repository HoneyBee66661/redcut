package com.redcut.domain.render

import com.redcut.domain.document.CanvasSpec
import kotlinx.serialization.Serializable

/**
 * The encoder's target: geometry, frame rate, bitrates (FR-5.1 – FR-5.7).
 *
 * Carried on the graph rather than passed to the exporter separately, because the
 * graph is what both paths render: preview at canvas resolution and export at the
 * chosen resolution are then the *same* value with two different outputs, which
 * is what makes the parity test in §12.3 writable at all.
 *
 * ### Divergence from the spec sketch: bitrates are nullable
 *
 * §4.3 declares `videoBitrate: Int` (non-null). Preview has no bitrate — it
 * renders into a surface — and the two options were a magic `0` or a made-up
 * number. A magic zero is the kind of value a later reader trusts and then
 * encodes with; `null` says plainly "the renderer chooses". Export always fills
 * them in from the bitrate preset (FR-5.6).
 */
@Serializable
data class OutputSpec(
    val width: Int,
    val height: Int,
    val fps: Int = FPS,
    /** Bits per second. Null = the renderer chooses (preview). */
    val videoBitrate: Int? = null,
    /** Bits per second. Null = the renderer chooses (preview). */
    val audioBitrate: Int? = null,
) {
    init {
        require(
            width > 0 && height > 0,
        ) { "output must have a positive size, was ${width}x$height" }
        require(fps > 0) { "fps must be > 0, was $fps" }
        require(videoBitrate == null || videoBitrate > 0) {
            "videoBitrate must be > 0 when set, was $videoBitrate"
        }
        require(audioBitrate == null || audioBitrate > 0) {
            "audioBitrate must be > 0 when set, was $audioBitrate"
        }
    }

    /** Portrait or landscape, derived from geometry — never stored (FR-5.7). */
    val isPortrait: Boolean get() = height > width

    companion object {
        /** FR-5.2: 30 fps fixed in MVP; 24/60 is v2. */
        const val FPS: Int = 30

        /**
         * The preview target for [canvas]: same geometry, no bitrates, because
         * preview never encodes.
         */
        fun preview(canvas: CanvasSpec): OutputSpec =
            OutputSpec(width = canvas.width, height = canvas.height)

        /**
         * The export target that follows [canvas] geometry: same dimensions, same
         * orientation, at the standard frame rate.
         */
        fun fromCanvas(canvas: CanvasSpec): OutputSpec =
            OutputSpec(width = canvas.width, height = canvas.height)
    }
}
