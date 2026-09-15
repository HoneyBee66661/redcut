package com.redcut.domain.project

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.FitMode
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.Track
import com.redcut.domain.document.TrackKind
import com.redcut.domain.document.videoTrack
import org.junit.jupiter.api.Test

/**
 * A project file exactly as schema v1 wrote one: a flat `clips` list, no `tracks` key anywhere.
 *
 * A literal rather than `encode(project())`, because what is being tested is precisely that a file from
 * BEFORE this build still opens — and a fixture produced by this build's encoder could not be that. Two
 * clips, so their ORDER is testable; a non-default canvas, a name and timestamps, so "nothing lost" is
 * a claim about fields that could plausibly have been dropped on the way through.
 */
private const val V1_FILE = """
{"id":"p1","name":"Holiday","updatedAtMs":1700000000000,
 "document":{"schemaVersion":1,"id":"doc-1","name":"Holiday 2",
  "sources":[{"id":"src-1","uri":"content://media/1","displayName":"clip.mp4",
   "durationUs":60000000,"width":1280,"height":720,"hasAudio":true}],
  "clips":[
   {"id":"clip-a","sourceId":"src-1","sourceInUs":0,"sourceOutUs":4000000},
   {"id":"clip-b","sourceId":"src-1","sourceInUs":4000000,"sourceOutUs":6000000,"speed":2.0}],
  "canvas":"LANDSCAPE_1080","createdAtMs":1600000000000,"modifiedAtMs":1700000000000}}
"""

/** A v1 file with nothing on the timeline: a project the user made and then emptied. */
private const val V1_FILE_EMPTY = """
{"id":"p1","name":"Empty","updatedAtMs":1,
 "document":{"schemaVersion":1,"id":"doc-1","name":"Empty","clips":[]}}
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
                Clip(
                    id = "clip-a",
                    sourceId = "src-1",
                    sourceInUs = 0,
                    sourceOutUs = 4 * oneSecond,
                ),
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
    fun `a v1 file's flat clips open as one video track holding them, in order`() {
        // Schema v2 moved the clips inside a track, so a file written before that says `clips` and has no
        // `tracks` at all. Without the migration the decode succeeds and the timeline comes back EMPTY —
        // the one failure a user cannot tell apart from "my edits are gone".
        val opened = ProjectCodec.decode(V1_FILE)!!

        assertThat(opened.document.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(opened.document.tracks).hasSize(1)
        assertThat(opened.document.tracks.single().id).isEqualTo(Track.MAIN_ID)
        assertThat(opened.document.tracks.single().kind).isEqualTo(TrackKind.VIDEO)
        assertThat(opened.document.clips.map { it.id })
            .containsExactly("clip-a", "clip-b")
            .inOrder()
    }

    @Test
    fun `a v1 file keeps everything that was not its clips`() {
        // The migration rewrites one key. Everything else the user's project holds has to arrive
        // unchanged: the name they gave it, the sources its clips point at (with the facts the probe
        // found), the canvas, and the timestamps a gallery sorts by.
        val opened = ProjectCodec.decode(V1_FILE)!!

        assertThat(opened.name).isEqualTo("Holiday")
        assertThat(opened.updatedAtMs).isEqualTo(1_700_000_000_000L)
        assertThat(opened.document.id).isEqualTo("doc-1")
        assertThat(opened.document.name).isEqualTo("Holiday 2")
        assertThat(opened.document.canvas).isEqualTo(CanvasSpec.LANDSCAPE_1080)
        assertThat(opened.document.createdAtMs).isEqualTo(1_600_000_000_000L)
        assertThat(opened.document.modifiedAtMs).isEqualTo(1_700_000_000_000L)
        assertThat(opened.document.sources.single().displayName).isEqualTo("clip.mp4")
        assertThat(opened.document.sources.single().hasAudio).isTrue()
        assertThat(opened.document.clipById("clip-b")?.speed).isEqualTo(2f)
    }

    @Test
    fun `an old file with no clips opens with the empty video lane a new project has`() {
        // Not every v1 file has clips: one made and then emptied is still a project, and it must open
        // rather than fail on the way through the migration.
        val opened = ProjectCodec.decode(V1_FILE_EMPTY)!!

        assertThat(opened.document.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(opened.document.tracks).isEqualTo(listOf(Track.MAIN))
        assertThat(opened.document.clips).isEmpty()
    }

    // --- The committed v2 fixture -----------------------------------------------------------

    /**
     * The committed v2 file, off the CLASSPATH.
     *
     * From the classpath rather than the path on disk because Gradle puts `src/test/resources` there, so
     * the test finds it from wherever it happens to run. A hand-written JSON literal would prove only
     * that the migration agrees with what this test's author believed the old format was; this fixture
     * was emitted BY the v2 serializer and committed next to it.
     */
    private fun v2Fixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/v2-project.json")) {
            "the committed v2 fixture is missing from the test classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `the committed v2 fixture opens as a v3 document with nothing lost`() {
        // The real file, through the real codec: a v2 lane's `clips` key becomes its items and every fact
        // the fixture records — the two sources, the trim, the speed, the level — has to survive the
        // promotion, because the alternative is a user opening a project onto an empty timeline.
        val opened = ProjectCodec.decode(v2Fixture())!!

        assertThat(opened.document.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(opened.document.sources.map { it.id })
            .containsExactly("src-plain", "src-rotated")
            .inOrder()
        assertThat(opened.document.tracks).hasSize(1)
        assertThat(opened.document.tracks.single().id).isEqualTo(Track.MAIN_ID)
        assertThat(opened.document.tracks.single().kind).isEqualTo(TrackKind.VIDEO)
        assertThat(opened.document.clips.map { it.id })
            .containsExactly("clip-trimmed", "clip-adjusted")
            .inOrder()
        assertThat(opened.document.clipById("clip-trimmed")?.sourceInUs).isEqualTo(1_000_000L)
        assertThat(opened.document.clipById("clip-trimmed")?.sourceOutUs).isEqualTo(4_500_000L)
        assertThat(opened.document.clipById("clip-adjusted")?.speed).isEqualTo(2f)
        assertThat(opened.document.clipById("clip-adjusted")?.volume).isEqualTo(0.5f)
    }

    @Test
    fun `a file this build wrote is not migrated a second time`() {
        // The round trip above proves equality; this proves the RULE — a v2 document keeps its tracks and
        // its stamp, so opening a project twice cannot fold its lanes into one on the second open.
        val before = project()

        val after = ProjectCodec.decode(ProjectCodec.encode(before))!!

        assertThat(after.document.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(after.document.tracks).isEqualTo(before.document.tracks)
    }

    // --- The committed v3 fixture -----------------------------------------------------------

    /**
     * The committed v3 file, off the CLASSPATH, for the same reason [v2Fixture] is read there rather
     * than by path: Gradle puts `src/test/resources` on the classpath, so the test finds the file from
     * wherever it happens to run.
     */
    private fun v3Fixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/v3-project.json")) {
            "the committed v3 fixture is missing from the test classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `the committed v3 fixture is still a v3 file with no keyframes key`() {
        // Asserted on the BYTES rather than through a decode, for the same reason V2FixtureDumpTest is:
        // a v4-shaped file decodes perfectly well and yields the same empty keyframes, so only the raw
        // text can say the migration test is reading what it claims to. There was no writer for the v3
        // wire shape by the time this fixture was committed — v3 was already one version back — so what
        // pins it to v3 is this guard, not a producer that could no longer exist.
        val text = v3Fixture()

        assertThat(text).contains(""""schemaVersion":3""")
        assertThat(text).doesNotContain(""""keyframes":""")
    }

    @Test
    fun `a v3 file opens as a v4 document with empty keyframes on its clips`() {
        // The "old file, missing new fields" direction: a file written by the v3 build has clips with no
        // `keyframes` key anywhere, and it must open with those fields at their defaults — the migration
        // that could not invent the field is the migration that does nothing but advance the stamp. The
        // static values a v3 file DID store have to survive, because a v3 crop the user set is still a
        // crop: `clip-cropped` keeps its crop rect and its volume, and both clips read back with no
        // keyframes at all.
        val opened = ProjectCodec.decode(v3Fixture())!!

        assertThat(opened.document.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(opened.document.tracks).hasSize(1)
        assertThat(opened.document.clips.map { it.id })
            .containsExactly("clip-plain", "clip-cropped")
            .inOrder()
        assertThat(opened.document.clips.all { it.keyframes.isEmpty() }).isTrue()
        val cropped = opened.document.clipById("clip-cropped")!!
        assertThat(cropped.transform.cropLeft).isEqualTo(0.1f)
        assertThat(cropped.transform.rotationDegrees).isEqualTo(2.5f)
        assertThat(cropped.transform.fit).isEqualTo(FitMode.FILL)
        assertThat(cropped.volume).isEqualTo(0.5f)
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
