package com.redcut.core.common.timeline

/**
 * How wide a second is, in pixels. The single knob zoom changes.
 *
 * Bounds are not arbitrary. [MAXIMUM] is set so that a frame at 60 fps is comfortably wider than
 * a pixel — trimming to a frame boundary (FR-2.9, FR-2.1) is only honest if the user can see the
 * frames they are landing on. [MINIMUM] is set so an HOUR-long project still fits a phone screen,
 * which is what "zoom out to see everything" has to mean for footage measured in hours.
 */
@JvmInline
value class TimelineZoom(val pixelsPerSecond: Float) {

    val isAtMinimum: Boolean get() = pixelsPerSecond <= MINIMUM.pixelsPerSecond
    val isAtMaximum: Boolean get() = pixelsPerSecond >= MAXIMUM.pixelsPerSecond

    /** A zoom the app can draw with: NaN and infinities from a pinch gesture do not survive. */
    fun clamped(): TimelineZoom =
        if (!pixelsPerSecond.isFinite()) DEFAULT else TimelineZoom(coerce())

    private fun coerce(): Float =
        pixelsPerSecond.coerceIn(MINIMUM.pixelsPerSecond, MAXIMUM.pixelsPerSecond)

    companion object {
        /**
         * 0.1 px/s: an HOUR is 360 px, so an hour-long project fits one phone screen.
         *
         * The user's word for this end was *"shrink max per 1 hr, incase video is hours long"*, and it
         * replaced the old 2 px/s ("a five-minute project is wider than a phone"). The old bound was chosen
         * for a five-minute project; a user with footage measured in hours could not zoom out far enough to
         * see it, which is the one thing zooming out is for.
         */
        val MINIMUM = TimelineZoom(0.1f)

        /** 8 px per 60 fps frame (16.67 ms) ≈ 480 px/s, so frame edges are visible. */
        val MAXIMUM = TimelineZoom(480f)

        /** Zoomed out far enough to see a whole short project. */
        val DEFAULT = TimelineZoom(60f)

        /** A pinch multiplies the current zoom; this is how much one gesture step may change it. */
        const val STEP_FACTOR = 1.5f
    }
}
