package com.redcut.domain.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The timeline's own arithmetic once a lane can hold a gap.
 *
 * [sampleDocument] is gap-free on purpose — the command suites depend on its exact shape — so the gapped
 * documents are built here. What the suite is about is the difference a [Gap] makes: it is room the
 * timeline spends, so everything after it starts later than a sum over the clips alone would say.
 */
class TrackPositionsTest {

    /** A one-lane document whose items are written out in order, gaps and all. */
    private fun gappedDocument(vararg items: TrackItem): EditDocument = EditDocument(
        id = "doc-gaps",
        name = "Gaps",
        sources = listOf(source("s1")),
        tracks = listOf(Track(id = VIDEO, kind = TrackKind.VIDEO, items = items.toList())),
    )

    /** Two clips with a one-second hole between them: 2s of clip, 1s of room, 3s of clip. */
    private fun splitByGap(): EditDocument = gappedDocument(
        clip("c1", "s1", 0L, 2 * SEC),
        Gap(SEC),
        clip("c2", "s1", 2 * SEC, 5 * SEC),
    )

    /** Two lanes that each hold a gap: 2s then 1s of room, and 2s of room then 3s of clip. */
    private fun twoLanes(): EditDocument = EditDocument(
        id = "doc-two-lanes",
        name = "Two lanes",
        sources = listOf(source("s1")),
        tracks = listOf(
            Track(
                id = "track-video-1",
                kind = TrackKind.VIDEO,
                items = listOf(clip("c1", "s1", 0L, 2 * SEC), Gap(SEC)),
            ),
            Track(
                id = "track-video-2",
                kind = TrackKind.VIDEO,
                items = listOf(Gap(2 * SEC), clip("c2", "s1", 0L, 3 * SEC)),
            ),
        ),
    )

    @Test
    fun `a gap between two clips moves the clip that follows it`() {
        val doc = splitByGap()
        val lane = doc.tracks.first()

        assertEquals(listOf(0L, 3 * SEC), doc.timeline.map { it.startUs })
        assertEquals(listOf(0L, 3 * SEC), lane.positionedClips().map { it.startUs })
        assertEquals(6 * SEC, lane.contentEndUs)
        assertEquals(6 * SEC, doc.durationUs)
        assertEquals(6 * SEC, doc.timelineDurationUs)
    }

    @Test
    fun `a trailing gap is room the timeline has to show`() {
        val doc = gappedDocument(
            clip("c1", "s1", 0L, 2 * SEC),
            Gap(4 * SEC),
        )

        assertEquals(6 * SEC, doc.tracks.first().contentEndUs)
        assertEquals(6 * SEC, doc.durationUs)
        assertEquals(listOf(2 * SEC), doc.timeline.map { it.endUs })
        assertNull(doc.clipAt(5 * SEC))
    }

    @Test
    fun `a gap between clips shifts clipAt and timelineStartOf and offsetIntoClip`() {
        val doc = splitByGap()

        assertNull(doc.clipAt(2 * SEC + SEC / 2))
        assertEquals("c1", doc.clipAt(0L)?.id)
        assertEquals("c2", doc.clipAt(3 * SEC)?.id)
        assertEquals(3 * SEC, doc.timelineStartOf("c2")!!)
        assertEquals(SEC, doc.offsetIntoClip("c2", 4 * SEC)!!)
        assertNull(doc.offsetIntoClip("c2", 2 * SEC))
    }

    @Test
    fun `a track with no gaps answers exactly what the old prefix sum answered`() {
        val doc = sampleDocument()

        // The sum as it stood while it walked `clips` and counted nothing else.
        var cursor = 0L
        val oldStarts = doc.clips.map { clip ->
            val start = cursor
            cursor += clip.timelineDurationUs
            start
        }

        assertEquals(oldStarts, doc.timeline.map { it.startUs })
        assertEquals(oldStarts, doc.tracks.first().positionedClips().map { it.startUs })
        assertEquals(doc.clips.map { it.id }, doc.timeline.map { it.clip.id })
        assertEquals(doc.clips.indices.toList(), doc.timeline.map { it.index })
        assertEquals(cursor, doc.durationUs)
        assertEquals(cursor, doc.tracks.first().contentEndUs)
    }

    @Test
    fun `two tracks each with a gap are read flat with both gaps included`() {
        val doc = twoLanes()

        assertEquals(listOf(3 * SEC, 5 * SEC), doc.tracks.map { it.contentEndUs })
        // The lanes run in PARALLEL, so the timeline is as long as the longest of them, not as long
        // as the two added up: 8 s is the flat reading's own length, and it is not the video's.
        assertEquals(5 * SEC, doc.durationUs)
        assertEquals(listOf(0L, 5 * SEC), doc.timeline.map { it.startUs })
        assertEquals(listOf("c1", "c2"), doc.timeline.map { it.clip.id })
        // Flat, not side by side: the second lane's clips come after the first lane has finished, and
        // its own leading gap is counted in where they land.
        assertTrue(doc.timeline.last().startUs >= doc.tracks.first().contentEndUs) {
            "the flat reading places the second lane's clips after the first lane's contents"
        }
    }
}
