package com.redcut.domain.render

import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.TimeRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The graph a one-track, video-only project compiles to, pinned value by value.
 *
 * The compiler places its own layers — it ripples its `compiledSlots` closed, and its KDoc says the
 * document's timeline is never consulted for placement — and this suite exists to keep that true
 * while the DOCUMENT gains a per-lane reading ([EditDocument.lanes]). Nothing about that reading
 * may move a rendered frame, so the fixture's layers, their times, their sources and the graph's
 * length are written out as literals here rather than read back off the compiler's output.
 *
 * The second test is the one a change to the compiler would actually break. For a project whose
 * clips are all live, the document's flat reading and the compiled ripple agree, so a compiler that
 * started consulting [EditDocument.timeline] would still pass the first golden. A disabled clip
 * makes the two readings disagree by exactly the room the document holds open and the graph closes,
 * and the golden pins the closed one.
 */
class TimelineCompilerGoldenTest {

    /**
     * Three clips whose SOURCE ranges deliberately do not match their timeline ranges — 0–2, 5–7
     * and 8–10 of the source become 0–2, 2–4 and 4–6 of the video — so a graph that confused the
     * trim with the placement fails the golden instead of passing by coincidence.
     */
    private fun videoOnlyProject(): EditDocument = document(
        clips = listOf(
            clip("c1", "s1", 0L, 2 * SEC),
            clip("c2", "s1", 5 * SEC, 7 * SEC),
            clip("c3", "s1", 8 * SEC, 10 * SEC),
        ),
    )

    @Test
    fun `a one track video project compiles to the graph the fixture describes`() {
        val doc = videoOnlyProject()
        val graph = TimelineCompiler.compile(doc)
        val placed = listOf(
            TimeRange(0L, 2 * SEC),
            TimeRange(2 * SEC, 4 * SEC),
            TimeRange(4 * SEC, 6 * SEC),
        )
        val trimmed = listOf(
            TimeRange(0L, 2 * SEC),
            TimeRange(5 * SEC, 7 * SEC),
            TimeRange(8 * SEC, 10 * SEC),
        )

        assertEquals(listOf("c1", "c2", "c3"), graph.videoLayers.map { it.clipId })
        assertEquals(placed, graph.videoLayers.map { it.timeRange }, "laid from zero, end to end")
        assertEquals(
            trimmed,
            graph.videoLayers.map { it.sourceRange },
            "the source range is the trim, the time range is where it lands",
        )
        assertEquals(List(3) { doc.sources.single() }, graph.videoLayers.map { it.source })
        assertEquals(6 * SEC, graph.durationUs, "the end of the last layer")
        assertTrue(graph.transitions.isEmpty())
        assertTrue(graph.overlayLayers.isEmpty())
        assertGraphInvariants(graph)
    }

    @Test
    fun `a disabled clip is dropped from the graph and holds no room open in it`() {
        val doc = document(
            clips = listOf(
                clip("c1", "s1", 0L, 2 * SEC),
                clip("c2", "s1", 5 * SEC, 7 * SEC, enabled = false),
                clip("c3", "s1", 8 * SEC, 10 * SEC),
            ),
        )
        val graph = TimelineCompiler.compile(doc)

        // The document keeps the disabled clip's room — that is what its flat reading is — and the
        // graph ripples it closed. These two numbers are why placement cannot come from there.
        assertEquals(4 * SEC, doc.timeline.first { it.clip.id == "c3" }.startUs)
        assertEquals(TimeRange(0L, 2 * SEC), videoLayer(graph, "c1").timeRange)
        assertEquals(TimeRange(2 * SEC, 4 * SEC), videoLayer(graph, "c3").timeRange)
        assertEquals(listOf("c1", "c3"), graph.videoLayers.map { it.clipId })
        assertEquals(4 * SEC, graph.durationUs)
        assertGraphInvariants(graph)
    }
}
