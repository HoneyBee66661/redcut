package com.redcut.domain.document

/**
 * The commands that change the document's LANES, as opposed to what is inside them.
 *
 * A file of its own, the way `AdjustCommands.kt` holds the Edit stage's clip properties: every command
 * in `EditCommand.kt` is "one clip, on one lane", and its `trackId` is a PRECONDITION — the lane has to
 * be there already or the command is a no-op. A command that changes the set of lanes is the one edit
 * whose subject is the lane itself, and it is the only edit that can make a `trackId` valid where it
 * was not. Keeping it beside the clip commands would bury the one command with the opposite
 * precondition in the file that states the rule it is the exception to.
 */

/**
 * Adds a lane to the document (FR-1.6's music bed, and the first thing in the model that creates one).
 *
 * ### Why this has to be a command
 *
 * A lane is document structure, so creating one is an edit like any other: it has to be undoable,
 * comparable, and made through the one mutation gateway ([UndoStack] is the only thing allowed to
 * advance `revision`, spec §1.1). [planImport] therefore cannot reach for `EditDocument.copy(tracks =
 * …)` itself, and the internal writer it would otherwise use says why in its own KDoc — "Nothing is
 * created here: a lane is the document's own structure, and a command that invented one would be
 * editing a timeline the user cannot see". This is the command that is allowed to, by being the thing
 * the user's import asked for.
 *
 * ### Idempotent by id, which is what makes the seed safe to repeat
 *
 * A lane whose id the document already has leaves it alone. That single rule is what lets
 * [planImport] emit the seed without knowing whether the project has one: an import into a fresh
 * document creates the lane, and every later audio import emits the same command and watches it do
 * nothing. The alternative — the caller inspecting the document to decide — would put the answer to
 * "is this the first audio import?" in the UI, where it would be wrong exactly once, on the import
 * that a concurrent undo had just moved the ground under.
 *
 * ### The lane it takes is a whole [Track], and it is appended on top
 *
 * A value rather than a kind and an id, so a lane can be created with the sound state it should have
 * (a bed that starts at −6 dB is one command rather than two), and so this stays the same shape as
 * [AddSource], which likewise hands over the whole thing it is adding. It goes at the END of the lane
 * list, which is the top of the stack: lanes composite in list order, and the lane a user has just
 * created is the one they expect to see over the ones already there.
 *
 * ### Why it reports no lane
 *
 * [touchedTrackIds] is empty, and that is a claim rather than a default: a lane that does not exist
 * yet cannot be locked, and a lane that does exist makes this a no-op — so there is no lane whose
 * CONTENTS this command could disturb, which is the only thing the lock protects. Reporting the id
 * instead would be indistinguishable in behaviour and would claim an address this command never
 * writes to. It is the same answer [AddSource] gives for the same reason: it adds something the
 * timeline has not placed yet.
 */
data class AddTrack(val track: Track) : EditCommand {
    override val label: String get() = "Add track"

    override fun apply(doc: EditDocument): EditDocument =
        if (doc.trackById(track.id) != null) doc else doc.copy(tracks = doc.tracks + track)

    /** No lane: this creates one, and a lane that does not exist cannot be locked. See the type's KDoc. */
    override fun touchedTrackIds(document: EditDocument): Set<String> = emptySet()
}
