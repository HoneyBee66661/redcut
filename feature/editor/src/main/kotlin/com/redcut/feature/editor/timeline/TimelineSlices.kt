package com.redcut.feature.editor.timeline

import com.redcut.core.media.ThumbnailKey

/**
 * Where the thumbnails go: which frames a clip shows, and at which pixels.
 *
 * Split from [TimelineThumbnails] (which owns the loading) because this half is arithmetic, and
 * arithmetic is what can be checked in CI without a device, a `Bitmap` or a decoder. The same
 * reason the timeline's geometry lives in `:core:common`: the rules get tests, the I/O gets a thin
 * wrapper.
 */
internal object TimelineSlices {

    /** Spec §9.3: thumbnails are decoded at ≤ 160 px wide, so one covers 160 px of clip. */
    const val SLICE_WIDTH_PX = 160f

    /**
     * Per clip, per frame. A ceiling a real timeline never reaches.
     *
     * The reason it exists: at maximum zoom a 4-second clip is 1920 px wide, which is twelve
     * decodes — fine — but a 10-minute clip zoomed in is 288 000 px, and asking for 1800 decodes
     * for one frame of scrolling is precisely the jank NFR-8 forbids. Past the cap the strip
     * stretches its slices, which is a visual compromise rather than a stall.
     */
    const val MAX_SLICES_PER_CLIP = 12

    /** One thumbnail per [SLICE_WIDTH_PX] of clip width, never zero, never above the cap. */
    fun sliceCount(widthPx: Float): Int =
        (widthPx / SLICE_WIDTH_PX).toInt().coerceIn(1, MAX_SLICES_PER_CLIP)

    /**
     * The offset within a clip, in microseconds, that slice [index] of [count] samples.
     *
     * Samples the MIDDLE of each slice (`index * 2 + 1` over `count * 2`) rather than its start:
     * a frame taken at a slice boundary shows the frame the NEXT slice begins with, so the strip
     * reads as one frame out of step — the classic off-by-half that makes a filmstrip feel wrong
     * without anyone being able to say why.
     */
    fun sliceOffsetUs(durationUs: Long, index: Int, count: Int): Long =
        (durationUs * (index * 2 + 1)) / (count * 2)

    /**
     * Every slice the visible clips need, with the source time to decode and where to draw it.
     *
     * The distinction that matters, and that an earlier draft of this file got wrong: [SliceRequest
     * .positionUs] is a time in the SOURCE FILE, because that is what a decoder takes, while
     * [ClipSliceInput.startUs] and the placement are TIMELINE positions. For a trimmed clip — the
     * normal case, since trimming is the point of the app — those two are different numbers, and
     * asking the decoder for a timeline time returns the wrong frame or nothing at all. The
     * crossing is [ClipSliceInput.sourceTimeAt], which is the clip's own mapping, so the speed and
     * reverse handling stays in the domain where it is already tested.
     */
    fun requests(clips: List<ClipSliceInput>): List<SliceRequest> = clips.flatMap { clip ->
        val count = sliceCount(clip.widthPx)
        (0 until count).map { index ->
            val positionUs = clip.sourceTimeAt(sliceOffsetUs(clip.durationUs, index, count))
            SliceRequest(
                clipId = clip.clipId,
                key = ThumbnailKey(clip.sourceId, positionUs),
                sourceId = clip.sourceId,
                uri = clip.uri,
                positionUs = positionUs,
                leftPx = clip.leftPx + index * clip.widthPx / count,
                widthPx = clip.widthPx / count,
            )
        }
    }
}

/**
 * One visible clip, as the slice arithmetic needs it.
 *
 * [sourceTimeAt] maps an offset inside this clip to an absolute source time. It is a lambda rather
 * than a computation here because the clip's own model (`Clip.sourceTimeAt`) already knows about
 * speed and reverse, and a second implementation of that mapping in the UI would be a second answer
 * to the same question.
 */
internal data class ClipSliceInput(
    val clipId: String,
    val sourceId: String,
    val uri: String,
    val leftPx: Float,
    val widthPx: Float,
    val durationUs: Long,
    val sourceTimeAt: (offsetUs: Long) -> Long,
)

/** One thumbnail the canvas should draw, and where. */
internal data class SliceRequest(
    /** The clip this slice belongs to: two clips of one source share slices, and share a key. */
    val clipId: String,
    /** What the thumbnail cache is keyed by: the source, and a time inside it. */
    val key: ThumbnailKey,
    val sourceId: String,
    val uri: String,
    /** The time to decode, in the SOURCE file (not a timeline position — see [TimelineSlices.requests]). */
    val positionUs: Long,
    /** Where it is drawn, in content pixels (the timeline's own coordinate space). */
    val leftPx: Float,
    val widthPx: Float,
)
