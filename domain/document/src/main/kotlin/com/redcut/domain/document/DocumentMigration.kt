package com.redcut.domain.document

/**
 * The document's own upgrade rules, one per format the app has written.
 *
 * ### Why these are functions and not lines in the file codec
 *
 * Each rule is about the DOCUMENT — "the clips that used to live directly on the document now live on a
 * video track", "a track's clips are now its items" — and the codec's job is only to hand it the keys the
 * current model no longer reads, because a v1 file says `clips` where v2 says `tracks` and
 * `ignoreUnknownKeys` would otherwise drop them without a word. Keeping the rules here means a migration
 * is testable in the fast tier, without a JSON literal and without a filesystem, and that a future
 * version has one place to extend rather than one place per reader.
 *
 * ### The order they have to run in
 *
 * Every rule finishes by bringing the version stamp forward, so whichever runs FIRST can make the next
 * one look at an up-to-date document and do nothing. They therefore run newest source version first —
 * [promotedFromV2], then [promotedFromV1] — which is what keeps each rule in front of the files it owns;
 * [promotedFromV2] additionally refuses anything not stamped exactly `2`, so a v1 document is left for the
 * rule that knows where v1 kept its clips. [promotedFromV1] is the exception that has to accept
 * everything below the tracks format, including a document with no stamp at all, because it is the only
 * rule that can.
 */

/**
 * Schema v2 → v3: a track's flat `clips` list becomes that track's [Track.items], in order.
 *
 * ### The two conditions, and why both
 *
 * The same two as [promotedFromV1]. The version stamp decides whether a file is OLD — and here it has to
 * say exactly `2`, because a v1 document belongs to [promotedFromV1] and stamping one v3 from here would
 * leave its clips invisible to the rule that knows where they are. The document's own content decides
 * whether there is anything to move: a v3 track has no `clips` field, so a v2 file's clips arrive
 * through the codec's own view of a v2 track while the document itself decodes with every lane empty, and
 * a document that is empty everywhere is the one that needs filling. Both are cheap to check, and a
 * migration that is wrong once loses work a user cannot redo.
 *
 * A v2 lane's OTHER attributes were never written — the version had none — so they come out at their
 * defaults, which is exactly what "the clip list moved and nothing else changed" means.
 *
 * The stamp is brought forward either way: what comes out is a v3 document, and one still saying `2`
 * would be migrated again by the next reader.
 */
fun EditDocument.promotedFromV2(v2Tracks: List<Track>): EditDocument {
    if (schemaVersion != V2_VERSION) return this
    val promoted = if (tracks.all { it.items.isEmpty() } && v2Tracks.isNotEmpty()) {
        // Verbatim and in order. A migration that dropped a clip, sorted them, or re-derived anything
        // would be a migration that lost the user's edit — and the clips are the edit.
        copy(tracks = v2Tracks)
    } else {
        this
    }
    return promoted.copy(schemaVersion = EditDocument.SCHEMA_VERSION)
}

/**
 * The version whose tracks held a flat clip list: the format [promotedFromV2] upgrades, and the highest
 * stamp that is still this rule's business.
 *
 * A band rather than "anything below the current version", and the difference matters: the codec reads a
 * v1 file's flat `clips` and a v2 file's per-track `clips` through DIFFERENT views, so a rule that
 * claimed both would stamp one of them v3 before the other rule saw it (see the file note on order).
 */
private const val V2_VERSION = 2

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
 * would be migrated again by the next reader. Since v3 that stamp is
 * [EditDocument.SCHEMA_VERSION], and the lane this builds holds its clips as items — the v1 path is the
 * same shape as the v2 one, reached from further back.
 */
fun EditDocument.promotedFromV1(v1Clips: List<Clip>): EditDocument {
    // `EditDocument.SCHEMA_VERSION`, not `SCHEMA_VERSION`: a companion object's members are in scope
    // inside the CLASS body, where the compiler has an implicit companion receiver to look through —
    // and an extension function has no such receiver, however obviously it is "about" EditDocument.
    // The bare name here would be a top-level one that does not exist.
    if (schemaVersion >= EditDocument.SCHEMA_VERSION) return this
    val promoted = if (clips.isEmpty() && v1Clips.isNotEmpty()) {
        // Verbatim and in order. A migration that dropped a clip, sorted them, or re-derived anything
        // would be a migration that lost the user's edit — and the clips are the edit.
        copy(tracks = listOf(videoTrack(v1Clips)))
    } else {
        this
    }
    return promoted.copy(schemaVersion = EditDocument.SCHEMA_VERSION)
}
