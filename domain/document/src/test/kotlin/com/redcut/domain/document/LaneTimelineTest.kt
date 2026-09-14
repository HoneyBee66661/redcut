package com.redcut.domain.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The document's per-lane reading, and the length it implies.
 *
 * The flat reading laid the lanes end to end, which is the same thing as a one-lane document and a
 * lie about any other: two lanes play at once, so lane 2 starts at 0 like lane 1 and the project is
 * as long as its longest lane rather than as long as all of them added up. What this suite pins is
 * that difference, and the one number the change must not move — a one-lane document's duration.
 */
class LaneTimelineTest {

    /** A lane of [items], named so the reading can be checked against the track it came from. */
    private fun lane(id: String, vararg items: TrackItem): Track =
        Track(id = id, kind = TrackKind.VIDEO, items = items.toList())

    private fun documentWith(vararg tracks: Track): EditDocument = EditDocument(
        id = "doc-lanes",
        name = "Lanes",
        sources = listOf(source("s1")),
        tracks = tracks.toList(),
    )

    /** Two lanes with no gaps, 3 s and 5 s long: each starts at its own zero. */
    private fun twoLanesSideBySide(): EditDocument = documentWith(
        lane("track-video-1", clip("c1", "s1", 0L, 3 * SEC)),
        lane("track-video-2", clip("c2", "s1", 0L, 5 * SEC)),
    )

    /** Two lanes that each hold a gap: 2 s then 1 s of room, and 2 s of room then 3 s of clip. */
    private fun twoLanesWithGaps(): EditDocument = documentWith(
        lane("track-video-1", clip("c1", "s1", 0L, 2 * SEC), Gap(SEC)),
        lane("track-video-2", Gap(2 * SEC), clip("c2", "s1", 0L, 3 * SEC)),
    )

    @Test
    fun `two lanes read from their own zero, in document order`() {
        val doc = twoLanesSideBySide()

        assertEquals(listOf("track-video-1", "track-video-2"), doc.lanes.map { it.trackId })
        assertEquals(listOf("c1"), doc.lanes[0].slots.map { it.clip.id })
        assertEquals(listOf("c2"), doc.lanes[1].slots.map { it.clip.id })
        // The whole of the difference from the flat reading: lane 2 starts where IT starts.
        assertEquals(listOf(0L), doc.lanes[0].slots.map { it.startUs })
        assertEquals(listOf(0L), doc.lanes[1].slots.map { it.startUs })
        // The index is the lane's own too, so both lanes have their slot 0 -- two lanes, two zeroes.
        assertEquals(listOf(0, 0), doc.lanes.map { it.slots.single().index })
    }

    @Test
    fun `the flat reading still places the second lane after the first`() {
        val doc = twoLanesSideBySide()

        assertEquals(listOf(0L, 3 * SEC), doc.timeline.map { it.startUs })
        assertEquals(listOf(0, 1), doc.timeline.map { it.index })
        // Both readings hold the same clips; they disagree about when lane 2 plays, and that
        // disagreement is what a track id on the intent settles (WS C6).
        assertEquals(doc.clips.map { it.id }, doc.timeline.map { it.clip.id })
        assertEquals(
            doc.clips.map { it.id },
            doc.lanes.flatMap { one -> one.slots.map { it.clip.id } },
        )
    }

    @Test
    fun `a lane with a gap reads on its own clock, its gaps included`() {
        val doc = twoLanesWithGaps()

        assertEquals(listOf(0L), doc.lanes[0].slots.map { it.startUs })
        // Lane 2's own 2 s of room moves c2 by 2 s, inside lane 2 — nowhere near lane 1's extent.
        assertEquals(listOf(2 * SEC), doc.lanes[1].slots.map { it.startUs })
        assertEquals(listOf(3 * SEC, 5 * SEC), doc.lanes.map { it.durationUs })
    }

    @Test
    fun `the timeline is as long as its longest lane, not the sum of them`() {
        val doc = twoLanesWithGaps()

        assertEquals(
            8 * SEC,
            doc.tracks.sumOf { it.contentEndUs },
            "the sum of the lanes, which is the length the flat reading used to claim",
        )
        assertEquals(5 * SEC, doc.durationUs, "3 s and 5 s side by side are 5 s of video")
        assertEquals(5 * SEC, doc.timelineDurationUs)
    }

    @Test
    fun `a one lane document is as long as the flat reading always said`() {
        val sample = sampleDocument()
        val gapped = documentWith(lane(VIDEO, clip("c1", "s1", 0L, 2 * SEC), Gap(4 * SEC)))
        val fresh = EditDocument(id = "doc-new", name = "New")

        // The case that must not move, and it does not by construction rather than by coincidence:
        // the longest of one lane IS that lane, so the new reading and the old sum agree.
        assertEquals(sample.tracks.sumOf { it.contentEndUs }, sample.durationUs)
        assertEquals(5 * SEC, sample.durationUs, "the number the command suite already asserts")
        assertEquals(6 * SEC, gapped.durationUs, "a trailing gap is still room the timeline shows")
        assertEquals(0L, fresh.durationUs, "a fresh document is one empty lane, and no time long")
        assertEquals(sample.durationUs, sample.timeline.last().endUs)
    }

    @Test
    fun `an empty lane is a lane, and it carries no slots`() {
        val doc = documentWith(
            lane("track-video-1", clip("c1", "s1", 0L, 2 * SEC)),
            lane("track-video-2"),
        )

        // One entry per TRACK, empty ones included: dropping them would renumber the rest, and
        // lane 2's clips would be drawn as lane 1's.
        assertEquals(listOf("track-video-1", "track-video-2"), doc.lanes.map { it.trackId })
        assertTrue(doc.lanes[1].slots.isEmpty())
        assertEquals(0L, doc.lanes[1].durationUs)
        assertEquals(2 * SEC, doc.durationUs, "an empty lane cannot lengthen the timeline")
    }

    @Test
    fun `a document with no lanes is no time at all`() {
        val doc = EditDocument(id = "doc-audio", name = "Audio only", tracks = emptyList())

        assertTrue(doc.lanes.isEmpty())
        assertEquals(0L, doc.durationUs)
        assertEquals(0L, doc.timelineDurationUs)
    }
}
