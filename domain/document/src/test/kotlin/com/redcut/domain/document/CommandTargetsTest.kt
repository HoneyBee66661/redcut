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
 * test come from the COMPILER — `EditCommand`'s Java sealed-type `PermittedSubclasses` attribute, the
 * list the compiler writes when it accepts an implementor — and [TARGETS] is compared against that
 * BOTH ways: a command with no row fails, and a row naming a command that no longer exists fails too.
 *
 * `sealed` is what makes the enumeration complete rather than merely current: an implementor has to live
 * in this module and this package, so no other module can add a command this file would not see.
 *
 * Why not `KClass.sealedSubclasses`: that call needs `kotlin-reflect`, which is not on this module's test
 * classpath, and CI compiles with `-Werror`, so the "reflection API not found in compilation classpath"
 * warning is a BUILD FAILURE there (it happened: run 34861176344, `:domain:document:compileTestKotlin`).
 * It looked green locally only because the local gate does not pass `-Predcut.warningsAsErrors=true`.
 * The class-file attribute answers the same question with no dependency and no warning.
 */
class CommandTargetsTest {

    @Test
    fun `the enumeration is real, and every command has a table row`() {
        val subclasses = requireNotNull(EditCommand::class.java.permittedSubclasses) {
            "EditCommand is not a sealed type, so this file would check nothing at all"
        }
        // Asserted first: a compiler that recorded no implementor would leave every test below green
        // while checking nothing at all.
        assertTrue(subclasses.isNotEmpty()) {
            "the compiler recorded no command, so this file is vacuous"
        }

        val enumerated = subclasses.map { it.simpleName }.sorted()
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
        // AddTrack is in this list as a CLAIM, not as a convenience: creating a lane is not touching
        // one. A lane that does not exist yet cannot be locked, and a lane that does makes the command
        // a no-op — so there is no lane whose CONTENTS it could disturb, and contents are the only
        // thing the lock protects. It is the same answer AddSource gives, for the same shape of reason.
        //
        // The three caption commands are the same claim about a different thing: the effect stack is not
        // a lane either (FR-4.3). A caption lives on the document, so there is no lane whose contents a
        // move or a retime could disturb — and reporting one would claim an address these commands never
        // write to.
        assertEquals(
            listOf(
                "AddSource",
                "AddTextOverlay",
                "AddTrack",
                "RenameDocument",
                "SetTextRange",
                "SetTextTransform",
            ),
            laneFree,
        ) {
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

        /**
         * The fixture's document: one lane, holding c1 (0s-2s) and c2 (2s-5s) over source s1, and one
         * caption on the effect stack.
         *
         * The caption is added with the command that adds one rather than written into `sampleDocument()`,
         * because that fixture is shared with every other suite in this module and a caption in it would
         * be a caption in all of them. It is here because two rows below CHANGE a caption — move it,
         * retime it — and `a lock on a lane a command does not touch does not stop it` requires every
         * lane-free command to actually change the sample. A command that no-ops on it would pass that
         * check by not being a command at all.
         */
        val SAMPLE: EditDocument = AddTextOverlay(
            effectId = TEXT_ID,
            spec = TextSpec("caption"),
            timeRange = TimeRange(0L, SEC),
        ).apply(sampleDocument())

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
        val FIXTURE_KEY = listOf(Keyframe(0L, 0.1f))

        /** The id the fixture's caption answers to, and the caption the two caption rows address. */
        const val TEXT_ID = "t1"

        /**
         * A caption box that is NOT the one the fixture's caption holds.
         *
         * Derived from the model's own default rather than spelled out as four floats, for the reason
         * [FIXTURE_TRANSFORM] is read off the clip: the numbers themselves are not what this table is
         * about. What it must be is DIFFERENT — a move to where the caption already is would be refused
         * by the command, and the row would then be checking nothing.
         */
        val MOVED_CAPTION_BOX = TextOverlayBox.DEFAULT.movedToCentre(0.5f, 0.2f).toTransform()

        /** A command that addresses no lane: the three of them are named in the empty-set test. */
        val NO_LANES: Set<String> = emptySet()

        /**
         * The lane the audio workstream's first import seeds (FR-1.6).
         *
         * Deliberately NOT a lane [SAMPLE] has, because the claim this row makes is that creating a lane
         * addresses no lane — and the case that claim has to survive is the one where the lane is not
         * there yet. It is also what makes `a lock on a lane a command does not touch does not stop it`
         * a real check for this command rather than a no-op that would pass either way.
         */
        val AUDIO_LANE: Track = Track(Track.AUDIO_ID, TrackKind.AUDIO)

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
            "AddTextOverlay" to Expectation(
                AddTextOverlay("t9", TextSpec("caption"), TimeRange(0L, SEC)),
                NO_LANES,
            ),
            "AddTrack" to Expectation(AddTrack(AUDIO_LANE), NO_LANES),
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
            "SetKeyframes" to Expectation(
                SetKeyframes(VIDEO, "c1", KeyframableProperty.CROP_LEFT, FIXTURE_KEY),
                ON_VIDEO,
            ),
            "SetMuted" to Expectation(SetMuted(VIDEO, "c1", true), ON_VIDEO),
            "SetReverse" to Expectation(SetReverse(VIDEO, "c1", true), ON_VIDEO),
            "SetSpeed" to Expectation(SetSpeed(VIDEO, "c1", ClipRanges.SPEED_MAX), ON_VIDEO),
            "SetTextRange" to Expectation(SetTextRange(TEXT_ID, 0L, 2 * SEC), NO_LANES),
            "SetTextTransform" to Expectation(
                SetTextTransform(TEXT_ID, MOVED_CAPTION_BOX),
                NO_LANES,
            ),
            "SetTransform" to Expectation(SetTransform(VIDEO, "c1", FIXTURE_TRANSFORM), ON_VIDEO),
            "SetVolume" to Expectation(SetVolume(VIDEO, "c1", ClipRanges.VOLUME_MAX), ON_VIDEO),
            "SplitClip" to Expectation(SplitClip(VIDEO, "c2", MID_C2, "c3"), ON_VIDEO),
            "TrimClip" to Expectation(TrimClip(VIDEO, "c1", 0L, SEC), ON_VIDEO),
        )
    }
}
