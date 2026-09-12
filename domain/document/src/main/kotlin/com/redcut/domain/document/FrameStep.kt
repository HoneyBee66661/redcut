package com.redcut.domain.document

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
 * The frame rate is the probe's, falling back to [DEFAULT_FRAME_RATE] when it reported nothing usable,
 * because a clip whose rate is unknown still has to be steppable.
 */
fun EditDocument.frameDurationUsAt(playheadUs: Long): Long? {
    val clip = clipAt(playheadUs) ?: return null
    val source = sources.firstOrNull { it.id == clip.sourceId }
    val sourceFrameRate = source?.frameRate?.takeIf { it > 0f } ?: DEFAULT_FRAME_RATE
    val sourceFrameUs = MICROS_PER_SECOND / sourceFrameRate
    val timelineFrameUs = (sourceFrameUs / clip.speed).roundToLong()
    // A very high frame rate or a very slow clip could round this to zero, which would make a step a
    // no-op the user cannot escape: one microsecond is not frame-accurate but it is not nothing.
    return timelineFrameUs.coerceAtLeast(1L)
}

/**
 * The playhead moved by one frame (FR-2.9), clamped to the timeline.
 *
 * Clamped rather than wrapped: stepping back from the first frame should stop at the start, not jump
 * to the end. A step that teleports is how a user loses their place in a long timeline.
 *
 * A document with no clip at the playhead steps by the default frame length and is then held by the
 * same clamp — so on an empty timeline the buttons are predictable (nothing happens, because there is
 * nowhere to step to) rather than a surprise jump of an invented frame.
 */
fun EditDocument.steppedPlayheadUs(playheadUs: Long, step: FrameStep): Long {
    val frameUs = frameDurationUsAt(playheadUs)
        ?: (MICROS_PER_SECOND / DEFAULT_FRAME_RATE).roundToLong().coerceAtLeast(1L)
    val moved = when (step) {
        FrameStep.BACK -> playheadUs - frameUs
        FrameStep.FORWARD -> playheadUs + frameUs
    }
    return moved.coerceIn(0L, timelineDurationUs)
}

/** The default when the probe reported no usable frame rate. 30 is the overwhelming common case. */
const val DEFAULT_FRAME_RATE = 30f

private const val MICROS_PER_SECOND = 1_000_000f
