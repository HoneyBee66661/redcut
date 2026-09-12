package com.redcut.domain.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behaviour of the CUT-stage commands (FR-2), including the edge cases the spec
 * singles out as "the usual sources of subtle bugs".
 *
 * Every command must be *total*: a failed precondition returns the document
 * unchanged rather than throwing. Several tests below assert that by identity
 * (`assertSame`), which is stronger than equality -- it proves no copy was made
 * and therefore no history entry will be pushed by [UndoStack].
 */
class EditCommandTest {

    // --- Trim (FR-2.1) -----------------------------------------------------

    @Test
    fun `trim clamps the out point to the source duration`() {
        val doc = sampleDocument()
        val trimmed = TrimClip("c1", 0L, 99 * SEC).apply(doc)
        val clip = trimmed.clipById("c1")!!
        assertEquals(10 * SEC, clip.sourceOutUs, "out point should stop at the source end")
    }

    @Test
    fun `trim clamps to the minimum clip duration rather than producing a zero-length clip`() {
        val doc = sampleDocument()
        val trimmed = TrimClip("c1", 500_000L, 500_001L).apply(doc)
        val clip = trimmed.clipById("c1")!!
        assertTrue(clip.sourceDurationUs >= Clip.MIN_DURATION_US) {
            "trim collapsed the clip to ${clip.sourceDurationUs}us"
        }
    }

    @Test
    fun `trim never produces a negative in point`() {
        val doc = sampleDocument()
        val trimmed = TrimClip("c1", -5 * SEC, 1 * SEC).apply(doc)
        assertEquals(0L, trimmed.clipById("c1")!!.sourceInUs)
    }

    @Test
    fun `trim extends outward as well as shrinking`() {
        val doc = sampleDocument()
        val trimmed = TrimClip("c1", 0L, 4 * SEC).apply(doc)
        assertEquals(4 * SEC, trimmed.clipById("c1")!!.sourceOutUs)
    }

    @Test
    fun `trim is a no-op when the range is unchanged`() {
        val doc = sampleDocument()
        assertSame(doc, TrimClip("c1", 0L, 2 * SEC).apply(doc))
    }

    @Test
    fun `trim survives a source whose probe reported no duration`() {
        // A bad probe must not make the clip untrimmable.
        val doc = sampleDocument().let {
            it.copy(sources = listOf(source("s1", durationUs = 0L)))
        }
        val trimmed = TrimClip("c1", 0L, 2 * SEC).apply(doc)
        assertInvariants(trimmed, "zero-duration probe")
    }

    // --- Split (FR-2.5) ---------------------------------------------------

    @Test
    fun `split produces two contiguous clips covering the original range`() {
        val doc = sampleDocument()
        val split = SplitClip("c1", 1 * SEC, "c1b").apply(doc)
        val left = split.clipById("c1")!!
        val right = split.clipById("c1b")!!
        assertEquals(0L, left.sourceInUs)
        assertEquals(1 * SEC, left.sourceOutUs)
        assertEquals(1 * SEC, right.sourceInUs)
        assertEquals(2 * SEC, right.sourceOutUs)
        assertEquals(
            doc.durationUs,
            split.durationUs,
            "splitting must not change the timeline duration",
        )
    }

    @Test
    fun `split refuses a point that would leave a sub-minimum half`() {
        val doc = sampleDocument()
        assertSame(doc, SplitClip("c1", 50_000L, "c1b").apply(doc))
    }

    @Test
    fun `split refuses to reuse an existing clip id`() {
        val doc = sampleDocument()
        assertSame(doc, SplitClip("c1", 1 * SEC, "c2").apply(doc))
    }

    @Test
    fun `split outside the clip range is a no-op at both ends`() {
        // c1 is 0L..2*SEC. A split point outside that range has no halves to make,
        // and must return the document untouched rather than throw.
        val doc = sampleDocument()
        assertSame(doc, SplitClip("c1", 0L, "c1b").apply(doc), "at the in point")
        assertSame(doc, SplitClip("c1", 2 * SEC, "c1b").apply(doc), "at the out point")
    }

    // --- Cut left / right (FR-2.2, FR-2.3) --------------------------------

    @Test
    fun `cut left keeps the tail and preserves the clip identity`() {
        val doc = sampleDocument()
        val cut = CutLeft("c1", 1 * SEC).apply(doc)
        val clip = cut.clipById("c1")
        assertNotNull(clip, "the surviving tail must keep the original clip id")
        assertEquals(1 * SEC, clip!!.sourceInUs)
        assertEquals(2 * SEC, clip.sourceOutUs)
    }

    @Test
    fun `cut right keeps the head and preserves the clip identity`() {
        val doc = sampleDocument()
        val cut = CutRight("c2", 3 * SEC).apply(doc)
        val clip = cut.clipById("c2")
        assertNotNull(clip, "the surviving head must keep the original clip id")
        assertEquals(2 * SEC, clip!!.sourceInUs)
        assertEquals(3 * SEC, clip.sourceOutUs)
    }

    @Test
    fun `cut left at or before the in point changes nothing`() {
        val doc = sampleDocument()
        assertSame(doc, CutLeft("c1", 0L).apply(doc))
        assertSame(doc, CutLeft("c1", -1 * SEC).apply(doc))
    }

    @Test
    fun `cut right at or past the out point changes nothing`() {
        val doc = sampleDocument()
        assertSame(doc, CutRight("c2", 5 * SEC).apply(doc))
        assertSame(doc, CutRight("c2", 6 * SEC).apply(doc))
    }

    @Test
    fun `a cut that would leave a sub-minimum clip deletes it instead`() {
        // FR-2: "a clip that would go below it is deleted instead".
        val doc = sampleDocument()
        val cutLeft = CutLeft("c1", 2 * SEC - 1_000L).apply(doc)
        assertNull(cutLeft.clipById("c1"), "clip should have been deleted, not clamped")

        val cutRight = CutRight("c1", 1_000L).apply(doc)
        assertNull(cutRight.clipById("c1"), "clip should have been deleted, not clamped")
    }

    @Test
    fun `cut left is arithmetically equivalent to split then delete the head`() {
        // FR-2 requires one code path. CutLeft preserves the clip id by design (so
        // clip-scoped effects are not orphaned), so this compares the footage rather
        // than the ids -- see the CutLeft kdoc.
        val doc = sampleDocument()
        val at = 700_000L
        val viaCut = CutLeft("c1", at).apply(doc)
        val viaSplitDelete = DeleteClip("c1")
            .apply(SplitClip("c1", at, "scratch").apply(doc))

        assertEquals(
            viaSplitDelete.clips.map { it.sourceInUs to it.sourceOutUs },
            viaCut.clips.map { it.sourceInUs to it.sourceOutUs },
        )
    }

    @Test
    fun `cut right is arithmetically equivalent to split then delete the tail`() {
        val doc = sampleDocument()
        val at = 700_000L
        val viaCut = CutRight("c1", at).apply(doc)
        val viaSplitDelete = DeleteClip("scratch")
            .apply(SplitClip("c1", at, "scratch").apply(doc))

        assertEquals(
            viaSplitDelete.clips.map { it.sourceInUs to it.sourceOutUs },
            viaCut.clips.map { it.sourceInUs to it.sourceOutUs },
        )
    }

    // --- Delete (FR-2.6) --------------------------------------------------

    @Test
    fun `delete ripples the gap closed`() {
        val doc = sampleDocument()
        val after = DeleteClip("c1").apply(doc)
        assertEquals(doc.durationUs - 2 * SEC, after.durationUs)
        assertEquals(0L, after.timeline.first().startUs, "c2 should now start at zero")
    }

    @Test
    fun `delete drops effects scoped to the deleted clip`() {
        val doc = sampleDocument().let {
            it.copy(
                effects = listOf(
                    AppliedEffect.Adjust(
                        id = "e-clip",
                        scope = EffectScope.Clip("c1"),
                        timeRange = TimeRange(0L, SEC),
                        spec = ColorAdjustSpec(),
                    ),
                    AppliedEffect.Adjust(
                        id = "e-doc",
                        scope = EffectScope.Document,
                        timeRange = TimeRange(0L, SEC),
                        spec = ColorAdjustSpec(),
                    ),
                ),
            )
        }
        val after = DeleteClip("c1").apply(doc)
        assertEquals(listOf("e-doc"), after.effects.map { it.id })
        assertInvariants(after, "delete with scoped effects")
    }

    @Test
    fun `delete refuses to empty the timeline`() {
        val single = EditDocument(
            id = "d",
            name = "one",
            sources = listOf(source("s1")),
            clips = listOf(clip("only", "s1", 0L, SEC)),
        )
        assertSame(single, DeleteClip("only").apply(single))
    }

    // --- Merge (FR-2.4) ---------------------------------------------------

    @Test
    fun `merge joins source-adjacent clips into one`() {
        val doc = sampleDocument()
        val merged = MergeClips(listOf("c1", "c2")).apply(doc)
        assertEquals(1, merged.clips.size)
        assertEquals(0L, merged.clips[0].sourceInUs)
        assertEquals(5 * SEC, merged.clips[0].sourceOutUs)
        assertEquals(doc.durationUs, merged.durationUs, "merge is lossless")
    }

    @Test
    fun `merge refuses clips that are not contiguous in the source`() {
        // Merging these would silently drop the footage between 2s and 3s.
        val short = sampleDocument().let {
            it.copy(
                clips = listOf(
                    clip("a", "s1", 0L, 2 * SEC),
                    clip("b", "s1", 3 * SEC, 5 * SEC),
                ),
            )
        }
        assertSame(short, MergeClips(listOf("a", "b")).apply(short))
    }

    @Test
    fun `merge refuses clips from different sources`() {
        val twoSources = EditDocument(
            id = "d",
            name = "two sources",
            sources = listOf(source("s1"), source("s2")),
            clips = listOf(clip("a", "s1", 0L, 2 * SEC), clip("b", "s2", 2 * SEC, 4 * SEC)),
        )
        assertSame(twoSources, MergeClips(listOf("a", "b")).apply(twoSources))
    }

    @Test
    fun `merge refuses clips with different speeds`() {
        // Stricter than the spec text: merging different speeds would silently change
        // how much timeline one of them occupies.
        val mixed = sampleDocument().let {
            it.copy(
                clips = listOf(
                    clip("a", "s1", 0L, 2 * SEC),
                    clip("b", "s1", 2 * SEC, 4 * SEC, speed = 2f),
                ),
            )
        }
        assertSame(mixed, MergeClips(listOf("a", "b")).apply(mixed))
    }

    @Test
    fun `merge refuses clips with different reverse flags`() {
        // Same argument as the speed case above: merging clips that play in opposite
        // directions would silently reverse one of them, so the run is left alone.
        val mixed = sampleDocument().let {
            it.copy(
                clips = listOf(
                    clip("a", "s1", 0L, 2 * SEC),
                    clip("b", "s1", 2 * SEC, 4 * SEC, reverse = true),
                ),
            )
        }
        assertSame(mixed, MergeClips(listOf("a", "b")).apply(mixed))
    }

    @Test
    fun `merge refuses a run that is separated by another clip`() {
        val separated = sampleDocument().let {
            it.copy(
                clips = listOf(
                    clip("a", "s1", 0L, 2 * SEC),
                    clip("middle", "s1", 2 * SEC, 3 * SEC),
                    clip("b", "s1", 3 * SEC, 5 * SEC),
                ),
            )
        }
        assertSame(separated, MergeClips(listOf("a", "b")).apply(separated))
    }

    @Test
    fun `merge needs at least two distinct clips`() {
        val doc = sampleDocument()
        assertSame(doc, MergeClips(listOf("c1")).apply(doc))
        assertSame(doc, MergeClips(listOf("c1", "c1")).apply(doc))
        assertSame(doc, MergeClips(emptyList()).apply(doc))
    }

    // --- Reorder / Duplicate (FR-2.7, FR-2.8) -----------------------------

    @Test
    fun `reorder moves a clip and clamps an out-of-range index`() {
        // Clamping is symmetric, exactly as TrimClip's kdoc argues for drag-driven
        // commands: an index past the end clamps to lastIndex (99 -> 1) and a
        // negative index clamps to 0 (-5 -> 0). Neither end is a silent no-op.
        val doc = sampleDocument()
        assertEquals(listOf("c2", "c1"), ReorderClip("c1", 1).apply(doc).clips.map { it.id })
        assertEquals(listOf("c2", "c1"), ReorderClip("c1", 99).apply(doc).clips.map { it.id })
        assertEquals(listOf("c2", "c1"), ReorderClip("c2", -5).apply(doc).clips.map { it.id })
    }

    @Test
    fun `duplicate inserts the copy immediately after the original`() {
        val doc = sampleDocument()
        val after = DuplicateClip("c1", "c1copy").apply(doc)
        assertEquals(listOf("c1", "c1copy", "c2"), after.clips.map { it.id })
        assertInvariants(after, "duplicate")
    }

    @Test
    fun `duplicate refuses to reuse an existing id`() {
        val doc = sampleDocument()
        assertSame(doc, DuplicateClip("c1", "c2").apply(doc))
    }

    // --- Totality ---------------------------------------------------------

    @Test
    fun `every command is a no-op on an unknown clip id`() {
        val doc = sampleDocument()
        val unknown = "does-not-exist"
        assertSame(doc, TrimClip(unknown, 0L, SEC).apply(doc))
        assertSame(doc, SplitClip(unknown, SEC, "n1").apply(doc))
        assertSame(doc, CutLeft(unknown, SEC).apply(doc))
        assertSame(doc, CutRight(unknown, SEC).apply(doc))
        assertSame(doc, DeleteClip(unknown).apply(doc))
        assertSame(doc, MergeClips(listOf(unknown, "c1")).apply(doc))
        assertSame(doc, ReorderClip(unknown, 0).apply(doc))
        assertSame(doc, DuplicateClip(unknown, "n2").apply(doc))
    }

    @Test
    fun `append requires a live source and a fresh id`() {
        val doc = sampleDocument()
        assertSame(doc, AppendClip("new", "missing-source", 0L, SEC).apply(doc))
        assertSame(doc, AppendClip("c1", "s1", 0L, SEC).apply(doc))
        assertSame(doc, AppendClip("new", "s1", 0L, 50_000L).apply(doc))

        val appended = AppendClip("new", "s1", 0L, SEC).apply(doc)
        assertEquals(listOf("c1", "c2", "new"), appended.clips.map { it.id })
    }

    // --- Comparability and naming (spec §7.3) ---

    @Test
    fun `commands are named comparable values`() {
        // §7.3 requirement 1: named commands with equals and no opaque
        // (List<EditOperation>) -> List<EditOperation> escape hatch. Everything here
        // is asserted through the public surface only -- equals, hashCode, label and
        // apply -- so it holds for any implementation that keeps that promise rather
        // than only for today's data classes.

        // (a) Equality and hashCode are structural.
        assertEquals(TrimClip("c1", 0L, SEC), TrimClip("c1", 0L, SEC))
        assertEquals(
            TrimClip("c1", 0L, SEC).hashCode(),
            TrimClip("c1", 0L, SEC).hashCode(),
            "equal commands must hash alike, or they cannot key a log or a command set",
        )
        assertNotEquals(
            TrimClip("c1", 0L, SEC),
            TrimClip("c1", 0L, 2 * SEC),
            "commands differing in an argument must not compare equal",
        )

        // (b) Applying two equal commands yields equal documents: a command is a pure
        // value, so it cannot carry hidden state that makes the second apply differ.
        // The command is chosen to actually change the document, so the equality
        // below cannot pass merely because both applies were no-ops.
        val doc = sampleDocument()
        val first = TrimClip("c1", 0L, 3 * SEC)
        val second = TrimClip("c1", 0L, 3 * SEC)
        assertNotEquals(doc, first.apply(doc), "this command must actually change the document")
        assertEquals(first.apply(doc), second.apply(doc))

        // (c) Every command type carries a label: this is what "Undo <label>" shows.
        val everyCommandType = listOf<EditCommand>(
            AddSource(source("s1")),
            AppendClip("new", "s1", 0L, SEC),
            TrimClip("c1", 0L, SEC),
            SplitClip("c1", SEC, "c1b"),
            CutLeft("c1", SEC),
            CutRight("c1", SEC),
            DeleteClip("c1"),
            MergeClips(listOf("c1", "c2")),
            ReorderClip("c1", 1),
            DuplicateClip("c1", "c1b"),
        )
        assertEquals(10, everyCommandType.size, "one instance per command type")
        everyCommandType.forEach { command ->
            assertTrue(command.label.isNotBlank()) {
                "${command::class.simpleName} has a blank label, so \"Undo \" would show nothing"
            }
        }
    }
}
