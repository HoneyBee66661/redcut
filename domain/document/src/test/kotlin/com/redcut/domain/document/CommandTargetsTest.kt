package com.redcut.domain.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The audit for [EditCommand.touchedTrackIds] — and for the gate that reads it.
 *
 * ### Why this enumerates rather than lists
 *
 * A hand-written list of commands goes stale one command at a time, and it goes stale SILENTLY: the next
 * command is simply not checked, which is the failure this design exists to end. So the commands under
 * test are [EditCommand::class.sealedSubclasses] — what the compiler knows, not what this file remembers
 * — and [TARGETS] is compared against that BOTH ways: a command with no row fails, and a row naming a
 * command that no longer exists fails too.
 *
 * `sealed` is what makes the enumeration complete rather than merely current: an implementor has to live
 * in this module and this package, so no other module can add a command this file would not see.
 */
class CommandTargetsTest {

    @Test
    fun `the enumeration is real, and every command has a table row`() {
        val subclasses = EditCommand::class.sealedSubclasses
        // Asserted first: a reflection call that returned nothing would leave every test below green
        // while checking nothing at all.
        assertTrue(subclasses.isNotEmpty()) {
            "sealedSubclasses found no command, so this file is vacuous"
        }

        val enumerated = subclasses.map { it.java.simpleName }.sorted()
        assertEquals(TARGETS.keys.sorted(), enumerated) {
            "the table and the sealed hierarchy disagree: a command has no row, or a row names " +
                "a command that is gone"
        }
    }

    @Test
    fun `each command reports exactly the lanes its table row names`() {
        TARGETS.forEach { (name, expectation) ->
            assertEquals(expectation.lanes, expectation.command.touchedTrackIds(SAMPLE)) {
                "$name does not report the lanes it touches"
            }
        }
    }

    @Test
    fun `every lane the table names is a lane the fixture document has`() {
        TARGETS.forEach { (name, expectation) ->
            expectation.lanes.forEach { lane ->
                assertTrue(SAMPLE.trackById(lane) != null) {
                    "$name names the lane '$lane', which the fixture does not have"
                }
            }
        }
    }

    @Test
    fun `only the commands that touch no lane report an empty set`() {
        val laneFree = TARGETS.filterValues { it.lanes.isEmpty() }.keys.sorted()
        assertEquals(listOf("AddSource", "RenameDocument"), laneFree) {
            "reporting no lane is a claim about the model rather than a default: a new command " +
                "has to be listed here on purpose"
        }
    }

    @Test
    fun `the gate is indistinguishable from apply on an unlocked document`() {
        TARGETS.forEach { (name, expectation) ->
            assertEquals(
                expectation.command.apply(SAMPLE),
                SAMPLE.after(expectation.command),
            ) { "$name behaved differently under the lock gate" }
        }
    }

    @Test
    fun `a command that would touch a locked lane changes nothing and records nothing`() {
        TARGETS.filterValues { it.lanes.isNotEmpty() }.forEach { (name, expectation) ->
            val locked = lockLanes(SAMPLE, expectation.lanes)
            val stack = UndoStack(locked)
            // The WHOLE document, not just the clip: a refusal that quietly moved the revision would
            // still be a change, and the user would pay for it at the next recompile.
            val result = stack.execute(expectation.command)
            assertEquals(locked, result) { "$name edited a locked lane" }
            assertFalse(stack.canUndo) {
                "$name left an undo entry for an edit that never happened"
            }
        }
    }

    @Test
    fun `a refused gesture records nothing when it ends`() {
        TARGETS.filterValues { it.lanes.isNotEmpty() }.forEach { (name, expectation) ->
            val locked = lockLanes(SAMPLE, expectation.lanes)
            val stack = UndoStack(locked)
            stack.preview(expectation.command)
            assertEquals(locked, stack.commit()) { "$name's gesture edited a locked lane" }
            assertFalse(stack.canUndo) { "$name's gesture left an undo entry" }
        }
    }

    @Test
    fun `a lock on a lane a command does not touch does not stop it`() {
        val locked = lockLanes(SAMPLE, ON_VIDEO)
        TARGETS.filterValues { it.lanes.isEmpty() }.forEach { (name, expectation) ->
            val stack = UndoStack(locked)
            assertNotEquals(locked, stack.execute(expectation.command)) {
                "$name was refused by a lock on a lane it does not touch"
            }
            assertTrue(stack.canUndo) { "$name's edit was not recorded" }
        }
    }

    @Test
    fun `a compound is refused when any part would touch a locked lane`() {
        val twoLanes = SAMPLE.copy(tracks = SAMPLE.tracks + Track(SECOND_LANE, TrackKind.VIDEO))
        val compound = CompoundCommand(
            "Two lanes",
            listOf(
                AppendClip(VIDEO, "c8", "s1", 0L, SEC),
                AppendClip(SECOND_LANE, "c9", "s1", 0L, SEC),
            ),
        )
        // A compound carries no lane of its own, so its parts' lanes ARE its lanes. A check that read a
        // `trackId` field would find nothing here and let the whole thing through.
        assertEquals(setOf(VIDEO, SECOND_LANE), compound.touchedTrackIds(twoLanes))

        assertEquals(compound.apply(twoLanes), twoLanes.after(compound))
        // Refused whole: half an import applied is a state the user cannot undo their way out of.
        val locked = lockLanes(twoLanes, setOf(SECOND_LANE))
        assertEquals(locked, locked.after(compound))
    }

    /** [doc] with [lanes] locked, and every other lane left exactly as it was. */
    private fun lockLanes(doc: EditDocument, lanes: Set<String>): EditDocument = doc.copy(
        tracks = doc.tracks.map { if (it.id in lanes) it.copy(isLocked = true) else it },
    )

    /** A command, and the lanes the table says it addresses. */
    private data class Expectation(val command: EditCommand, val lanes: Set<String>)

    private companion object {
        /** A second lane, for the compound case: a union needs two lanes before it is a union. */
        const val SECOND_LANE = "track-video-second"

        /** The fixture's document: one lane, holding c1 (0s-2s) and c2 (2s-5s) over source s1. */
        val SAMPLE: EditDocument = sampleDocument()

        /** A point strictly inside c2, for the commands that address a point, not a range. */
        val MID_C2: Long = requireNotNull(SAMPLE.clipById("c2"))
            .let { (it.sourceInUs + it.sourceOutUs) / 2 }

        /**
         * The transform the fixture's clip already carries.
         *
         * Read off the clip rather than written out here: this table is about which LANES a command
         * names, and a `TransformSpec` spelled out in a test would be one more thing to keep in step
         * with the model, for nothing.
         */
        val FIXTURE_TRANSFORM = requireNotNull(SAMPLE.clipById("c1")).transform

        /** A command that addresses no lane: the two of them are named in the empty-set test. */
        val NO_LANES: Set<String> = emptySet()

        /** The one lane the fixture has. */
        val ON_VIDEO: Set<String> = setOf(VIDEO)

        /**
         * The audit, as a table: every command, and the lanes it reports for [SAMPLE].
         *
         * These are the sets as of this commit. A command whose `apply` starts touching a lane it did
         * not before has to change its row here, which is the diff a reviewer needs to see.
         */
        val TARGETS: Map<String, Expectation> = mapOf(
            "AddSource" to Expectation(AddSource(source("s2")), NO_LANES),
            "AppendClip" to Expectation(AppendClip(VIDEO, "c9", "s1", 0L, SEC), ON_VIDEO),
            "CompoundCommand" to Expectation(
                CompoundCommand(
                    "Add and trim",
                    listOf(
                        AppendClip(VIDEO, "c9", "s1", 0L, SEC),
                        TrimClip(VIDEO, "c1", 0L, SEC),
                    ),
                ),
                ON_VIDEO,
            ),
            "CutLeft" to Expectation(CutLeft(VIDEO, "c2", MID_C2), ON_VIDEO),
            "CutRight" to Expectation(CutRight(VIDEO, "c2", MID_C2), ON_VIDEO),
            "DeleteClip" to Expectation(DeleteClip(VIDEO, "c1"), ON_VIDEO),
            "DuplicateClip" to Expectation(DuplicateClip(VIDEO, "c1", "c9"), ON_VIDEO),
            "MergeClips" to Expectation(MergeClips(VIDEO, listOf("c1", "c2")), ON_VIDEO),
            "RenameDocument" to Expectation(RenameDocument("Renamed"), NO_LANES),
            "ReorderClip" to Expectation(ReorderClip(VIDEO, "c1", 1), ON_VIDEO),
            "SetFades" to Expectation(SetFades(VIDEO, "c1", SEC, 0L), ON_VIDEO),
            "SetMuted" to Expectation(SetMuted(VIDEO, "c1", true), ON_VIDEO),
            "SetReverse" to Expectation(SetReverse(VIDEO, "c1", true), ON_VIDEO),
            "SetSpeed" to Expectation(SetSpeed(VIDEO, "c1", ClipRanges.SPEED_MAX), ON_VIDEO),
            "SetTransform" to Expectation(SetTransform(VIDEO, "c1", FIXTURE_TRANSFORM), ON_VIDEO),
            "SetVolume" to Expectation(SetVolume(VIDEO, "c1", ClipRanges.VOLUME_MAX), ON_VIDEO),
            "SplitClip" to Expectation(SplitClip(VIDEO, "c2", MID_C2, "c3"), ON_VIDEO),
            "TrimClip" to Expectation(TrimClip(VIDEO, "c1", 0L, SEC), ON_VIDEO),
        )
    }
}
