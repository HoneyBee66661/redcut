package com.redcut.domain.project

import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.Track
import com.redcut.domain.document.TrackKind
import com.redcut.domain.document.promotedFromV1
import com.redcut.domain.document.promotedFromV2
import com.redcut.domain.document.promotedFromV3
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
     *
     * An older document is promoted on the way in, because the alternative — decoding the keys this
     * build still names and dropping the rest — would open the user's project with its timeline silently
     * emptied, and `ignoreUnknownKeys` is exactly what makes that silent. That is why the key a previous
     * format used is read through a view of its own ([V1Project] for a v1 file's flat `clips`,
     * [V2Project] for a v2 track's `clips`) rather than migrated after the decode: by then, it is gone.
     *
     * The v3 rule runs first — it only advances the stamp of an already-complete v4-shaped document, so it
     * is the least invasive and the first in the newest-first order — then the v2 rule, then the v1 rule:
     * each stamps the version forward, and the order is what keeps each one in front of the files it owns
     * (see `EditDocument.promotedFromV3`).
     */
    fun decode(text: String): SavedProject? = runCatching {
        val project = json.decodeFromString<SavedProject>(text)
        val v2Tracks = json.decodeFromString<V2Project>(text).document.tracks.map { it.toTrack() }
        val v1Clips = json.decodeFromString<V1Project>(text).document.clips
        project.promoteV3().promoteV2Tracks(v2Tracks).promoteV1Clips(v1Clips)
    }.getOrNull()

    /**
     * Advances the stamp of a v3 file, whose clips already decoded with empty keyframes.
     *
     * A v3 file is structurally identical to a v4 one — the only difference is the new defaulted field,
     * which the codec's own decode has already filled — so there is nothing to move and no old view to
     * read it through. The one thing left is the version stamp, and advancing it here is what keeps a
     * future v5 migration able to tell a v3 file from a v4 one. Run first, before [promoteV2Tracks],
     * because it is the newest rule and the newest-first order is what keeps each rule in front of the
     * files it owns.
     */
    private fun SavedProject.promoteV3(): SavedProject = copy(document = document.promotedFromV3())

    /**
     * Hands the codec's v2 reading of `tracks` to the document's own migration rule.
     *
     * One decode per format the text might be in, because they disagree about where a lane's clips are:
     * the first reads everything the current model names, and each view after it reads one key this build
     * no longer looks at. The decision — whether this document is old, and whether it has anything to
     * move — belongs to the migration rules in :domain:document, where it is tested without JSON.
     *
     * Before [promoteV1Clips], never after: a v1 file has no `tracks` key, so this rule has nothing to
     * move in it, but it would still stamp the version forward and the v1 rule would then find a
     * document that looks current and leave the user's clips where the decode dropped them.
     */
    private fun SavedProject.promoteV2Tracks(v2Tracks: List<Track>): SavedProject =
        copy(document = document.promotedFromV2(v2Tracks))

    /**
     * Hands the codec's v1 reading of `clips` to the document's own migration rule.
     *
     * See [promoteV2Tracks] for why the file is read once per format, and why that one goes first.
     */
    private fun SavedProject.promoteV1Clips(v1Clips: List<Clip>): SavedProject =
        copy(document = document.promotedFromV1(v1Clips))
}

/**
 * The document as schema v1 wrote it: a flat clip list, and no tracks.
 *
 * A view of the file's `document` object rather than a second document type — it names the one key the
 * migration needs and lets `ignoreUnknownKeys` ignore the rest, so a v1 file with fields this build has
 * never heard of still yields its clips. Nothing outside the migration may read it: the clips it holds
 * have no track, which is the state the rest of the codebase is written to not have to think about.
 */
@Serializable
private data class V1Project(val document: V1Document = V1Document())

@Serializable
private data class V1Document(val clips: List<Clip> = emptyList())

/**
 * The document as schema v2 wrote it: a track whose clips were still a flat `clips` list inside it.
 *
 * The same trick as [V1Project], one version on. v3's [Track] has no `clips` field, so the key a v2 file
 * wrote for every lane is dropped without a word and the document decodes with every lane empty; this
 * names that key so the clips survive to the migration. Nothing outside the migration may read it: a
 * [V2Track] is a statement about a FILE, and the domain's lanes are built from it at the boundary.
 */
@Serializable
private data class V2Project(val document: V2Document = V2Document())

@Serializable
private data class V2Document(val tracks: List<V2Track> = emptyList())

@Serializable
private data class V2Track(
    val id: String,
    val kind: TrackKind,
    val clips: List<Clip> = emptyList(),
)

/**
 * The lane this v2 row describes: its clips become the lane's items, in order.
 *
 * The v3 attributes stay at their defaults because v2 never wrote them — that is not a lossy conversion,
 * it is the whole content of the change. The conversion lives HERE rather than in the model so the old
 * wire shape stops at the module boundary: [V2Track] is not a lane, and a domain that accepted one would
 * have to keep answering questions about a format it has moved past.
 */
private fun V2Track.toTrack(): Track = Track(id = id, kind = kind, items = clips)
