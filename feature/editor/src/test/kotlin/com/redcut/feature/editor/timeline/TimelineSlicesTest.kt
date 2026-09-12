package com.redcut.feature.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.redcut.core.media.ThumbnailKey
import org.junit.Test

/**
 * The filmstrip's arithmetic (spec §7.1, §9.3).
 *
 * The case that matters most is the first one below: an earlier draft of this code asked the
 * decoder for a TIMELINE time where it needs a SOURCE time. For an untrimmed clip starting at
 * zero those are the same number, which is why the mistake survives a quick look — and for a
 * trimmed clip (the normal case, since trimming is the app's purpose) it returns the wrong frame
 * or nothing at all.
 */
class TimelineSlicesTest {

    private val oneSecond = 1_000_000L

    private fun clip(
        clipId: String = "clip-1",
        sourceId: String = "src-1",
        leftPx: Float = 0f,
        widthPx: Float = 640f,
        durationUs: Long = 4 * oneSecond,
        sourceTimeAt: (Long) -> Long = { it },
    ) = ClipSliceInput(
        clipId = clipId,
        sourceId = sourceId,
        uri = "content://media/$sourceId",
        leftPx = leftPx,
        widthPx = widthPx,
        durationUs = durationUs,
        sourceTimeAt = sourceTimeAt,
    )

    @Test
    fun `a slice is decoded at a SOURCE time, not at its timeline position`() {
        // A clip trimmed to start 10 s into its source: its timeline offset 0 is source 10 s.
        // Asking for the timeline time would decode a frame from the wrong part of the file.
        val trimmed = clip(sourceTimeAt = { offsetUs -> 10 * oneSecond + offsetUs })

        val first = TimelineSlices.requests(listOf(trimmed)).first()

        assertThat(first.positionUs).isEqualTo(10 * oneSecond + 500_000L)
        assertThat(first.key).isEqualTo(ThumbnailKey("src-1", 10 * oneSecond + 500_000L))
    }

    @Test
    fun `slice times sample the middle of each slice`() {
        // Four slices of a 4 s clip sample at 0.5, 1.5, 2.5 and 3.5 s. Sampling at the boundaries
        // would show, in each slice, the frame the NEXT one begins with.
        val offsets = (0 until 4).map { index ->
            TimelineSlices.sliceOffsetUs(
                4 * oneSecond,
                index,
                4,
            )
        }

        assertThat(offsets)
            .containsExactly(500_000L, 1_500_000L, 2_500_000L, 3_500_000L)
            .inOrder()
    }

    @Test
    fun `a clip shows one thumbnail per 160 px of its width`() {
        assertThat(TimelineSlices.sliceCount(160f)).isEqualTo(1)
        assertThat(TimelineSlices.sliceCount(640f)).isEqualTo(4)
        assertThat(TimelineSlices.sliceCount(161f)).isEqualTo(1)
    }

    @Test
    fun `a clip narrower than one thumbnail still shows one, and a huge clip is capped`() {
        // At minimum zoom a 100 ms clip is 3 px wide; zero slices would draw an empty rectangle.
        assertThat(TimelineSlices.sliceCount(3f)).isEqualTo(1)
        // A 10-minute clip at maximum zoom would otherwise ask for 1800 decodes in one frame.
        assertThat(
            TimelineSlices.sliceCount(288_000f),
        ).isEqualTo(TimelineSlices.MAX_SLICES_PER_CLIP)
    }

    @Test
    fun `slices tile the clip's width with no gaps and no overlap`() {
        val requests = TimelineSlices.requests(listOf(clip(widthPx = 480f)))

        assertThat(requests.map { it.leftPx }).containsExactly(0f, 160f, 320f).inOrder()
        assertThat(requests.map { it.widthPx }).containsExactly(160f, 160f, 160f).inOrder()
    }

    @Test
    fun `two clips of one source share a thumbnail when they show the same frame`() {
        // Both clips start at source 0 with the same shape, so their first slices are the same
        // frame: one decode, one cache entry (§9.3's key is the source and the time, not the clip).
        val a = clip(clipId = "clip-a", leftPx = 0f)
        val b = clip(clipId = "clip-b", leftPx = 640f)

        val requests = TimelineSlices.requests(listOf(a, b))

        assertThat(requests.map { it.key }.distinct()).hasSize(4)
        assertThat(requests.first { it.clipId == "clip-a" }.key)
            .isEqualTo(requests.first { it.clipId == "clip-b" }.key)
    }

    @Test
    fun `requests carry the clip they belong to, so the draw pass can clip them`() {
        val requests = TimelineSlices.requests(
            listOf(clip(clipId = "clip-a"), clip(clipId = "clip-b", leftPx = 640f)),
        )

        assertThat(requests.filter { it.clipId == "clip-a" }).hasSize(4)
        assertThat(requests.filter { it.clipId == "clip-b" }).hasSize(4)
    }

    @Test
    fun `no clips means no requests`() {
        assertThat(TimelineSlices.requests(emptyList())).isEmpty()
    }
}
