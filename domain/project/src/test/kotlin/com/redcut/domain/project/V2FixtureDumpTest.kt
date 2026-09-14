package com.redcut.domain.project

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the fixture lands, relative to this MODULE's directory — Gradle runs a module's tests with that
 * directory as the working directory, so this resolves to
 * `domain/project/src/test/resources/v2-project.json`.
 */
private const val FIXTURE_PATH = "src/test/resources/v2-project.json"

/**
 * The committed v2 project file: that it is still a v2 file, and that the codec opens it.
 *
 * ### What this used to be, and why it could not stay that way
 *
 * The class name is historical. This was a FIXTURE PRODUCER: it built a project with the current model
 * and wrote it to [FIXTURE_PATH] through [ProjectCodec.encode], so the committed artifact was one emitted
 * BY the v2 serializer rather than a JSON literal typed by hand — the v1 fixture's lesson, which is that
 * a hand-typed "old file" proves only that the migration agrees with what its author believed the old
 * format was.
 *
 * That producer cannot survive the model moving past v2. Its input is the CURRENT model and the current
 * model is v3, so running it would overwrite the committed v2 file with a v3 one — and the v3 migration
 * test in [ProjectFileTest] would go on passing while reading a file that needs no migration. A green
 * test of nothing, plus a corrupted fixture, is the worst of both. The honest producer for a frozen
 * format is a frozen WRITER, and there is no writer for the v2 wire shape; the file was emitted and
 * committed while v2 was current, which was the only moment it could be.
 *
 * What is left is the half still worth asserting: the artifact is a v2 file, and the production codec
 * opens it.
 */
class V2FixtureDumpTest {

    @Test
    fun `the committed fixture is still a v2 file the codec can open`() {
        val text = File(FIXTURE_PATH).readText(Charsets.UTF_8)

        // Asserted on the BYTES rather than through a decode: a v3 file decodes perfectly well and yields
        // the same clips, so only the raw text can say the migration test is reading what it claims to.
        assertThat(text).contains(""""schemaVersion":2""")
        assertThat(text).contains(""""clips":""")
        assertThat(text).doesNotContain(""""items":""")

        // And that it is a file rather than a fixture-shaped hole: the codec opens it and the promotion
        // finds the lane's clips.
        val reopened = ProjectCodec.decode(text)!!
        assertThat(reopened.document.tracks[0].clips).hasSize(2)
        assertThat(reopened.document.tracks[0].clips[1].speed).isEqualTo(2f)
    }
}
