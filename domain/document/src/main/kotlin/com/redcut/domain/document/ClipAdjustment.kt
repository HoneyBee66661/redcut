package com.redcut.domain.document

/**
 * The Edit stage's controls (FR-3.1–3.4, 3.9).
 *
 * ### Why the inspector needs a table and not five sliders
 *
 * The inspector (task 2.1) is a list of rows, and each row has to answer two questions about a clip:
 * what value is it showing, and what command does a change become? Asked in two places, those answers
 * drift — the classic version being a slider whose range disagrees with the command's clamp by a
 * decimal, so the value it displays is one the document will never hold. [currentValueOf] and [adjust]
 * are the two directions of ONE mapping, and the tests walk the round trip.
 *
 * ### The value is a Float for every control, deliberately
 *
 * A slider is a Float; mute and reverse are switches. Rather than two shapes with two call sites, the
 * switches use the same channel the sliders do (>= 0.5 is on), which keeps the inspector a single list
 * of rows and the ViewModel a single "begin/update/end" gesture. The conversion is stated here rather
 * than at each call site.
 */
enum class ClipAdjustment {

    /** FR-3.1: 0.25×–4.0×, clamped by [SetSpeed]. */
    SPEED,

    /** FR-3.2: 0 %–200 % as a fraction, clamped by [SetVolume]. */
    VOLUME,

    /** FR-3.4: milliseconds, clamped by [SetFades] against the spec ceiling and the clip's length. */
    FADE_IN,

    /** FR-3.4, the other end. */
    FADE_OUT,

    /** FR-3.3: the switch turns mute on above [SWITCH_THRESHOLD]. */
    MUTE,

    /** FR-3.9: the switch turns reverse on above [SWITCH_THRESHOLD]. */
    REVERSE,

    ;

    /**
     * Whether this control is a switch rather than a slider.
     *
     * The UI needs to know (a slider and a switch do not look alike), and it is a property of the
     * CONTROL rather than of the value, so it belongs next to the enum rather than in a `when` in the
     * inspector.
     */
    val isSwitch: Boolean get() = this == MUTE || this == REVERSE

    /** The step a slider moves in, in the control's own unit. */
    val step: Float
        get() = when (this) {
            SPEED, VOLUME -> SLIDER_STEP
            FADE_IN, FADE_OUT -> FADE_STEP_MS
            MUTE, REVERSE -> 1f
        }

    companion object {
        /** Anything at or above this counts as "on" for a switch. */
        const val SWITCH_THRESHOLD = 0.5f

        /**
         * 5 % per step for speed and volume.
         *
         * Coarse on purpose: a slider that moves by 1 % takes a hundred drags to cross its range, and
         * the value that matters to a user ("twice as fast", "half volume") is reachable in a few.
         */
        const val SLIDER_STEP = 0.05f

        /** 100 ms per step: fine enough for a fade, coarse enough to land on a round number. */
        const val FADE_STEP_MS = 100f
    }
}

/**
 * The clip's current value for [adjustment], or null when there is no such clip.
 *
 * Null rather than a default, because "no clip" and "a clip at 1.0×" are different answers: the first
 * is a control that should be disabled, the second is a control showing its clip's actual value.
 */
fun EditDocument.currentValueOf(clipId: String, adjustment: ClipAdjustment): Float? {
    val clip = clipById(clipId) ?: return null
    return when (adjustment) {
        ClipAdjustment.SPEED -> clip.speed
        ClipAdjustment.VOLUME -> clip.volume
        ClipAdjustment.FADE_IN -> clip.fadeInMs.toFloat()
        ClipAdjustment.FADE_OUT -> clip.fadeOutMs.toFloat()
        ClipAdjustment.MUTE -> if (clip.muted) 1f else 0f
        ClipAdjustment.REVERSE -> if (clip.reverse) 1f else 0f
    }
}

/**
 * The command that moves [adjustment] to [value], or null when there is no such clip.
 *
 * The fades are the reason this takes the DOCUMENT rather than a clip: setting one fade has to carry the
 * other one through, and a caller assembling `SetFades` itself would have to read, remember and re-pass
 * the value it did not mean to touch — three chances to lose the user's other fade.
 */
fun EditDocument.adjust(clipId: String, adjustment: ClipAdjustment, value: Float): EditCommand? {
    val clip = clipById(clipId) ?: return null
    return when (adjustment) {
        ClipAdjustment.SPEED -> SetSpeed(clipId, value)
        ClipAdjustment.VOLUME -> SetVolume(clipId, value)
        ClipAdjustment.FADE_IN -> SetFades(
            clipId,
            fadeInMs = value.toLong(),
            fadeOutMs = clip.fadeOutMs,
        )
        ClipAdjustment.FADE_OUT -> SetFades(
            clipId,
            fadeInMs = clip.fadeInMs,
            fadeOutMs = value.toLong(),
        )
        ClipAdjustment.MUTE -> SetMuted(clipId, value >= ClipAdjustment.SWITCH_THRESHOLD)
        ClipAdjustment.REVERSE -> SetReverse(clipId, value >= ClipAdjustment.SWITCH_THRESHOLD)
    }
}
