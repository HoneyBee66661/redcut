package com.redcut.domain.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetTrackLockedTest {
    @Test
    fun `locking a lane makes it refuse other commands`() {
        val locked = SAMPLE.after(SetTrackLocked(VIDEO, true))
        assertTrue(locked.trackById(VIDEO)!!.isLocked)

        // A mute on the locked lane is refused: identical document comes back.
        val mute = SetMuted(VIDEO, "c1", true)
        assertEquals(locked, locked.after(mute))
    }

    @Test
    fun `unlocking a locked lane is exempt from the gate`() {
        val locked = SAMPLE.after(SetTrackLocked(VIDEO, true))

        // The unlock command touches the locked lane but must NOT be refused by the gate.
        val unlocked = locked.after(SetTrackLocked(VIDEO, false))
        assertFalse(unlocked.trackById(VIDEO)!!.isLocked)

        // Now other commands work on the lane again.
        val edited = unlocked.after(SetMuted(VIDEO, "c1", true))
        assertTrue(edited.trackById(VIDEO)!!.clipById("c1")!!.muted)
    }

    @Test
    fun `locking is recorded in history and undo reverses it`() {
        val stack = UndoStack(SAMPLE)
        stack.execute(SetTrackLocked(VIDEO, true))
        assertTrue(stack.current.trackById(VIDEO)!!.isLocked)
        assertTrue(stack.canUndo)

        stack.undo()
        assertFalse(stack.current.trackById(VIDEO)!!.isLocked)
    }

    private companion object {
        const val VIDEO = "track-v1"
        val SAMPLE = EditDocument(
            id = "p1",
            name = "Project",
            tracks = listOf(
                Track(VIDEO, TrackKind.VIDEO, items = listOf(Clip("c1", "s1", 0L, 1000L))),
            ),
        )
    }
}
