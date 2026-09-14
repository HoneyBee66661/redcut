package com.redcut.domain.document

import com.redcut.core.common.timeline.Timebase
import kotlinx.serialization.Serializable

/**
 * An imported media source. Immutable once probed.
 *
 * [uri] is an opaque String, NOT an android.net.Uri — that is what keeps this
 * module on the JVM. :engine:* is responsible for turning it into a real
 * MediaItem; if it cannot be resolved, the engine reports an error rather than
 * the domain learning about Android (spec §5.1).
 */
@Serializable
data class SourceRef(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    /** Container rotation metadata, 0/90/180/270. */
    val rotationDegrees: Int = 0,
    val frameRate: Float = 30f,
    /**
     * The rate as the exact rational the arithmetic uses, or 0 when only the Float is known.
     *
     * A probe reports a Float, and 29.97 is NOT 30000/1001: the grid a frame-step lands on has to
     * be the exact rate, or every step drifts off the frames the user is looking at. A numerator
     * of 0 means "derive it from [frameRate]", which is what [timebase] does — and it is the
     * default, so a project file written before this field existed still loads.
     */
    val frameRateNumerator: Int = 0,
    val frameRateDenominator: Int = 1,
    val hasAudio: Boolean = false,
    val videoCodec: String = "",
    val audioCodec: String? = null,
) {

    /**
     * The grid this source's frames sit on.
     *
     * The rational pair when whatever probed the source knew the exact rate, and otherwise the best
     * reading of [frameRate] — which still recognises the NTSC rates by value, so a Float 29.97
     * becomes 30000/1001 rather than a rate no frame boundary is ever on.
     */
    val timebase: Timebase
        get() = if (frameRateNumerator > 0) {
            Timebase(frameRateNumerator, frameRateDenominator)
        } else {
            Timebase.fromFrameRate(frameRate)
        }
}

/**
 * A clip: a range of one source, plus the EDIT-stage properties applied to it.
 *
 * [sourceInUs]/[sourceOutUs] are in SOURCE time. Timeline position is derived
 * (see [EditDocument.timeline]). This is the deliberate divergence from storing
 * absolute timeline positions: ripple edits, speed changes, and reordering all
 * become trivially correct, because nothing but the prefix sum ever moves.
 */
@Serializable
data class Clip(
    val id: String,
    val sourceId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val speed: Float = 1f,
    val reverse: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val transform: TransformSpec = TransformSpec(),
    val enabled: Boolean = true,
) {
    init {
        require(sourceInUs >= 0) { "sourceInUs must be >= 0, was $sourceInUs" }
        require(sourceOutUs > sourceInUs) {
            "sourceOutUs ($sourceOutUs) must exceed sourceInUs ($sourceInUs)"
        }
        require(speed > 0f) { "speed must be > 0, was $speed" }
    }

    /** Length of the source range this clip reads, before speed is applied. */
    val sourceDurationUs: Long get() = sourceOutUs - sourceInUs

    /**
     * Duration on the TIMELINE, i.e. after speed is applied.
     *
     * Computed, never stored — one source of truth. Clamped to [MIN_DURATION_US]
     * so a pathological speed cannot produce a zero-length clip that the trim UI
     * and the renderer would disagree about.
     */
    val timelineDurationUs: Long
        get() = ((sourceDurationUs / speed).toLong()).coerceAtLeast(MIN_DURATION_US)

    /** Maps a timeline offset within this clip back to an absolute source time. */
    fun sourceTimeAt(offsetUs: Long): Long {
        val scaled = (offsetUs * speed).toLong()
        return if (reverse) sourceOutUs - scaled else sourceInUs + scaled
    }

    companion object {
        /** 100 ms (FR-2). Trim/split/cut clamp to this; below it, delete instead. */
        const val MIN_DURATION_US = 100_000L
    }
}

/** Crop / scale / rotate / fit. Static in MVP — no keyframes (spec §1.3). */
@Serializable
data class TransformSpec(
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val fit: FitMode = FitMode.FIT,
) {
    init {
        require(cropRight > cropLeft) { "cropRight must exceed cropLeft" }
        require(cropBottom > cropTop) { "cropBottom must exceed cropTop" }
    }
}

@Serializable
enum class FitMode { FIT, FILL, STRETCH }
