package com.redcut.core.common.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The frame grid (FR-2.9), and the timebase everything later cuts and keyframes against.
 *
 * Two things are pinned here. The first is arithmetic a reader can check by hand: a 30 fps frame is
 * 33 333 µs, and 24 000 frames at 24 000/1001 fps is exactly 1001 seconds. The second is WHY the
 * rate is a rational and not a Float — 29.97 is not 30000/1001, and a grid built on the rounded
 * Float drifts off the very frames the user is trying to land on.
 */
class TimebaseTest {

    private fun timebase(numerator: Int, denominator: Int = 1) = Timebase(numerator, denominator)

    // --- The grid ----------------------------------------------------------

    @Test
    fun `a whole rate is a frame every 33 333 microseconds, rounded to the nearest`() {
        val thirty = timebase(30)

        assertThat(thirty.frameIndexAt(0L)).isEqualTo(0L)
        // One microsecond short of a frame, and rounded UP: the index is nearest, not floor, so a
        // playhead dragged a hair past a boundary reads as the frame the user is looking at.
        assertThat(thirty.frameIndexAt(33_333L)).isEqualTo(1L)
        assertThat(thirty.timeUsAt(1L)).isEqualTo(33_333L)
    }

    @Test
    fun `the three index readings are the round, the floor and the ceiling of one frame`() {
        // 1 050 000 µs is halfway between the starts of frames 31 and 32 at 30 fps. A cut rounds, a
        // range that must not reach forward floors, and one that must not fall short ceilings.
        val thirty = timebase(30)

        assertThat(thirty.frameIndexAtOrBefore(1_050_000L)).isEqualTo(31L)
        assertThat(thirty.frameIndexAt(1_050_000L)).isEqualTo(32L)
        assertThat(thirty.frameIndexAtOrAfter(1_050_000L)).isEqualTo(32L)
        // On the grid the three agree, which is what a caller relies on when it does not care.
        assertThat(thirty.frameIndexAtOrBefore(1_000_000L)).isEqualTo(30L)
        assertThat(thirty.frameIndexAtOrAfter(1_000_000L)).isEqualTo(30L)
    }

    @Test
    fun `snapping is idempotent, so a snap on every frame cannot walk`() {
        // The property that makes a snap safe to apply defensively: a second snap is a no-op, so
        // code that snaps on the way in and again on the way out cannot compound two roundings.
        val thirty = timebase(30)

        listOf(0L, 1L, 33_333L, 1_050_000L, 4_000_000L).forEach { timeUs ->
            val snapped = thirty.snapUs(timeUs)
            assertThat(thirty.snapUs(snapped)).isEqualTo(snapped)
        }
        assertThat(thirty.snapUs(1_050_000L)).isEqualTo(1_066_667L)
    }

    // --- The NTSC rates, which are the reason for the rational ---------------

    @Test
    fun `at 30000 over 1001 fps a thousand frames spans 33 seconds and a third`() {
        // 1000 x 1001 / 30000 = 33.36666... s, i.e. 33 366 667 µs. The FLOAT 29.97 gives a frame of
        // 33 366.7 µs, so 1000 of them is 33 366 700 — 33 µs long, and the error compounds with
        // every step, which is drift the user feels as "the cut is a frame late".
        val ntsc = Timebase.NTSC_29_97

        assertThat(ntsc.timeUsAt(1L)).isEqualTo(33_367L)
        assertThat(ntsc.timeUsAt(1_000L)).isEqualTo(33_366_667L)
        // One second in: 1 000 000 x 30000 / 1001 / 1 000 000 = 29.97, which is frame 30.
        assertThat(ntsc.frameIndexAt(1_000_000L)).isEqualTo(30L)
    }

    @Test
    fun `at 24000 over 1001 fps, 24000 frames is exactly 1001 seconds`() {
        // The whole point of keeping the rate rational: 24000 / (24000/1001) = 1001 s, exact. Frame
        // 23 976 is 23976/24000 of that — 999.999 s — because 1000 s holds 23 976.024 frames at
        // this rate, not 23 976. A Float cannot express either number.
        val film = Timebase.NTSC_23_976

        assertThat(film.timeUsAt(24_000L)).isEqualTo(1_001_000_000L)
        assertThat(film.timeUsAt(23_976L)).isEqualTo(999_999_000L)
    }

    // --- Timecode ----------------------------------------------------------

    @Test
    fun `an hour of the film rate reads as an hour of timecode not fifty nine minutes`() {
        // THE anti-drift assertion. 3600 s of real elapsed time is 01:00:00 at any frame rate, and
        // the frame field is the frame inside that second. Counting frames instead — 86 314 of
        // them, at a nominal 24 fps — would read 00:59:57, and the gap grows with every hour.
        val film = Timebase.NTSC_23_976

        val timecode = film.formatTimecode(3_600 * 1_000_000L)

        assertThat(timecode.substringBeforeLast(':')).isEqualTo("01:00:00")
        assertThat(timecode).isEqualTo("01:00:00:00")
    }

    @Test
    fun `the frame field is the frame inside the second, and never rolls into the next one`() {
        // Half a second in: 0.5 x 23.976 = 11.99 frames, so frame 12. And the last millisecond of
        // the second reads frame 23, not 24: a second holds 23.976 frames at this rate, so the
        // clamp is what keeps the field inside the second the elapsed-time fields named.
        val film = Timebase.NTSC_23_976

        assertThat(film.formatTimecode(3_600_500_000L)).isEqualTo("01:00:00:12")
        assertThat(film.formatTimecode(3_600_999_000L)).isEqualTo("01:00:00:23")
    }

    // --- Stepping ----------------------------------------------------------

    @Test
    fun `a step forward and back is one frame, from any starting point`() {
        val ntsc = Timebase.NTSC_29_97

        assertThat(ntsc.addFrames(0L, 1L)).isEqualTo(33_367L)
        assertThat(ntsc.addFrames(33_367L, -1L)).isEqualTo(0L)
    }

    @Test
    fun `stepping from between frames lands on the grid, not off it`() {
        // 1 050 000 µs is halfway between the starts of frames 31 and 32 at 30 fps. Snapping first
        // is what stops a rounded frame LENGTH being added to a playhead that was never on the
        // grid: the result is frame 31 backwards and frame 33 forwards, both of them frame starts.
        val thirty = timebase(30)

        assertThat(thirty.addFrames(1_050_000L, -1L)).isEqualTo(1_033_333L)
        assertThat(thirty.addFrames(1_050_000L, 1L)).isEqualTo(1_100_000L)
    }

    @Test
    fun `a step back from the first frame stops at zero instead of wrapping`() {
        assertThat(timebase(30).addFrames(0L, -1L)).isEqualTo(0L)
        assertThat(Timebase.NTSC_29_97.addFrames(0L, -1L)).isEqualTo(0L)
    }

    // --- What a probe reports ----------------------------------------------

    @Test
    fun `a probe's rounded rate becomes the exact NTSC rational`() {
        // A probe reports a float, and 29.97 is 3e-5 from 30000/1001 — a tenth of a frame an hour,
        // and precisely the boundary the user cannot hit. Taking it at face value is the bug.
        assertThat(Timebase.fromFrameRate(29.97f)).isEqualTo(Timebase.NTSC_29_97)
        assertThat(Timebase.fromFrameRate(23.976f)).isEqualTo(Timebase.NTSC_23_976)
        assertThat(Timebase.fromFrameRate(59.94f)).isEqualTo(Timebase.NTSC_59_94)
    }

    @Test
    fun `a whole rate is itself, and a fractional one is thousandths reduced by their gcd`() {
        assertThat(Timebase.fromFrameRate(25f)).isEqualTo(Timebase(25, 1))
        assertThat(Timebase.fromFrameRate(30f)).isEqualTo(Timebase(30, 1))
        assertThat(Timebase.fromFrameRate(240f)).isEqualTo(Timebase(240, 1))
        // 47.952 is not within a hundredth of an NTSC preset, so it is 47952/1000 = 5994/125.
        assertThat(Timebase.fromFrameRate(47.952f)).isEqualTo(Timebase(5_994, 125))
    }

    @Test
    fun `a rate no probe could have meant falls back to 30 rather than throwing`() {
        // The same fallback the domain's DEFAULT_FRAME_RATE encodes: a clip whose rate is unknown
        // is still steppable, and a value that is not a rate at all must not take the editor down.
        assertThat(Timebase.fromFrameRate(0f)).isEqualTo(Timebase.DEFAULT)
        assertThat(Timebase.fromFrameRate(-1f)).isEqualTo(Timebase.DEFAULT)
        assertThat(Timebase.fromFrameRate(Float.NaN)).isEqualTo(Timebase.DEFAULT)
        assertThat(Timebase.fromFrameRate(Float.POSITIVE_INFINITY)).isEqualTo(Timebase.DEFAULT)
        // A billion frames a second is a probe's garbage, not a rate: fall back, do not throw.
        assertThat(Timebase.fromFrameRate(1e9f)).isEqualTo(Timebase.DEFAULT)
    }

    // --- What the user reads -----------------------------------------------

    @Test
    fun `the rate is named the way the user reads it, with trailing zeros trimmed`() {
        assertThat(timebase(30).frameRateLabel).isEqualTo("30 fps")
        assertThat(timebase(25).frameRateLabel).isEqualTo("25 fps")
        assertThat(Timebase.NTSC_29_97.frameRateLabel).isEqualTo("29.97 fps")
        assertThat(Timebase.NTSC_23_976.frameRateLabel).isEqualTo("23.976 fps")
    }

    @Test
    fun `the nominal rate is the real one rounded, and never zero`() {
        // The rate a TIMECODE counts with, which is why it is a whole number — and why
        // `formatTimecode` takes its HH:MM:SS from elapsed time instead of from this.
        assertThat(Timebase.NTSC_23_976.nominalFramesPerSecond).isEqualTo(24)
        assertThat(Timebase.NTSC_29_97.nominalFramesPerSecond).isEqualTo(30)
        assertThat(timebase(1, 10_000).nominalFramesPerSecond).isEqualTo(1)
    }

    // --- Guards ------------------------------------------------------------

    @Test
    fun `a rate that could not be a rate is refused rather than guessed`() {
        // A zero numerator or denominator would divide by zero in every conversion above, so the
        // constructor refuses rather than producing a timebase that fails somewhere far away.
        assertThrows(IllegalArgumentException::class.java) { timebase(0) }
        assertThrows(IllegalArgumentException::class.java) { timebase(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { timebase(240_001) }
        assertThrows(IllegalArgumentException::class.java) { timebase(30, 10_001) }
    }
}
