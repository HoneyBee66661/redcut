package com.redcut.domain.project

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.TrackKind
import com.redcut.domain.document.videoTrack
import org.junit.jupiter.api.Test

/**
 * A project file exactly as schema v1 wrote one: a flat `clips` list, no `tracks` key anywhere.
 *
 * A literal rather than `encode(project())`, because what is being tested is precisely that a file from
 * BEFORE this build still opens — and a fixture produced by this build's encoder could not be that.
 */
private const val V1_FILE = """
{"id":"p1","name":"untitled","updatedAtMs":1700000000000,
 "document":{"schemaVersion":1,"id":"doc","name":"Doc",
  "sources":[{"id":"src-1","uri":"content://media/1","displayName":"clip.mp4",
   "durationUs":60000000,"width":1920,"height":1080}],
  "clips":[{"id":"clip-a","sourceId":"src-1","sourceInUs":0,"sourceOutUs":4000000}]}}
"""

/**
 * The naming rule, and the file format's round trip.
 *
 * Both are pure, which is the whole reason they live in this module rather than in the DataStore
 * implementation that will use them: the rule that decides what a project is CALLED is testable in
 * milliseconds, and the file format that decides whether the user's work can be reopened is testable
 * without a filesystem at all.
 */
class ProjectFileTest {

    private val oneSecond = 1_000_000L

    private fun document() = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(
            SourceRef(
                id = "src-1",
                uri = "content://media/1",
                displayName = "clip.mp4",
                durationUs = 60 * oneSecond,
                width = 1920,
                height = 1080,
            ),
        ),
        tracks = listOf(
            videoTrack(
                Clip(id = "clip-a", sourceId = "src-1", sourceInUs = 0, sourceOutUs = 4 * oneSecond),
            ),
        ),
    )

    private fun project(id: String = "p1", name: String = "untitled") = SavedProject(
        id = id,
        name = name,
        document = document(),
        updatedAtMs = 1_700_000_000_000L,
    )

    // --- The naming rule ---------------------------------------------------

    @Test
    fun `the first project is plain untitled, not untitled 1`() {
        // "untitled 1" reads as a count that is out of step with its own name: it is the first one, and
        // the number says otherwise.
        assertThat(nextUntitledName(emptyList())).isEqualTo("untitled")
        assertThat(nextUntitledName(listOf("holiday"))).isEqualTo("untitled")
    }

    @Test
    fun `a taken name moves to the next number`() {
        assertThat(nextUntitledName(listOf("untitled"))).isEqualTo("untitled 2")
        assertThat(nextUntitledName(listOf("untitled", "untitled 2"))).isEqualTo("untitled 3")
    }

    @Test
    fun `a gap in the numbering is filled, rather than counted past`() {
        // The name identifies a project; it is not a record of how many have existed. Deleting
        // "untitled 2" should make that name available again.
        assertThat(nextUntitledName(listOf("untitled", "untitled 3"))).isEqualTo("untitled 2")
    }

    @Test
    fun `names that only differ in case or spacing still collide`() {
        // To a user reading a list, "Untitled " and "untitled" are the same name — and a rule that did
        // not know that would create a second project with the same one.
        assertThat(nextUntitledName(listOf("Untitled"))).isEqualTo("untitled 2")
        assertThat(nextUntitledName(listOf(" untitled "))).isEqualTo("untitled 2")
        assertThat(nextUntitledName(listOf("untitled", "UNTITLED 2"))).isEqualTo("untitled 3")
    }

    @Test
    fun `a name that merely starts with untitled does not count as taken`() {
        // "untitled holiday" is a different name: prefix matching would skip numbers for no reason.
        assertThat(nextUntitledName(listOf("untitled holiday"))).isEqualTo("untitled")
        assertThat(nextUntitledName(listOf("untitled2"))).isEqualTo("untitled")
    }

    // --- The file format ---------------------------------------------------

    @Test
    fun `a project survives the round trip to text and back`() {
        val before = project()

        val after = ProjectCodec.decode(ProjectCodec.encode(before))

        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `the document inside survives with its clips, so a reopened project still edits`() {
        // The point of the whole feature: what comes back has to be the user's timeline, not a summary of
        // it. Asserting the EQUALITY above proves the objects match; this asserts what they contain.
        val after = ProjectCodec.decode(ProjectCodec.encode(project()))!!

        assertThat(after.document.clips.single().sourceOutUs).isEqualTo(4 * oneSecond)
        assertThat(after.document.sources.single().uri).isEqualTo("content://media/1")
    }

    @Test
    fun `a file written by a newer version opens, losing only what this build does not know`() {
        // Forward compatibility is a parse option, not a migration: `ignoreUnknownKeys` is what lets a
        // project written by a later build open here at all.
        val text = ProjectCodec.encode(project())
        val withExtra = text.dropLast(1) + ""","futureField":42}"""

        assertThat(ProjectCodec.decode(withExtra)).isEqualTo(project())
    }

    @Test
    fun `a v1 file's flat clips open as one video track`() {
        // Schema v2 moved the clips inside a track, so a file written before that says `clips` and has no
        // `tracks` at all. Without the migration the decode succeeds and the timeline comes back EMPTY —
        // the one failure a user cannot tell apart from "my edits are gone".
        val opened = ProjectCodec.decode(V1_FILE)!!

        assertThat(opened.document.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(opened.document.tracks).hasSize(1)
        assertThat(opened.document.tracks.single().kind).isEqualTo(TrackKind.VIDEO)
        assertThat(opened.document.clips.map { it.id }).containsExactly("clip-a")
        assertThat(opened.document.sourceById("src-1")).isNotNull()
    }

    @Test
    fun `text that is not a project is null rather than an exception`() {
        // A corrupt or half-written file is a project the user cannot open — worth telling them, not worth
        // crashing the editor they opened to look at a different one.
        assertThat(ProjectCodec.decode("")).isNull()
        assertThat(ProjectCodec.decode("not json at all")).isNull()
        assertThat(ProjectCodec.decode("""{"id":"p1"}""")).isNull()
    }
}
