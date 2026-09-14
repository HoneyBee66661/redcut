package com.redcut.feature.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.redcut.core.media.ThumbnailKey
import org.junit.Test

/**
 * The filmstrip's arithmetic (spec §7.1, §9.3).
 *
 * Two cases below are the ones that matter most. The first is that the decoder is asked for a
 * SOURCE time: an earlier draft asked for a TIMELINE time, which for an untrimmed clip starting at
 * zero is the same number — so the mistake survives a quick look, and for a trimmed clip (the
 * normal case, since trimming is the app's purpose) it returns the wrong frame or nothing at all.
 *
 * The second is that a SPLIT does not change the size the strip draws at — the device pass's
 * *"saat split, height gambar clip mengecil seperti zoom out"*. It is the case the old code fails:
 * there the number of slices came from the clip's own width and was capped, so halving a clip
 * halved every drawn frame with it.
 */
class TimelineSlicesTest {

    private val oneSecond = 1_000_000L

    /**
     * A clip with the window defaulting the way the production type defaults it: to the clip's own
     * rectangle, which means "the whole clip". A test that passes neither window bound is therefore
     * testing the same thing a caller from before the window existed does.
     */
    private fun clip(
        clipId: String = "clip-1",
        sourceId: String = "src-1",
        leftPx: Float = 0f,
        widthPx: Float = 640f,
        durationUs: Long = 4 * oneSecond,
        sourceTimeAt: (Long) -> Long = { it },
        windowStartPx: Float = leftPx,
        windowEndPx: Float = leftPx + widthPx,
    ) = ClipSliceInput(
        clipId = clipId,
        sourceId = sourceId,
        uri = "content://media/$sourceId",
        leftPx = leftPx,
        widthPx = widthPx,
        durationUs = durationUs,
        sourceTimeAt = sourceTimeAt,
        windowStartPx = windowStartPx,
        windowEndPx = windowEndPx,
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
        // Rounded UP, which is what tiles the clip completely: a 161 px clip is one whole slice and
        // a 1 px sliver, and the sliver still needs a frame drawn on it.
        assertThat(TimelineSlices.totalSlices(160f)).isEqualTo(1)
        assertThat(TimelineSlices.totalSlices(640f)).isEqualTo(4)
        assertThat(TimelineSlices.totalSlices(161f)).isEqualTo(2)
    }

    @Test
    fun `a clip narrower than one slice still shows one, and a huge clip stops at the net`() {
        // At minimum zoom a 100 ms clip is 3 px wide; zero slices would draw an empty rectangle.
        val sliver = clip(widthPx = 3f, durationUs = oneSecond / 10)

        assertThat(TimelineSlices.requests(listOf(sliver)).map { it.widthPx }).containsExactly(3f)

        // The old cap lived here, and it is what the device pass caught: a huge clip was cut to
        // twelve slices, each stretched to a twelfth of the clip. What bounds the work now is the
        // WINDOW, and what is left is a net under ONE clip's contribution — which drops the tail
        // rather than rescaling what it returns, so every slice below is still a full pitch wide.
        val huge = clip(widthPx = 288_000f)
        val capped = TimelineSlices.requests(listOf(huge))

        assertThat(capped).hasSize(TimelineSlices.MAX_SLICES_PER_CLIP)
        assertThat(capped.map { it.widthPx }.distinct()).containsExactly(160f)
    }

    @Test
    fun `one clip's net cannot empty the clip that follows it`() {
        // The case the PASS-WIDE net failed. It was spent in document order, so the first clip took all
        // of it and the second came back with nothing at all: on a four-lane screen the last lanes had
        // no thumbnails, which reads as a broken loader rather than as a strip at its limit. Both clips
        // here are far past the net, so what is asserted holds for ANY net value — each clip's share is
        // its own, and neither can take the other's.
        val huge = clip(clipId = "huge", leftPx = 0f, widthPx = 288_000f)
        val starved = clip(clipId = "starved", leftPx = 288_000f, widthPx = 288_000f)

        val requests = TimelineSlices.requests(listOf(huge, starved))

        assertThat(requests.filter { it.clipId == "huge" })
            .hasSize(TimelineSlices.MAX_SLICES_PER_CLIP)
        assertThat(requests.filter { it.clipId == "starved" })
            .hasSize(TimelineSlices.MAX_SLICES_PER_CLIP)
    }

    @Test
    fun `slices tile the clip's width with no gaps and no overlap`() {
        val requests = TimelineSlices.requests(listOf(clip(widthPx = 480f)))

        assertThat(requests.map { it.leftPx }).containsExactly(0f, 160f, 320f).inOrder()
        assertThat(requests.map { it.widthPx }).containsExactly(160f, 160f, 160f).inOrder()
    }

    @Test
    fun `the last slice stops at the clip's end instead of reaching past it`() {
        // Five hundred pixels is three whole slices and twenty pixels left over: the fourth slice is
        // the clip's own remainder, and it ends at 500 rather than at 640. A request that ran past
        // the clip would name a rectangle belonging to the clip's neighbour.
        val requests = TimelineSlices.requests(listOf(clip(widthPx = 500f)))

        assertThat(requests.map { it.leftPx }).containsExactly(0f, 160f, 320f, 480f).inOrder()
        assertThat(requests.map { it.widthPx }).containsExactly(160f, 160f, 160f, 20f).inOrder()
    }

    @Test
    fun `the slice pitch follows the clip, not the window that is looking at it`() {
        // A window scrolled to 400..800 asks for the slices intersecting it, and their left edges are
        // the CLIP's own grid: the first it returns starts at 320, before the window does. A grid
        // anchored to the window would put a slice edge exactly ON the window's left edge, which
        // means every thumbnail would slide sideways by the remainder of a slice as the user scrolled.
        val scrolled = clip(widthPx = 960f, windowStartPx = 400f, windowEndPx = 800f)

        val requests = TimelineSlices.requests(listOf(scrolled))

        assertThat(requests.map { it.leftPx }).containsExactly(320f, 480f, 640f, 800f).inOrder()
    }

    @Test
    fun `a clip outside the window asks for nothing at all`() {
        // The rect list a pass is given carries clips the window's margin only just reaches; one past
        // its end has no slice on screen, and no request is the honest answer rather than a decode
        // that would be drawn nowhere.
        val offScreen = clip(
            leftPx = 2400f,
            widthPx = 480f,
            windowStartPx = 0f,
            windowEndPx = 1000f,
        )

        assertThat(TimelineSlices.requests(listOf(offScreen))).isEmpty()
    }

    @Test
    fun `splitting a clip in two does not change the size the filmstrip draws at`() {
        // The device pass, and the case the old code fails. A four thousand eight hundred pixel clip
        // is thirty slices of one hundred and sixty pixels; the old cap made it twelve slices of four
        // hundred, so its two halves — twelve slices of two hundred each — were drawn at HALF the
        // size the very same footage had been drawn at one frame earlier.
        val whole = clip(
            clipId = "whole",
            widthPx = 4800f,
            durationUs = 4 * oneSecond,
            windowStartPx = 0f,
            windowEndPx = 4800f,
        )
        val leftHalf = clip(
            clipId = "left",
            widthPx = 2400f,
            durationUs = 2 * oneSecond,
            windowStartPx = 0f,
            windowEndPx = 4800f,
        )
        // The right half's timeline zero is two seconds into the source, which is what a split leaves
        // behind: the same footage, under a new clip.
        val rightHalf = clip(
            clipId = "right",
            leftPx = 2400f,
            widthPx = 2400f,
            durationUs = 2 * oneSecond,
            sourceTimeAt = { offsetUs -> 2 * oneSecond + offsetUs },
            windowStartPx = 0f,
            windowEndPx = 4800f,
        )

        val wholeSlices = TimelineSlices.requests(listOf(whole))
        val splitSlices = TimelineSlices.requests(listOf(leftHalf, rightHalf))

        assertThat(splitSlices.map { it.widthPx }.distinct()).containsExactly(160f)
        // The same pixels at the same size AND the same frames: the slice grid and the sampling are
        // both anchored to the clip, so a split costs no decode and moves no thumbnail.
        assertThat(splitSlices.map { Triple(it.leftPx, it.widthPx, it.key) })
            .isEqualTo(wholeSlices.map { Triple(it.leftPx, it.widthPx, it.key) })
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
