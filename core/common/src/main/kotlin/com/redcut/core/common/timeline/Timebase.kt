package com.redcut.core.common.timeline

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A frame rate as an exact rational, and the frame grid that follows from it.
 *
 * ### Why not a Float
 *
 * The document's unit is the microsecond, and every rate that matters is a rational: 29.97 IS
 * 30000/1001, which no Float can hold. Dividing a second by the float 29.97 gives a frame length of
 * 33 366.7 µs, and a step that adds a ROUNDED length to the playhead walks off the grid — step N
 * times and you are no longer on frame N, so "step to the cut" and "split here" disagree by a frame
 * or two, which is the one error frame-stepping exists to prevent (FR-2.9).
 *
 * So the rate is kept as the pair of integers a spec or a probe actually states, and every
 * conversion below is exact until the final microsecond, which is rounded once.
 *
 * ### What it is not
 *
 * Not a duration and not a position: a `Timebase` says how a time maps to a frame index, and
 * [snapUs] and [addFrames] are the only ways it moves one. Keyframes (WS K), cuts and export
 * timecode all read the same grid, which is the point of having one.
 */
data class Timebase(val numerator: Int, val denominator: Int = 1) {

    init {
        require(numerator in 1..MAX_NUMERATOR) {
            "numerator must be in 1..$MAX_NUMERATOR, was $numerator"
        }
        require(denominator in 1..MAX_DENOMINATOR) {
            "denominator must be in 1..$MAX_DENOMINATOR, was $denominator"
        }
    }

    /** Frames per second, as the real number the rational stands for. */
    private val framesPerSecond: Double get() = numerator.toDouble() / denominator

    /**
     * The rate a timecode COUNTS with: the real rate rounded to a whole number, and never zero.
     *
     * Rounded rather than truncated, because 23.976 counts 24 frames in a timecode second and 29.97
     * counts 30. It is a frame-COUNTING rate, not a source of elapsed time — [formatTimecode] says
     * why the two must not be confused.
     */
    val nominalFramesPerSecond: Int get() = framesPerSecond.roundToInt().coerceAtLeast(1)

    /** The rate as the user reads it: `30 fps`, `29.97 fps`, trailing zeros trimmed. */
    val frameRateLabel: String get() = "${rateText(framesPerSecond)} fps"

    /** The frame [timeUs] falls in, rounded to the nearest. */
    fun frameIndexAt(timeUs: Long): Long =
        divideRounded(timeUs * numerator.toLong(), denominator * MICROS_PER_SECOND)

    /** The last frame already begun at [timeUs]: the floor, for a range that must not reach. */
    fun frameIndexAtOrBefore(timeUs: Long): Long =
        divideFloor(timeUs * numerator.toLong(), denominator * MICROS_PER_SECOND)

    /** The first frame still running at [timeUs]: the ceiling, for one that must not fall short. */
    fun frameIndexAtOrAfter(timeUs: Long): Long =
        divideCeil(timeUs * numerator.toLong(), denominator * MICROS_PER_SECOND)

    /** When frame [frameIndex] begins, rounded to the nearest microsecond. */
    fun timeUsAt(frameIndex: Long): Long =
        divideRounded(frameIndex * denominator * MICROS_PER_SECOND, numerator.toLong())

    /** [timeUs] moved onto the grid: the start of the frame it falls in. */
    fun snapUs(timeUs: Long): Long = timeUsAt(frameIndexAt(timeUs))

    /**
     * [timeUs] moved [deltaFrames] frames along the grid, never before zero.
     *
     * The snap is what makes repeated steps exact: the frame INDEX moves, and is converted back
     * once: N steps from a frame boundary land on frame N however often repeated, rather than
     * accumulating the rounding of N separate frame lengths.
     */
    fun addFrames(timeUs: Long, deltaFrames: Long): Long =
        timeUsAt(frameIndexAt(timeUs) + deltaFrames).coerceAtLeast(0L)

    /**
     * The time as `HH:MM:SS:FF`.
     *
     * `HH:MM:SS` is REAL ELAPSED TIME, never `frameIndex / nominalFramesPerSecond`. At 23.976 or
     * 29.97 the nominal rate is a rounding of the real one, and the two readings part company by
     * about 3.6 seconds an hour: an hour of 23.976 material is 86 314 frames, which counting at a
     * nominal 24 fps reports as 00:59:57. That is a bug class rather than a style choice — the
     * timecode looks right on a short clip, is minutes out on a long one, and nothing else in the
     * app disagrees with it in time to catch it.
     *
     * The frame field is therefore the frame index WITHIN that second, clamped to
     * `0..nominalFramesPerSecond - 1`: an NTSC second holds fewer frames than the nominal rate,
     * so the clamp keeps the field one readable number instead of rolling into the next second.
     */
    fun formatTimecode(timeUs: Long): String {
        val elapsedSeconds = Math.floorDiv(timeUs, MICROS_PER_SECOND)
        val frame = (frameIndexAt(timeUs) - frameIndexAt(elapsedSeconds * MICROS_PER_SECOND))
            .coerceIn(0L, nominalFramesPerSecond - 1L)
        val hours = elapsedSeconds / SECONDS_PER_HOUR
        val minutes = (elapsedSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val seconds = elapsedSeconds % SECONDS_PER_MINUTE
        return listOf(hours, minutes, seconds, frame).joinToString(":") { twoDigits(it) }
    }

    companion object {

        /** 24 000/1001: `23.976`, the rate of film-sourced material prepared for NTSC. */
        val NTSC_23_976 = Timebase(24_000, 1_001)

        /** 30 000/1001: `29.97`, the rate the float 29.97 is a rounding of. */
        val NTSC_29_97 = Timebase(30_000, 1_001)

        /** 60 000/1001: `59.94`, 29.97 doubled, for 60p shot for NTSC. */
        val NTSC_59_94 = Timebase(60_000, 1_001)

        /**
         * 30 fps: what a probe that reported nothing usable falls back to, so a clip is never
         * unsteppable.
         *
         * The same 30 as the editor's own `DEFAULT_FRAME_RATE`, which cannot be imported here — the
         * dependency runs the other way, and :core:common may not know about :domain:document — so
         * the two are held equal by a test in the module that can see both, not by a comment.
         */
        val DEFAULT = Timebase(30)

        /** Tried in order; they do not overlap. */
        private val NTSC_PRESETS = listOf(NTSC_23_976, NTSC_29_97, NTSC_59_94)

        /**
         * The timebase a reported frame rate stands for.
         *
         * A rate within [NTSC_TOLERANCE] of a preset IS that preset, because a probe hands back a
         * rounded float: `29.97f` sits 3e-5 from 30000/1001, which is a tenth of a frame an hour —
         * exactly the boundary the user cannot hit. Any other rate is taken at the precision it was
         * given: whole rates exactly, the rest as thousandths reduced by their gcd. Anything
         * unusable (zero, negative, NaN, infinity, or a rate too extreme to hold) is [DEFAULT].
         */
        fun fromFrameRate(rate: Float): Timebase {
            if (!rate.isFinite() || rate <= 0f) return DEFAULT
            NTSC_PRESETS
                .firstOrNull { abs(rate.toDouble() - it.framesPerSecond) <= NTSC_TOLERANCE }
                ?.let { return it }

            val wholeRate = rate.roundToInt()
            if (rate == wholeRate.toFloat() && wholeRate in 1..MAX_NUMERATOR) {
                return Timebase(wholeRate, 1)
            }
            val scaled = (rate.toDouble() * RATE_SCALE).roundToLong()
            val divisor = greatestCommonDivisor(scaled, RATE_SCALE)
            val numerator = scaled / divisor
            if (numerator !in 1..MAX_NUMERATOR.toLong()) return DEFAULT
            return Timebase(numerator.toInt(), (RATE_SCALE / divisor).toInt())
        }
    }
}

/** Microseconds in a second: the unit the whole document is written in. */
private const val MICROS_PER_SECOND = 1_000_000L

/** A frame rate of 240 000 fps is the ceiling; below it, a rate is a rate. */
private const val MAX_NUMERATOR = 240_000

/** 1/10 000 of a frame per second is the finest a probe could mean. */
private const val MAX_DENOMINATOR = 10_000

/** The scale a fractional rate is kept at: thousandths, then reduced. */
private const val RATE_SCALE = 1_000L

/** Thousandths are written with three digits: `29.970`. */
private const val RATE_DECIMALS = 3

/**
 * How close to a preset a reported rate counts as that preset.
 *
 * 0.01 is wider than any probe's rounding and narrower than the gap to the neighbouring whole rates
 * — 24 fps is 0.024 from 23.976 and 30 fps is 0.03 from 29.97 — so a whole rate never lands here.
 */
private const val NTSC_TOLERANCE = 0.01

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Rounds half away from zero, which is what "the nearest frame" means to a viewer. */
private fun divideRounded(value: Long, divisor: Long): Long =
    if (value >= 0) (value + divisor / 2) / divisor else -((-value + divisor / 2) / divisor)

private fun divideFloor(value: Long, divisor: Long): Long = Math.floorDiv(value, divisor)

private fun divideCeil(value: Long, divisor: Long): Long = -Math.floorDiv(-value, divisor)

/** Euclid, on magnitudes: a scale and a rate share no factor they do not both have. */
private fun greatestCommonDivisor(a: Long, b: Long): Long {
    var x = abs(a)
    var y = abs(b)
    while (y != 0L) {
        val remainder = x % y
        x = y
        y = remainder
    }
    return x
}

/** The rate as text, trailing zeros trimmed: `30`, `29.97`, `23.976`. */
private fun rateText(framesPerSecond: Double): String {
    val thousandths = (framesPerSecond * RATE_SCALE).roundToLong()
    if (thousandths % RATE_SCALE == 0L) return (thousandths / RATE_SCALE).toString()
    val fraction = (thousandths % RATE_SCALE).toString().padStart(RATE_DECIMALS, '0').trimEnd('0')
    return "${thousandths / RATE_SCALE}.$fraction"
}

/** Two digits, and wider when an hour field runs past 99. Locale-proof by construction. */
private fun twoDigits(field: Long): String = field.toString().padStart(2, '0')
