package com.redcut.domain.document

/**
 * The Edit stage's clip properties (FR-3.1–3.4, 3.9).
 *
 * ### Why these are commands with clamps, and where the clamps live
 *
 * The model has carried `speed`, `volume`, `muted`, `fadeInMs`, `fadeOutMs` and `reverse` since Phase
 * 1.1 — the fields were never the missing part. What was missing is the same thing the Cut stage needed
 * after its commands existed: a way to CHANGE them that is comparable, undoable, and that refuses
 * nonsense in exactly one place.
 *
 * Every range here comes from the spec's FR-3 table (speed 0.25×–4.0×, volume 0–200 %, fades
 * 0–3000 ms), and the clamp is the COMMAND's job rather than the slider's. A UI that is the only thing
 * standing between a document and an impossible value is a UI whose bug becomes a broken document —
 * and the renderer would then be handed a clip it cannot draw. Clamping here means the worst a buggy
 * slider can do is ask for the nearest legal value.
 */
object ClipRanges {

    /** FR-3.1: 0.25× to 4.0×. */
    const val SPEED_MIN = 0.25f
    const val SPEED_MAX = 4f

    /** FR-3.2: 0 % to 200 %, stored as a fraction (1.0 = 100 %). */
    const val VOLUME_MIN = 0f
    const val VOLUME_MAX = 2f

    /** FR-3.4: up to three seconds, in milliseconds. */
    const val FADE_MAX_MS = 3_000L
}

/** FR-3.1: set a clip's playback speed. Affects its TIMELINE duration, so the ripple re-flows. */
data class SetSpeed(val clipId: String, val speed: Float) : EditCommand {
    override val label: String get() = "Speed"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.clipById(clipId) ?: return doc
        // Clamped, not rejected: a slider dragged past its end asks for the end, and `Clip` requires
        // speed > 0 (a zero or negative speed has no meaning in the timeline arithmetic).
        val clamped = speed.coerceIn(ClipRanges.SPEED_MIN, ClipRanges.SPEED_MAX)
        return doc.withClip(clip.copy(speed = clamped))
    }
}

/** FR-3.2: set a clip's volume, 0 %–200 %. */
data class SetVolume(val clipId: String, val volume: Float) : EditCommand {
    override val label: String get() = "Volume"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.clipById(clipId) ?: return doc
        val clamped = volume.coerceIn(ClipRanges.VOLUME_MIN, ClipRanges.VOLUME_MAX)
        return doc.withClip(clip.copy(volume = clamped))
    }
}

/**
 * FR-3.3: mute a clip, or unmute it.
 *
 * Mute is separate from volume rather than "volume = 0" on purpose: unmuting has to restore the level
 * the user had set, and a command that overwrote volume with zero would lose it — the difference
 * between a toggle and a destructive edit.
 */
data class SetMuted(val clipId: String, val muted: Boolean) : EditCommand {
    override val label: String get() = if (muted) "Mute" else "Unmute"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.clipById(clipId) ?: return doc
        return doc.withClip(clip.copy(muted = muted))
    }
}

/**
 * FR-3.4: fade in and/or fade out, in milliseconds.
 *
 * Each fade is bounded by BOTH the spec's ceiling (3 s) and the clip's own timeline length: a four
 * second fade on a two second clip is a request the renderer cannot honour, and clamping to the clip is
 * what makes "fade the whole clip" the natural maximum a user can reach by dragging. The two are
 * clamped independently — an overlap (in + out longer than the clip) is left to the renderer, which
 * holds one value per property and is the only place that knows how VideoCompositor blends them.
 */
data class SetFades(
    val clipId: String,
    val fadeInMs: Long,
    val fadeOutMs: Long,
) : EditCommand {
    override val label: String get() = "Fade"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.clipById(clipId) ?: return doc
        val clipMs = clip.timelineDurationUs / MICROS_PER_MILLI
        val ceiling = if (clipMs < ClipRanges.FADE_MAX_MS) clipMs else ClipRanges.FADE_MAX_MS
        return doc.withClip(
            clip.copy(
                fadeInMs = fadeInMs.coerceIn(0L, ceiling),
                fadeOutMs = fadeOutMs.coerceIn(0L, ceiling),
            ),
        )
    }
}

/**
 * FR-3.9: play a clip backwards.
 *
 * Nothing else moves: `Clip.sourceTimeFor` already folds `reverse` into the mapping, which is why the
 * preview, the filmstrip and the trim gesture all keep reading the right frame when this flips —
 * the property was designed to be the single place direction lives.
 */
data class SetReverse(val clipId: String, val reverse: Boolean) : EditCommand {
    override val label: String get() = if (reverse) "Reverse" else "Un-reverse"

    override fun apply(doc: EditDocument): EditDocument {
        val clip = doc.clipById(clipId) ?: return doc
        return doc.withClip(clip.copy(reverse = reverse))
    }
}

private const val MICROS_PER_MILLI = 1_000L
