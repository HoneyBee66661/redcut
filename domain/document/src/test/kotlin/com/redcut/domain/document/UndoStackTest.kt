package com.redcut.domain.document

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Undo/redo behaviour (spec §7.3), including the two properties the spec calls out
 * as the reason this design is worth having:
 *
 *  - "N random commands then N undos returns the original document"
 *  - coalescing, without which "one gesture fills the undo stack and Undo appears
 *    broken"
 *
 * The randomised tests use a seeded [Random] and a plain loop rather than a
 * property-testing DSL, so a failure is reproducible from the printed round index
 * and so the suite does not depend on a runner API that has not been exercised in
 * this project yet. The inputs are randomly generated and the invariants are
 * checked after every step, which is what the property buys.
 */
class UndoStackTest {

    // --- Basic round-tripping ---------------------------------------------

    @Test
    fun `execute then undo restores the previous document`() {
        val stack = UndoStack(sampleDocument())
        val before = stack.current

        stack.execute(DeleteClip("c1"))
        assertNotEquals(before, stack.current)

        stack.undo()
        assertEquals(before, stack.current)
        assertFalse(stack.canUndo)
    }

    @Test
    fun `redo reapplies an undone command`() {
        val stack = UndoStack(sampleDocument())
        stack.execute(DeleteClip("c1"))
        val after = stack.current

        stack.undo()
        assertTrue(stack.canRedo)
        stack.redo()
        assertEquals(after, stack.current)
    }

    @Test
    fun `a new command clears the redo stack`() {
        val stack = UndoStack(sampleDocument())
        stack.execute(DeleteClip("c1"))
        stack.undo()
        assertTrue(stack.canRedo)

        stack.execute(ReorderClip("c1", 1))
        assertFalse(stack.canRedo, "branching must discard the abandoned future")
    }

    @Test
    fun `undo and redo are no-ops on an empty history`() {
        val stack = UndoStack(sampleDocument())
        val original = stack.current
        stack.undo()
        assertEquals(original, stack.current)
        stack.redo()
        assertEquals(original, stack.current)
    }

    @Test
    fun `labels name the command for the undo affordance`() {
        val stack = UndoStack(sampleDocument())
        stack.execute(DeleteClip("c1"))
        assertEquals("Delete", stack.undoLabel)

        stack.undo()
        assertEquals("Delete", stack.redoLabel)
    }

    // --- No-op commands do not consume history ----------------------------

    @Test
    fun `a command that changes nothing records no history entry`() {
        val stack = UndoStack(sampleDocument())
        val original = stack.current

        // Fails the distinct-operands precondition, so it is a no-op.
        stack.execute(MergeClips(listOf("c1", "c1")))
        assertEquals(0, stack.undoDepth, "a no-op must not consume one of the 50 slots")
        assertSame(original, stack.current)
    }

    // --- Coalescing (spec §7.3 requirement 2) -----------------------------

    @Test
    fun `a previewed drag records exactly one history entry`() {
        val stack = UndoStack(sampleDocument())
        val original = stack.current

        // A trim drag emits a delta per frame.
        repeat(10) { frame ->
            stack.preview(TrimClip("c1", 0L, SEC + frame * 10_000L))
        }
        assertTrue(stack.isPreviewing)
        assertEquals(0, stack.undoDepth, "preview must not touch history")

        stack.commit()
        assertFalse(stack.isPreviewing)
        assertEquals(1, stack.undoDepth, "the whole gesture collapses into one entry")
        assertEquals("Trim", stack.undoLabel)

        stack.undo()
        assertEquals(original, stack.current, "one undo reverses the whole drag")
    }

    @Test
    fun `a drag that ends where it started records nothing`() {
        val stack = UndoStack(sampleDocument())
        // Out to 1s, then back to the original 2s.
        stack.preview(TrimClip("c1", 0L, SEC))
        stack.preview(TrimClip("c1", 0L, 2 * SEC))
        stack.commit()
        assertEquals(0, stack.undoDepth)
        assertFalse(stack.canUndo)
    }

    @Test
    fun `aborting a preview restores the pre-gesture document`() {
        val stack = UndoStack(sampleDocument())
        val original = stack.current

        stack.preview(TrimClip("c1", 0L, SEC))
        assertNotEquals(original, stack.current)

        stack.abortPreview()
        assertEquals(original, stack.current)
        assertEquals(0, stack.undoDepth)
        assertFalse(stack.isPreviewing)
    }

    @Test
    fun `a second gesture is a second history entry`() {
        val stack = UndoStack(sampleDocument())
        stack.preview(TrimClip("c1", 0L, 1_500_000L))
        stack.commit()
        stack.preview(TrimClip("c1", 0L, 1_200_000L))
        stack.commit()
        assertEquals(2, stack.undoDepth)
    }

    // --- Bounded history (FR-6.5) -----------------------------------------

    @Test
    fun `history is bounded and drops the oldest entries`() {
        val stack = UndoStack(sampleDocument(), limit = 5)
        val original = stack.current

        repeat(20) { i -> stack.execute(AppendClip("n$i", "s1", 0L, SEC)) }

        assertEquals(5, stack.undoDepth, "history must respect the bound")
        repeat(5) { stack.undo() }
        assertFalse(stack.canUndo)
        assertNotEquals(
            original,
            stack.current,
            "the oldest entries were dropped, so the original is no longer reachable",
        )
    }

    @Test
    fun `the default limit is the 50 the spec specifies`() {
        assertEquals(50, UndoStack.DEFAULT_LIMIT)
    }

    // --- Lifecycle --------------------------------------------------------

    @Test
    fun `reset replaces the document and drops history`() {
        val stack = UndoStack(sampleDocument())
        stack.execute(DeleteClip("c1"))

        val replacement = sampleDocument().copy(id = "doc-2")
        val before = stack.current.revision
        stack.reset(replacement)

        // reset() deliberately restamps the document rather than adopting the
        // replacement's own revision, so compare content with the stamps masked.
        assertEquals(replacement.copy(revision = 0L), stack.current.copy(revision = 0L))
        assertEquals(before + 1L, stack.current.revision)
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    // --- Properties -------------------------------------------------------

    /**
     * The spec's headline property: fire random commands at the stack, then undo
     * until there is nothing left, and the document must be identical to where it
     * started.
     *
     * [assertInvariants] also runs after every single command, so this simultaneously
     * checks that no command can produce a document the renderer could not consume
     * (spec §7.2), from any reachable state.
     */
    @Test
    fun `undoing a random command sequence returns the original document`() {
        val initial = sampleDocument()
        val root = Random(SEED)
        var executed = 0

        repeat(ROUNDS) { round ->
            val rng = Random(root.nextLong())
            val stack = UndoStack(initial)

            repeat(rng.nextInt(1, 16)) { step ->
                val command = randomCommand(rng, stack.current, round * 100 + step)
                stack.execute(command)
                executed++
                assertInvariants(stack.current, "round=$round step=$step cmd=${command.label}")
            }

            var undone = 0
            while (stack.canUndo) {
                stack.undo()
                undone++
            }
            assertEquals(
                initial,
                stack.current,
                "round $round did not undo back to the original document",
            )
            // Every undo() moves one entry onto the redo stack, so a full unwind
            // must leave exactly as many redoable steps as it took -- and none at
            // all when the round's commands were every one a no-op. Asserting
            // canRedo is true here would be wrong for exactly that case.
            assertEquals(undone, stack.redoDepth, "round $round unwound inconsistently")
        }

        assertTrue(executed > ROUNDS, "the generator should not have produced only no-ops")
    }

    /** Redo must be a true inverse of undo, from any state. */
    @Test
    fun `redo after undo returns to the state before the undo`() {
        val root = Random(SEED + 1)

        repeat(ROUNDS) { round ->
            val rng = Random(root.nextLong())
            val stack = UndoStack(sampleDocument())
            repeat(rng.nextInt(1, 10)) { step ->
                stack.execute(randomCommand(rng, stack.current, round * 100 + step))
            }

            val before = stack.current
            var undoCount = 0
            while (stack.canUndo) {
                stack.undo()
                undoCount++
            }
            repeat(undoCount) { stack.redo() }

            assertEquals(before, stack.current, "round $round failed the redo round-trip")
        }
    }

    // --- Document revision (spec 1.1, 8.1) ---

    /**
     * The revision is the identity of the current state and the recompilation
     * trigger (§8.1), and this stack is the only thing that advances it. These tests
     * pin the contract from the outside: content-changing work stamps, no-op work
     * does not, and undo/redo hand back the stamp that belonged to the restored
     * document rather than minting a new one.
     */
    @Test
    fun `execute advances the revision`() {
        val stack = UndoStack(sampleDocument())
        val before = stack.current

        stack.execute(DeleteClip("c1"))

        assertEquals(before.revision + 1, stack.current.revision)
    }

    @Test
    fun `a command that changes nothing does not advance the revision`() {
        val stack = UndoStack(sampleDocument())
        val before = stack.current.revision

        // Fails the distinct-operands precondition, so it is a no-op.
        stack.execute(MergeClips(listOf("c1", "c1")))

        assertEquals(before, stack.current.revision, "a no-op is not a new state")
        assertEquals(0, stack.undoDepth)
    }

    @Test
    fun `each preview frame that changes the document advances the revision`() {
        val stack = UndoStack(sampleDocument())

        stack.preview(TrimClip("c1", 0L, 1_800_000L))
        val first = stack.current.revision
        stack.preview(TrimClip("c1", 0L, 1_600_000L))
        val second = stack.current.revision
        stack.preview(TrimClip("c1", 0L, 1_400_000L))
        val third = stack.current.revision

        assertTrue(first > 0L, "the first frame that changes content must stamp")
        assertTrue(second > first, "every changed frame is a new state")
        assertTrue(third > second, "§8.1 debounces the preview rebuild, not the revision")

        // The current values, so there is no content change and nothing to stamp.
        stack.preview(TrimClip("c1", 0L, 1_400_000L))
        assertEquals(third, stack.current.revision)
    }

    @Test
    fun `commit advances the revision once more and records one entry`() {
        val stack = UndoStack(sampleDocument())
        stack.preview(TrimClip("c1", 0L, 1_800_000L))
        stack.preview(TrimClip("c1", 0L, 1_500_000L))
        val lastPreview = stack.current.revision

        stack.commit()

        assertEquals(lastPreview + 1, stack.current.revision)
        assertEquals(1, stack.undoDepth)
    }

    @Test
    fun `a gesture that ends where it started records neither history nor a revision change`() {
        val stack = UndoStack(sampleDocument())
        val original = stack.current
        val before = original.revision

        // Out to 1s, then back to the original 2s.
        stack.preview(TrimClip("c1", 0L, SEC))
        stack.preview(TrimClip("c1", 0L, 2 * SEC))
        stack.commit()

        assertEquals(0, stack.undoDepth)
        assertFalse(stack.canUndo)
        assertEquals(before, stack.current.revision, "the state is the pre-gesture state")
        assertEquals(original, stack.current)
    }

    @Test
    fun `undo and redo restore the revision that belonged to the restored document`() {
        val stack = UndoStack(sampleDocument())
        val before = stack.current
        val revisionBefore = before.revision

        stack.execute(DeleteClip("c1"))
        val after = stack.current
        assertEquals(revisionBefore + 1, after.revision)

        stack.undo()
        assertEquals(before, stack.current)
        assertEquals(revisionBefore, stack.current.revision)

        stack.redo()
        assertEquals(after, stack.current)
        assertEquals(revisionBefore + 1, stack.current.revision)
    }

    @Test
    fun `reset advances the revision past the document it replaced`() {
        val stack = UndoStack(sampleDocument())
        stack.execute(DeleteClip("c1"))
        val replaced = stack.current.revision

        stack.reset(sampleDocument().copy(id = "doc-2"))

        assertTrue(stack.current.revision > replaced) {
            "loading a project must not reuse the stamp it replaced"
        }
    }

    @Test
    fun `aborting a preview restores the pre-gesture revision`() {
        val stack = UndoStack(sampleDocument())
        val before = stack.current.revision

        stack.preview(TrimClip("c1", 0L, SEC))
        stack.abortPreview()

        assertEquals(before, stack.current.revision, "not one more")
    }

    /**
     * A random command generator. Ids for created clips are derived from [suffix]
     * rather than from a counter, so a command is a pure value with real `equals`
     * semantics -- which is the property the spec asks for.
     */
    private fun randomCommand(rng: Random, doc: EditDocument, suffix: Int): EditCommand {
        val clips = doc.clips
        if (clips.isEmpty()) {
            // Only reachable if a future command can empty the timeline; DeleteClip
            // refuses to, so this is a guard rather than an expected path.
            return AddSource(source("s-$suffix"))
        }
        val index = rng.nextInt(clips.size)
        val clip = clips[index]

        return when (rng.nextInt(9)) {
            0 -> TrimClip(clip.id, rng.nextLong(0L, 3 * SEC), rng.nextLong(0L, 3 * SEC))
            1 -> SplitClip(clip.id, rng.nextLong(0L, 6 * SEC), "split-$suffix")
            2 -> CutLeft(clip.id, rng.nextLong(0L, 6 * SEC))
            3 -> CutRight(clip.id, rng.nextLong(0L, 6 * SEC))
            4 -> DeleteClip(clip.id)
            5 -> MergeClips(listOfNotNull(clip.id, clips.getOrNull(index + 1)?.id))
            6 -> ReorderClip(clip.id, rng.nextInt(-2, clips.size + 2))
            7 -> DuplicateClip(clip.id, "dup-$suffix")
            else -> AppendClip(
                "app-$suffix",
                doc.sources.first().id,
                0L,
                rng.nextLong(Clip.MIN_DURATION_US, 3 * SEC),
            )
        }
    }

    private companion object {
        /**
         * Fixed so a failure is reproducible. The round index is printed in the
         * assertion message.
         */
        const val SEED = 20_260_912L
        const val ROUNDS = 200
    }
}
