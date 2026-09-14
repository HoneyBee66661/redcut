package com.redcut.domain.document

import com.redcut.core.common.timeline.Timebase
import kotlin.math.roundToLong

/**
 * Which way a frame-step goes (FR-2.9).
 *
 * A type rather than a boolean or an Int, because `stepPlayhead(playhead, true)` at a call site says
 * nothing about which way "true" is, and a `±1` argument has the same problem with more rope.
 */
enum class FrameStep {
    BACK,
    FORWARD,
}

/**
 * How long one frame of the clip at the playhead lasts on the TIMELINE, or null when there is no clip
 * there.
 *
 * "On the timeline" is the whole subtlety. The probe reports the SOURCE frame rate, and a clip played
 * at 2x shows two source frames per timeline frame — so a frame-step computed from the source rate
 * would jump twice as far as the picture does, and FR-2.9 exists precisely to land on frame
 * boundaries ("needed for frame-accurate cuts"). Dividing by the clip's speed is also why this is a
 * function here rather than a constant in a screen.
 *
 * The length comes from the source's [Timebase] and not from a Float division, because the rate is
 * a rational and a rounded one is a grid the picture is not on: a 29.97 frame is 1001/30000 of a
 * second, which is 33 366.67 µs, and not the 33 366.7 µs that `1_000_000 / 29.97f` produces.
 *
 * The frame rate is the probe's, falling back to [DEFAULT_FRAME_RATE] when it reported nothing
 * usable, because a clip whose rate is unknown still has to be steppable.
 */
fun EditDocument.frameDurationUsAt(playheadUs: Long): Long? {
    val clip = clipAt(playheadUs) ?: return null
    val timelineFrameUs = (timebaseAt(playheadUs).timeUsAt(1) / clip.speed).roundToLong()
    // A very high frame rate or a very slow clip could round this to zero, which would make a step a
    // no-op the user cannot escape: one microsecond is not frame-accurate but it is not nothing.
    return timelineFrameUs.coerceAtLeast(1L)
}

/**
 * The playhead moved by one frame (FR-2.9), clamped to the timeline.
 *
 * At a speed of 1 the step is GRID-EXACT: the frame INDEX moves and is converted back once, so N
 * steps from a frame boundary land on frame N however many times they are repeated. Adding a
 * rounded frame LENGTH to the playhead instead is what let a 29.97 clip drift — a thousand steps
 * drifted 333 µs off frame 1000 — and a cut made after stepping could not be trusted to be where
 * the user saw it.
 *
 * Clamped rather than wrapped: stepping back from the first frame should stop at the start, not jump
 * to the end. A step that teleports is how a user loses their place in a long timeline.
 *
 * A document with no clip at the playhead steps by the default frame length and is then held by the
 * same clamp — so on an empty timeline the buttons are predictable (nothing happens, because there is
 * nowhere to step to) rather than a surprise jump of an invented frame.
 */
fun EditDocument.steppedPlayheadUs(playheadUs: Long, step: FrameStep): Long {
    val clip = clipAt(playheadUs)
    val delta = step.deltaFrames()
    val moved = if (clip == null || clip.speed == 1f) {
        timebaseAt(playheadUs).addFrames(playheadUs, delta)
    } else {
        // A sped-up clip shows `speed` source frames per timeline frame, so a timeline frame is a
        // FRACTION of a source frame and the source's grid no longer describes it. The rounded
        // frame length stands in, which is what it has always done.
        playheadUs + delta * (frameDurationUsAt(playheadUs) ?: DEFAULT_TIMEBASE.timeUsAt(1))
    }
    return moved.coerceIn(0L, timelineDurationUs)
}

/** The timebase at [playheadUs]: the source's own, or the default grid when there is none. */
private fun EditDocument.timebaseAt(playheadUs: Long): Timebase {
    val clip = clipAt(playheadUs) ?: return DEFAULT_TIMEBASE
    return sources.firstOrNull { it.id == clip.sourceId }?.timebase ?: DEFAULT_TIMEBASE
}

/** +1 forward, -1 back: the sign is the whole reason the type exists. */
private fun FrameStep.deltaFrames(): Long = when (this) {
    FrameStep.BACK -> -1L
    FrameStep.FORWARD -> 1L
}

/** The default when the probe reported no usable frame rate. 30 is the overwhelming common case. */
const val DEFAULT_FRAME_RATE = 30f

/** [DEFAULT_FRAME_RATE] as a grid, for a playhead with no source to ask. */
private val DEFAULT_TIMEBASE = Timebase.fromFrameRate(DEFAULT_FRAME_RATE)
