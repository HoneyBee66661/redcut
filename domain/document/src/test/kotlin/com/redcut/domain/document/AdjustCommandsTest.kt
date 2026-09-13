package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The Edit stage's clamps (FR-3.1–3.4, 3.9).
 *
 * The clamps are the point of these commands: the spec gives each property a range, and the range is
 * enforced HERE rather than in the slider that asked for the value. Every case below is a value a UI
 * could plausibly produce — a dragged slider overshooting, a restored project file from a version with
 * different limits, a division landing on 0 — and the claim in each is the same: the document keeps a
 * value the renderer can use.
 */
class AdjustCommandsTest {

    private val oneSecond = 1_000_000L

    private fun source() = SourceRef(
        id = "src-1",
        uri = "content://media/1",
        displayName = "clip.mp4",
        durationUs = 60 * oneSecond,
        width = 1920,
        height = 1080,
    )

    private fun clip(
        id: String = "clip-a",
        sourceInUs: Long = 0L,
        sourceOutUs: Long = 4 * oneSecond,
        speed: Float = 1f,
        volume: Float = 1f,
        muted: Boolean = false,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        reverse: Boolean = false,
    ) = Clip(
        id = id,
        sourceId = "src-1",
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        speed = speed,
        volume = volume,
        muted = muted,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        reverse = reverse,
    )

    private fun document(clip: Clip = clip()) = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(source()),
        tracks = listOf(videoTrack(clip)),
    )

    private fun Clip.after(command: EditCommand): Clip =
        command.apply(document(this)).clips.single()

    // --- Speed (FR-3.1) ----------------------------------------------------

    @Test
    fun `speed is clamped to the spec's range, not rejected`() {
        val base = clip()

        assertThat(base.after(SetSpeed(VIDEO, "clip-a", 0.1f)).speed).isEqualTo(ClipRanges.SPEED_MIN)
        assertThat(base.after(SetSpeed(VIDEO, "clip-a", 9f)).speed).isEqualTo(ClipRanges.SPEED_MAX)
        assertThat(base.after(SetSpeed(VIDEO, "clip-a", 1.5f)).speed).isEqualTo(1.5f)
    }

    @Test
    fun `a zero speed clamps instead of throwing, because Clip refuses a non-positive speed`() {
        // The interesting one: `Clip` has `require(speed > 0)`, so a UI that computed 0 (a division, a
        // slider at its floor) would crash the document rather than set a legal value.
        assertThat(clip().after(SetSpeed(VIDEO, "clip-a", 0f)).speed).isEqualTo(ClipRanges.SPEED_MIN)
        assertThat(clip().after(SetSpeed(VIDEO, "clip-a", -2f)).speed).isEqualTo(ClipRanges.SPEED_MIN)
    }

    @Test
    fun `a speed change re-lays the timeline, because duration is derived`() {
        // No command "moves" the clips after a speed change: positions are prefix sums of
        // `timelineDurationUs`, so doubling the speed halves the clip's footprint by itself. This is
        // the property §5.1 buys, and it is asserted here so a future refactor that stored positions
        // would fail a test rather than silently produce gaps.
        val fast = clip().after(SetSpeed(VIDEO, "clip-a", 2f))

        assertThat(fast.timelineDurationUs).isEqualTo(2 * oneSecond)
    }

    // --- Volume and mute (FR-3.2, 3.3) -------------------------------------

    @Test
    fun `volume is clamped to 0-200 percent`() {
        val base = clip(volume = 1f)

        assertThat(base.after(SetVolume(VIDEO, "clip-a", 2.5f)).volume).isEqualTo(ClipRanges.VOLUME_MAX)
        assertThat(base.after(SetVolume(VIDEO, "clip-a", -1f)).volume).isEqualTo(ClipRanges.VOLUME_MIN)
        assertThat(base.after(SetVolume(VIDEO, "clip-a", 0.4f)).volume).isEqualTo(0.4f)
    }

    @Test
    fun `muting leaves the volume alone, so unmuting restores the level`() {
        // Mute as its own flag rather than "volume = 0" — the difference between a toggle and an edit
        // that quietly loses the value the user set.
        val muted = clip(volume = 0.35f).after(SetMuted(VIDEO, "clip-a", true))

        assertThat(muted.muted).isTrue()
        assertThat(muted.volume).isEqualTo(0.35f)
        assertThat(muted.after(SetMuted(VIDEO, "clip-a", false)).volume).isEqualTo(0.35f)
    }

    // --- Fades (FR-3.4) ----------------------------------------------------

    @Test
    fun `a fade longer than the spec allows is clamped to three seconds`() {
        val faded = clip(sourceOutUs = 20 * oneSecond).after(SetFades(VIDEO, "clip-a", 9_000L, 9_000L))

        assertThat(faded.fadeInMs).isEqualTo(ClipRanges.FADE_MAX_MS)
        assertThat(faded.fadeOutMs).isEqualTo(ClipRanges.FADE_MAX_MS)
    }

    @Test
    fun `a fade longer than the CLIP is clamped to the clip`() {
        // A 2 s clip cannot fade for 2.5 s. Clamping to the clip's own length is what makes "fade the
        // whole clip" the natural maximum a user reaches by dragging to the end.
        val twoSeconds = clip(sourceOutUs = 2 * oneSecond)

        val faded = twoSeconds.after(SetFades(VIDEO, "clip-a", 9_000L, 1_500L))

        assertThat(faded.fadeInMs).isEqualTo(2_000L)
        assertThat(faded.fadeOutMs).isEqualTo(1_500L)
    }

    @Test
    fun `a fade on a sped-up clip is bounded by its TIMELINE length`() {
        // At 4x the clip is one second of timeline, so the fade ceiling is that second — not the four
        // seconds of source it reads, which is what a clamp against source duration would use.
        val fast = clip(
            sourceOutUs = 4 * oneSecond,
            speed = 4f,
        ).after(SetFades(VIDEO, "clip-a", 3_000L, 0L))

        assertThat(fast.timelineDurationUs).isEqualTo(oneSecond)
        assertThat(fast.fadeInMs).isEqualTo(1_000L)
    }

    @Test
    fun `a negative fade is zero, not a wrapped value`() {
        val faded = clip().after(SetFades(VIDEO, "clip-a", -500L, -500L))

        assertThat(faded.fadeInMs).isEqualTo(0L)
        assertThat(faded.fadeOutMs).isEqualTo(0L)
    }

    // --- Reverse (FR-3.9) --------------------------------------------------

    @Test
    fun `reversing flips which end of the source the clip starts at`() {
        // The cross-check that matters: `sourceTimeFor` is the single place direction lives, so the
        // preview, the filmstrip, the trim gesture and the frame-step all follow this flip without
        // knowing it happened.
        val forward = clip(sourceInUs = 2 * oneSecond, sourceOutUs = 6 * oneSecond)
        val backward = forward.after(SetReverse(VIDEO, "clip-a", true))

        assertThat(forward.sourceTimeFor(0L)).isEqualTo(2 * oneSecond)
        assertThat(backward.sourceTimeFor(0L)).isEqualTo(6 * oneSecond)
        assertThat(backward.sourceTimeFor(oneSecond)).isEqualTo(5 * oneSecond)
    }

    // --- The document's edge -----------------------------------------------

    @Test
    fun `a blank name is refused rather than stored`() {
        // A project with no name is a project the user cannot find in a list. "Call it nothing" keeps the
        // name it had.
        val doc = document()

        assertThat(RenameDocument("   ").apply(doc)).isEqualTo(doc)
        assertThat(RenameDocument("").apply(doc)).isEqualTo(doc)
    }

    @Test
    fun `renaming trims, and a name that is already the document's changes nothing`() {
        val doc = document()

        assertThat(RenameDocument("  untitled 2  ").apply(doc).name).isEqualTo("untitled 2")
        assertThat(RenameDocument(doc.name).apply(doc)).isEqualTo(doc)
    }

    @Test
    fun `every adjustment refuses an unknown clip without touching the document`() {
        val doc = document()

        val commands = listOf(
            SetSpeed(VIDEO, "nope", 2f),
            SetVolume(VIDEO, "nope", 1f),
            SetMuted(VIDEO, "nope", true),
            SetFades(VIDEO, "nope", 100L, 100L),
            SetReverse(VIDEO, "nope", true),
            SetTransform(VIDEO, "nope", TransformSpec(cropLeft = 0.1f, cropRight = 0.9f)),
        )

        commands.forEach { command ->
            assertThat(command.apply(doc)).isEqualTo(doc)
        }
    }

    @Test
    fun `SetTransform updates the clip transform in the document`() {
        val doc = document()
        val transform = TransformSpec(
            cropLeft = 0.2f,
            cropTop = 0.2f,
            cropRight = 0.8f,
            cropBottom = 0.8f,
        )

        val updated = SetTransform("clip-a", transform).apply(doc)
        assertThat(updated.clips.single().transform).isEqualTo(transform)

        // Identical transform leaves document unchanged
        assertThat(SetTransform("clip-a", transform).apply(updated)).isEqualTo(updated)
    }
}
