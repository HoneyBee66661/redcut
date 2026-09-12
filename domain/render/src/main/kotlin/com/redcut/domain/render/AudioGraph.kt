package com.redcut.domain.render

import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.TimeRange
import kotlinx.serialization.Serializable

/**
 * Everything about the finished audio mix that is not already a property of a
 * single layer (spec §6.8 rule D4).
 *
 * Per-clip gain, mute and fades live on [RenderLayer.Video.audio], next to the
 * footage they belong to. What is left for this type is what only exists once
 * per document: the master gain and the music bed.
 *
 * **Why the per-clip values are not duplicated here.** The obvious alternative —
 * an `AudioGraph` holding a flat list of audio segments — was rejected because it
 * would copy gain/mute/fades out of the layers into a second list that can drift
 * out of step with them. Phase 5's Oboe mix reads the same layers the video path
 * does, so there is one place a value can be wrong instead of two.
 *
 * The same reasoning scales to the mixer: [AudioGraph] stays all-params, no
 * constructed processor chain, so replacing the Media3 audio chain with the
 * native one is a swap rather than a rewrite of every call site (rule D4).
 */
@Serializable
data class AudioGraph(
    /** Master gain, applied after per-layer gain. 0f..2f. */
    val masterGain: Float = 1f,
    /** FR-1.6 music bed. Null when the project has none. */
    val music: AudioBed? = null,
) {
    init {
        require(masterGain in 0f..AudioSpec.MAX_GAIN) {
            "masterGain must be in 0f..${AudioSpec.MAX_GAIN}, was $masterGain"
        }
    }
}

/**
 * The music bed (FR-1.6).
 *
 * > **Not yet reachable.** `EditDocument` has no `audioBed` field yet — the model
 * > in spec §5 names one but the committed document does not carry it, so the
 * > compiler always emits `music = null`. The shape is declared now because the
 * > mapper and the native audio graph are written against it, and because adding
 * > it later is then an additive model change rather than a graph migration.
 *
 * [sourceRange] is null when the whole source plays; [loop] repeats it to fill
 * the timeline when the source is shorter than the video.
 */
@Serializable
data class AudioBed(
    val source: SourceRef,
    /** Slice of the source to play; null = the whole source. */
    val sourceRange: TimeRange? = null,
    /** Where on the timeline the bed starts. */
    val timelineStartUs: Long = 0L,
    /** 0f..2f. */
    val gain: Float = 1f,
    val muted: Boolean = false,
    val fades: FadeSpec = FadeSpec(),
    val loop: Boolean = false,
) {
    init {
        require(timelineStartUs >= 0L) { "timelineStartUs must be >= 0, was $timelineStartUs" }
        require(gain in 0f..AudioSpec.MAX_GAIN) {
            "gain must be in 0f..${AudioSpec.MAX_GAIN}, was $gain"
        }
    }
}
