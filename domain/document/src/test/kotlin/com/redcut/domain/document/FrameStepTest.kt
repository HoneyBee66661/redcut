package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.timeline.Timebase
import org.junit.jupiter.api.Test

/**
 * Frame-stepping (FR-2.9), which exists for frame-accurate cuts.
 *
 * Two things are being tested at once, and the second is the one that would be wrong in a hurry: the
 * arithmetic of one frame, and WHOSE frame it is. A clip at 2x shows two source frames per timeline
 * frame, so a step taken from the source rate would move the picture twice as far as the user's finger
 * expects — and the cut they then made would be a frame or two off, which is exactly the error the
 * feature exists to prevent.
 */
class FrameStepTest {

    private val oneSecond = 1_000_000L

    private fun source(id: String = "src-1", frameRate: Float = 30f) = SourceRef(
        id = id,
        uri = "content://media/$id",
        displayName = "$id.mp4",
        durationUs = 60 * oneSecond,
        width = 1920,
        height = 1080,
        frameRate = frameRate,
    )

    private fun clip(
        id: String = "clip-a",
        sourceInUs: Long = 0L,
        sourceOutUs: Long = 4 * oneSecond,
        sourceId: String = "src-1",
        speed: Float = 1f,
    ) = Clip(
        id = id,
        sourceId = sourceId,
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        speed = speed,
    )

    private fun document(clip: Clip, vararg sources: SourceRef) = EditDocument(
        id = "doc",
        name = "Doc",
        sources = sources.toList().ifEmpty { listOf(source()) },
        tracks = listOf(videoTrack(clip)),
    )

    @Test
    fun `a step is one frame of the source rate`() {
        // 30 fps -> 33,333 µs a frame.
        val doc = document(clip())

        assertThat(doc.frameDurationUsAt(oneSecond)).isEqualTo(33_333L)
    }

    @Test
    fun `a sped-up clip steps by the TIMELINE frame, not the source frame`() {
        // At 2x the picture advances two source frames per timeline frame, so a timeline step is half
        // as long. Using the source rate here is the subtle bug: every cut made after stepping would
        // land a frame late.
        val doc = document(clip(speed = 2f))

        assertThat(doc.frameDurationUsAt(oneSecond)).isEqualTo(16_667L)
    }

    @Test
    fun `an unknown frame rate falls back to 30 rather than refusing to step`() {
        val doc = document(clip(), source(frameRate = 0f))

        assertThat(doc.frameDurationUsAt(oneSecond)).isEqualTo(33_333L)
    }

    @Test
    fun `a step is never zero, however extreme the clip`() {
        // 240 fps divided by a 4x clip rounds to ~1042 µs — but a pathological pair could round to 0,
        // and a step of zero is a button that does nothing and cannot be escaped.
        val doc = document(clip(speed = 4f), source(frameRate = 240f))

        // Half a second in: a 4x clip of a 4 s source range only occupies one second of timeline, so
        // the playhead has to be inside that.
        assertThat(doc.frameDurationUsAt(500_000L)).isAtLeast(1L)
    }

    @Test
    fun `forward and back are mirror images`() {
        val doc = document(clip())

        val forward = doc.steppedPlayheadUs(oneSecond, FrameStep.FORWARD)
        val back = doc.steppedPlayheadUs(forward, FrameStep.BACK)

        assertThat(forward).isEqualTo(oneSecond + 33_333L)
        assertThat(back).isEqualTo(oneSecond)
    }

    @Test
    fun `stepping back from the start stops at zero instead of wrapping to the end`() {
        val doc = document(clip())

        assertThat(doc.steppedPlayheadUs(0L, FrameStep.BACK)).isEqualTo(0L)
    }

    @Test
    fun `stepping forward past the end stops at the end`() {
        val doc = document(clip())

        assertThat(doc.steppedPlayheadUs(4 * oneSecond, FrameStep.FORWARD)).isEqualTo(4 * oneSecond)
    }

    @Test
    fun `an empty timeline has no frame to step to, and says so`() {
        val empty = EditDocument(id = "doc", name = "Doc")

        assertThat(empty.frameDurationUsAt(0L)).isNull()
        // There is no clip, so there is no frame: the step uses the default frame length and then the
        // timeline's own clamp (zero length) holds the playhead where it is. Predictable, and not a
        // surprise jump on a document with nothing in it.
        assertThat(empty.steppedPlayheadUs(0L, FrameStep.FORWARD)).isEqualTo(0L)
    }

    @Test
    fun `a step lands on the clip's own frame grid`() {
        // Twelve steps of a 30 fps clip must span exactly 400 000 µs: the point of the feature is
        // that repeated steps do not drift, which is what a rounded-then-multiplied value would do.
        // This assertion used to read 399 996 — the drift itself, written down as the expected
        // value, four microseconds short of frame 12 — and 400 000 is what the comment above it
        // always meant.
        val doc = document(clip())

        var playhead = 0L
        repeat(12) { playhead = doc.steppedPlayheadUs(playhead, FrameStep.FORWARD) }

        assertThat(playhead).isEqualTo(400_000L)
        assertThat(playhead / 33_333L).isEqualTo(12L)
    }

    @Test
    fun `stepping forward one thousand times at 29 point 97 lands exactly on frame 1000`() {
        // Equality, not tolerance. 1000 frames at 30000/1001 is 1000 x 1001 / 30000 = 33.3666... s,
        // i.e. 33 366 667 µs. The OLD arithmetic added a rounded 33 367 µs a step and landed on
        // 33 367 000 — 333 µs off the frame the user was looking at, after one thousand presses.
        val doc = document(clip(sourceOutUs = 40 * oneSecond), source(frameRate = 29.97f))

        var playhead = 0L
        repeat(1_000) { playhead = doc.steppedPlayheadUs(playhead, FrameStep.FORWARD) }

        assertThat(playhead).isEqualTo(33_366_667L)
        assertThat(playhead).isEqualTo(Timebase.NTSC_29_97.timeUsAt(1_000L))
    }

    @Test
    fun `stepping back from a between-frames playhead lands on the grid`() {
        // 1 050 000 µs is halfway through frame 31 of a 30 fps clip, so it is not a frame boundary
        // to begin with. A back-step lands on the start of frame 31 — subtracting a frame length
        // instead would give 1 016 667, which is not the start of anything.
        val doc = document(clip())

        assertThat(doc.steppedPlayheadUs(1_050_000L, FrameStep.BACK)).isEqualTo(1_033_333L)
    }

    @Test
    fun `an integer-rate source behaves exactly as before`() {
        // 240 fps at 4x: one source frame is 4 167 µs and a timeline frame is a quarter of that,
        // 1 041.75, which rounds to 1 042. The rational timebase gives the same number the Float
        // division did, so nothing about a whole rate changes — only the rates a Float cannot hold.
        val doc = document(clip(speed = 4f), source(frameRate = 240f))

        assertThat(doc.frameDurationUsAt(500_000L)).isEqualTo(1_042L)
        assertThat(doc.steppedPlayheadUs(500_000L, FrameStep.FORWARD)).isEqualTo(501_042L)
    }

    @Test
    fun `the default frame rate is the same 30 the timebase falls back to`() {
        // :core:common cannot import DEFAULT_FRAME_RATE — the dependency runs the other way, and a
        // pure module may not know the domain — so the two 30s are held equal HERE, in the one
        // module that can see both. Either one changing alone fails this.
        assertThat(Timebase.DEFAULT).isEqualTo(Timebase.fromFrameRate(DEFAULT_FRAME_RATE))
    }
}
