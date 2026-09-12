package com.redcut.core.common

/**
 * Where a new entity id comes from.
 *
 * A one-method interface, injected rather than called directly, because ids are the one
 * part of building a command that cannot be pure. `EditCommand`s address clips and sources
 * by id and deliberately never mint their own (see the note in `EditCommand.kt`): a command
 * that generated an id could not be compared, replayed, or asserted on. The id therefore has
 * to come from the caller — and the caller is a view model that a JVM test wants to
 * exercise. Injecting the source is what lets a test ask for `id-1`, `id-2` and assert on
 * exact command sequences, while the app asks for UUIDs.
 */
fun interface IdSource {
    /** A fresh id. Implementations must not repeat one. */
    fun next(): String
}
