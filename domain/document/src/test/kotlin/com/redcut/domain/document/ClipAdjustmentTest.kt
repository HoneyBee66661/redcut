package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The inspector's two directions (FR-3.1–3.4, 3.9).
 *
 * The property that matters is the round trip: for every control and every clip, reading a value and
 * writing it straight back must produce a command that changes NOTHING. That is the claim the inspector
 * rests on — a slider that shows a value the document would refuse is a slider that fights the user, and
 * this is the cheapest way to catch it.
 */
class ClipAdjustmentTest {

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
        sourceInUs = 0L,
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
        clips = listOf(clip),
    )

    private val allAdjustments = ClipAdjustment.entries.toList()

    @Test
    fun `writing back the value a control is showing changes nothing`() {
        // The default clip: every control reads its value and writes it straight back.
        val doc = document()

        allAdjustments.forEach { adjustment ->
            val value = doc.currentValueOf("clip-a", adjustment)
            assertThat(value).isNotNull()
            val command = doc.adjust("clip-a", adjustment, value!!)
            assertThat(command).isNotNull()
            assertThat(command!!.apply(doc)).isEqualTo(doc)
        }
    }

    @Test
    fun `the round trip holds for a clip that has been edited`() {
        // The interesting version: a clip with non-default values everywhere, where a mapping that
        // hard-coded a default would pass the test above and fail this one.
        val doc = document(
            clip(
                speed = 2.5f,
                volume = 0.35f,
                muted = true,
                fadeInMs = 400L,
                fadeOutMs = 900L,
                reverse = true,
            ),
        )

        allAdjustments.forEach { adjustment ->
            val value = doc.currentValueOf("clip-a", adjustment)!!
            assertThat(doc.adjust("clip-a", adjustment, value)!!.apply(doc)).isEqualTo(doc)
        }
    }

    @Test
    fun `reading a control on a clip that is not there is null, not a default`() {
        val doc = document()

        allAdjustments.forEach { adjustment ->
            assertThat(doc.currentValueOf("nope", adjustment)).isNull()
            assertThat(doc.adjust("nope", adjustment, 1f)).isNull()
        }
    }

    @Test
    fun `setting one fade carries the other one through`() {
        // The reason `adjust` takes the document: a caller assembling SetFades itself has to remember the
        // fade it did NOT mean to touch, and forgetting loses the user's edit silently.
        val doc = document(clip(fadeInMs = 250L, fadeOutMs = 1_200L))

        val afterIn = doc.adjust("clip-a", ClipAdjustment.FADE_IN, 800f)!!.apply(doc)
        assertThat(afterIn.clips.single().fadeInMs).isEqualTo(800L)
        assertThat(afterIn.clips.single().fadeOutMs).isEqualTo(1_200L)

        val afterOut = afterIn.adjust("clip-a", ClipAdjustment.FADE_OUT, 300f)!!.apply(afterIn)
        assertThat(afterOut.clips.single().fadeInMs).isEqualTo(800L)
        assertThat(afterOut.clips.single().fadeOutMs).isEqualTo(300L)
    }

    @Test
    fun `a switch is on above the threshold and off below it`() {
        val doc = document(clip())

        assertThat(
            doc.adjust("clip-a", ClipAdjustment.MUTE, 0.4f)!!.apply(doc).clips.single().muted,
        ).isFalse()
        assertThat(
            doc.adjust("clip-a", ClipAdjustment.MUTE, 0.5f)!!.apply(doc).clips.single().muted,
        ).isTrue()
        assertThat(
            doc.adjust("clip-a", ClipAdjustment.REVERSE, 1f)!!.apply(doc).clips.single().reverse,
        ).isTrue()
    }

    @Test
    fun `the slider's values still meet the command's clamps, not the other way round`() {
        // A control asking for more than the document allows gets the clamped value — the slider is not
        // the enforcer (AdjustCommands is), and this pins that the inspector does not pretend otherwise.
        val doc = document(clip(sourceOutUs = 2 * oneSecond))

        val fast = doc.adjust("clip-a", ClipAdjustment.SPEED, 99f)!!.apply(doc)
        assertThat(fast.clips.single().speed).isEqualTo(ClipRanges.SPEED_MAX)

        val faded = doc.adjust("clip-a", ClipAdjustment.FADE_IN, 9_000f)!!.apply(doc)
        assertThat(faded.clips.single().fadeInMs).isEqualTo(2_000L)
    }

    @Test
    fun `a switch reports itself as one, and the sliders do not`() {
        assertThat(ClipAdjustment.MUTE.isSwitch).isTrue()
        assertThat(ClipAdjustment.REVERSE.isSwitch).isTrue()
        assertThat(ClipAdjustment.SPEED.isSwitch).isFalse()
        assertThat(ClipAdjustment.VOLUME.isSwitch).isFalse()

        // The inspector renders switch rows at 0/1 and slider rows in their own units, so a step of 1
        // for a switch is part of the same claim.
        assertThat(ClipAdjustment.MUTE.step).isEqualTo(1f)
        assertThat(ClipAdjustment.FADE_IN.step).isEqualTo(ClipAdjustment.FADE_STEP_MS)
    }
}
