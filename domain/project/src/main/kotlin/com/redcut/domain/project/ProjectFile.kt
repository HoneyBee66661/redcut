package com.redcut.domain.project

import com.redcut.domain.document.EditDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A saved project: the document, plus what the user calls it.
 *
 * ### Why the name is stored rather than derived
 *
 * The name IS the user's handle on the work — the thing they will look for in a list — so it is data, not
 * a label computed from an id. It is also what the naming rule below operates on: two projects must not
 * share one, or "open untitled 2" stops meaning anything.
 *
 * [updatedAtMs] is part of the file rather than the filesystem's timestamp: the latest-project lookup has
 * to survive being copied to another device or restored from a backup, and a filesystem mtime does not.
 */
@Serializable
data class SavedProject(
    val id: String,
    val name: String,
    val document: EditDocument,
    val updatedAtMs: Long,
)

/**
 * The project repository port (spec §4.1's dependency rule: features depend on this, and never on the
 * implementation, which needs a `Context` and therefore lives in `:app`).
 *
 * Three methods, because three are what the editor needs today: save the working project, load the one
 * the user was last in, and read the names in use so a new project can be named without colliding. A
 * full project LIST is Phase 4's screen, and inventing its shape now would be inventing product
 * decisions in an infrastructure commit.
 */
interface ProjectStore {

    /** Writes [project], replacing any project with the same id. */
    suspend fun save(project: SavedProject)

    /** The most recently saved project, or null when there is none. */
    suspend fun latest(): SavedProject?

    /**
     * Every saved project as a summary, in NO particular order (see [summariesNewestFirst] for the order a
     * gallery wants).
     *
     * Summaries rather than projects, because the home screen draws tiles and a tile is a name, a count and
     * a timestamp: decoding every clip of every project to draw it is work that grows with the library
     * instead of with the screen.
     */
    suspend fun summaries(): List<ProjectSummary>

    /** Every saved project's name, for [nextUntitledName]. */
    suspend fun savedNames(): List<String>
}

/** The word the user's first unnamed project gets, and the stem of the rest. */
const val UNTITLED_BASE_NAME = "untitled"

/**
 * The name a new project gets: "untitled", then "untitled 2", "untitled 3", …
 *
 * ### The rule, and the two things it deliberately is not
 *
 * It fills the LOWEST free number rather than counting projects: delete "untitled 2" and the next new
 * project takes that name, because the name exists to identify a project, not to record how many there
 * have been. And it compares case- and whitespace-insensitively, because "Untitled" and "untitled " are
 * the same name to a user reading a list, and a rule that did not know that would cheerfully create a
 * second one.
 *
 * The numbering starts at 2 (not 1) because the first project is plain "untitled": "untitled 1" reads as
 * a count that is already out of step with its own name.
 */
fun nextUntitledName(existing: List<String>): String {
    val taken = existing.map { it.trim().lowercase() }.toSet()
    if (UNTITLED_BASE_NAME !in taken) return UNTITLED_BASE_NAME
    var number = FIRST_NUMBERED
    while ("$UNTITLED_BASE_NAME $number" in taken) number++
    return "$UNTITLED_BASE_NAME $number"
}

private const val FIRST_NUMBERED = 2

/**
 * The project file format, in one place.
 *
 * `ignoreUnknownKeys` because a file written by a NEWER app version must still open in an older one
 * where it can — losing a field the user has not used is better than refusing to open their project.
 * Losing DATA is a different matter, and that is what the document's own `schemaVersion` is for: a
 * version this build does not understand is a migration question (Phase 4.7), not a parse option.
 */
object ProjectCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(project: SavedProject): String = json.encodeToString(project)

    /**
     * The project in [text], or null when the text is not one.
     *
     * Null rather than an exception, and for the same reason the media reader reports rather than throws
     * (FR-1.4): a corrupt or half-written project file is a project the user cannot open, which is worth
     * telling them — and it is not worth crashing the editor they opened to look at a different one.
     */
    fun decode(text: String): SavedProject? = runCatching {
        json.decodeFromString<SavedProject>(text)
    }.getOrNull()
}
