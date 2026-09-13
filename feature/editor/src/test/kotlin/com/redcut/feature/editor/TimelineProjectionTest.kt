package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.timelineDurationUs
import com.redcut.domain.document.videoTrack
import org.junit.Test

/**
 * The document-to-timeline mapping (spec §5.1, §7.1).
 *
 * One function, four cases, and the reason it is worth this much attention: it is the seam where
 * `:core:common`'s pure geometry meets the domain, and the ONLY thing that stops the timeline from
 * drawing the wrong widths is reading the right field here. `Clip` has two durations —
 * `sourceDurationUs` (what the file gives) and `timelineDurationUs` (what the timeline shows,
 * after speed) — and picking the wrong one produces a timeline that looks plausible and is wrong
 * for every speed-changed clip.
 *
 * These run in CI's `build` job; `:feature:editor` is an Android module.
 */
class TimelineProjectionTest {

    private val oneSecond = 1_000_000L

    private fun clip(
        id: String,
        sourceInUs: Long = 0L,
        sourceOutUs: Long = 4 * oneSecond,
        speed: Float = 1f,
    ) = Clip(
        id = id,
        sourceId = "src-$id",
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        speed = speed,
    )

    private fun document(vararg clips: Clip) = EditDocument(
        id = "doc",
        name = "Doc",
        tracks = listOf(videoTrack(clips.toList())),
    )

    @Test
    fun `a clip projects its TIMELINE duration, not its source duration`() {
        // 4 s of source at 2x is 2 s on the timeline: the timeline draws half the width. Reading
        // sourceDurationUs here would draw every sped-up clip twice as wide as it plays.
        val doubled = document(clip("a", sourceOutUs = 4 * oneSecond, speed = 2f))

        val timings = doubled.toClipTimings()

        assertThat(timings.map { it.timelineDurationUs }).containsExactly(2 * oneSecond)
    }

    @Test
    fun `timings keep document order, because the geometry derives starts from it`() {
        // spansOf prefix-sums this list, so a reordering here would silently rewrite every start
        // time on the timeline — the clips would swap places on screen with no command involved.
        val three = document(
            clip("first", sourceOutUs = oneSecond),
            clip("second", sourceOutUs = 2 * oneSecond),
            clip("third", sourceOutUs = 3 * oneSecond),
        )

        assertThat(three.toClipTimings().map { it.clipId })
            .containsExactly("first", "second", "third")
            .inOrder()
    }

    @Test
    fun `the timeline duration is the sum of the clip timings`() {
        // The arithmetic spelled out, because this is the number the ruler and the scroll extent
        // are built from and a sum that is merely "close" hides a wrong formula:
        //   a: 4 s of source at 1x            -> 4 s
        //   b: 4 s of source at 2x            -> 2 s
        //   c: 1 s..3 s of source at 1x       -> 2 s
        //                                       -----
        //                                         8 s
        val mixed = document(
            clip("a", sourceOutUs = 4 * oneSecond),
            clip("b", sourceOutUs = 4 * oneSecond, speed = 2f),
            clip("c", sourceInUs = oneSecond, sourceOutUs = 3 * oneSecond),
        )

        assertThat(mixed.toClipTimings().map { it.timelineDurationUs })
            .containsExactly(4 * oneSecond, 2 * oneSecond, 2 * oneSecond)
            .inOrder()
        assertThat(mixed.timelineDurationUs).isEqualTo(8 * oneSecond)
    }

    @Test
    fun `an empty document projects to nothing at all`() {
        // The timeline's first frame of a new project: no spans, no rects, no ruler — and no
        // crash, which is what a `last()` instead of a `lastOrNull()` would give.
        val empty = document()

        assertThat(empty.toClipTimings()).isEmpty()
        assertThat(empty.timelineDurationUs).isEqualTo(0L)
    }
}
