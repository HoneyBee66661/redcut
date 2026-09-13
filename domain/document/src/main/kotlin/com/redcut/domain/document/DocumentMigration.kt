package com.redcut.domain.document

/**
 * Schema v1 → v2: a flat clip list becomes one video track holding it.
 *
 * ### Why this is a function and not a line in the file codec
 *
 * The rule is about the DOCUMENT — "the clips that used to live directly on the document now live on a
 * video track" — and the codec's job is only to hand it the clips, because a v1 file says `clips` where
 * v2 says `tracks` and `ignoreUnknownKeys` would otherwise drop them without a word. Keeping the rule
 * here means the migration is testable in the fast tier, without a JSON literal and without a
 * filesystem, and that a future version 3 has one place to extend rather than one place per reader.
 *
 * ### The two conditions, and why both
 *
 * The version stamp decides whether a file is OLD, and the document's own clips decide whether there is
 * anything to move. Either one alone loses an edit: an old stamp on a document that already carries its
 * clips in a track would duplicate them, and a document with clips and a missing stamp (a file written
 * between the two formats) would lose them. Both are cheap to check, and a migration that is wrong once
 * loses work a user cannot redo.
 *
 * The stamp is brought forward either way: what comes out is a v2 document, and one that still said `1`
 * would be migrated again by the next reader.
 */
fun EditDocument.promotedFromV1(v1Clips: List<Clip>): EditDocument {
    if (schemaVersion >= SCHEMA_VERSION) return this
    val promoted = if (clips.isEmpty() && v1Clips.isNotEmpty()) {
        // Verbatim and in order. A migration that dropped a clip, sorted them, or re-derived anything
        // would be a migration that lost the user's edit — and the clips are the edit.
        copy(tracks = listOf(videoTrack(v1Clips)))
    } else {
        this
    }
    return promoted.copy(schemaVersion = SCHEMA_VERSION)
}
