package com.redcut.domain.project

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.FitMode
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.TransformSpec
import com.redcut.domain.document.videoTrack
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the emitted fixture lands, relative to this MODULE's directory — Gradle runs a module's tests
 * with that directory as the working directory, so this resolves to
 * `domain/project/src/test/resources/v2-project.json`.
 */
private const val FIXTURE_PATH = "src/test/resources/v2-project.json"

/** The unit the durations below are written in, so they read as seconds, not as digit runs. */
private const val ONE_SECOND = 1_000_000L

/**
 * Emits a REAL schema-v2 project file, so the v3 migration has something honest to read.
 *
 * ### What it is for
 *
 * A migration test is worth only as much as the file it reads. The v1 migration's rule was "a v1
 * file must open in v2 with nothing lost, and the fixture should be a real file" — a hand-typed
 * JSON literal proves only that the migration agrees with what the test's author believed the old
 * format was. This host has no `adb` and no Android SDK, so a file pulled off a device is
 * impossible; the next best honest artifact is one emitted BY the v2 serializer, which is what this
 * writes: [ProjectCodec.encode], the same `Json` configuration the app saves with, so the bytes are
 * the ones a user's project file holds.
 *
 * ### Where the fixture lives
 *
 * At `domain/project/src/test/resources/v2-project.json`, VERSION-CONTROLLED, because a fixture in
 * `/tmp` is not reproducible from a clean checkout: the migration test would pass on the one machine
 * that had run this producer and fail everywhere else.
 *
 * The migration test does not read that path. It loads `/v2-project.json` from the CLASSPATH, which
 * is where Gradle puts `src/test/resources`, so the file is found from wherever the test happens to
 * run rather than only from this module's directory.
 *
 * ### A FIXTURE PRODUCER, not a test of behaviour
 *
 * It asserts almost nothing, on purpose. The round trip below is a smoke check that the artifact is
 * readable, not a claim about the format — those live in `ProjectFileTest`, which IS a test.
 *
 * Re-run this whenever the v2 model changes: it regenerates the COMMITTED fixture in place, so the
 * diff a format change produces is visible in `git status` and gets reviewed like any other change —
 * which is the reason this producer sits next to the fixture instead of in a scratch directory, where
 * a format change would instead surface as a migration test failing on a stale file.
 *
 * ```
 * ./gradlew :domain:project:test --tests '*V2FixtureDumpTest*'
 * ```
 */
class V2FixtureDumpTest {

    @Test
    fun `the v2 serializer writes the fixture the migration test reads`() {
        val file = File(FIXTURE_PATH)
        // Not a convenience: `src/test/resources/` does not exist on a clean checkout, so without
        // this the FIRST run — the one that creates the fixture — has nowhere to write.
        file.parentFile?.mkdirs()
        file.writeText(ProjectCodec.encode(fixtureProject()), Charsets.UTF_8)

        // The artifact on disk is what the next workstream consumes, so that is what gets checked:
        // a file that is missing or empty is a migration test with nothing to read.
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(0L)

        val reopened = ProjectCodec.decode(file.readText(Charsets.UTF_8))!!
        assertThat(reopened.document.tracks[0].clips).hasSize(2)
        assertThat(reopened.document.tracks[0].clips[1].speed).isEqualTo(2f)
    }

    /**
     * A project that exercises the v2 wire format rather than the empty case.
     *
     * Two sources that disagree about every probed field the model carries, and a video track whose
     * two clips disagree about every field the edit stage can set — because a field the fixture
     * leaves at its default is a field a migration can drop without the test ever noticing.
     */
    private fun fixtureProject(): SavedProject = SavedProject(
        id = "p-fixture",
        name = "fixture-two-clips",
        document = EditDocument(
            id = "doc-fixture",
            name = "fixture-two-clips",
            sources = listOf(plainSource(), rotatedSource()),
            tracks = listOf(videoTrack(trimmedClip(), adjustedClip())),
            canvas = CanvasSpec.LANDSCAPE_1080,
            createdAtMs = 1_600_000_000_000L,
            modifiedAtMs = 1_700_000_000_000L,
        ),
        updatedAtMs = 1_700_000_000_000L,
    )

    /** The ordinary import: silent, upright, at the frame rate a phone camera reports. */
    private fun plainSource(): SourceRef = SourceRef(
        id = "src-plain",
        uri = "content://fixture/plain",
        displayName = "plain.mp4",
        durationUs = 60 * ONE_SECOND,
        width = 1920,
        height = 1080,
        frameRate = 30f,
        hasAudio = false,
    )

    /** The one that is not: a quarter turn of rotation, 29.97 fps, and a sound track. */
    private fun rotatedSource(): SourceRef = SourceRef(
        id = "src-rotated",
        uri = "content://fixture/rotated",
        displayName = "rotated.mp4",
        durationUs = 30 * ONE_SECOND,
        width = 1920,
        height = 1080,
        rotationDegrees = 90,
        frameRate = 29.97f,
        hasAudio = true,
        audioCodec = "audio/mp4a-latm",
    )

    /** Trimmed, and nothing else: the shape every edit starts from. */
    private fun trimmedClip(): Clip = Clip(
        id = "clip-trimmed",
        sourceId = "src-plain",
        sourceInUs = 1_000_000L,
        sourceOutUs = 4_500_000L,
    )

    /** Everything the adjust stage can set, all at once, so the file says so. */
    private fun adjustedClip(): Clip = Clip(
        id = "clip-adjusted",
        sourceId = "src-rotated",
        sourceInUs = 0L,
        sourceOutUs = 6 * ONE_SECOND,
        speed = 2f,
        volume = 0.5f,
        muted = false,
        fadeInMs = 250L,
        transform = TransformSpec(
            cropLeft = 0.1f,
            cropTop = 0.05f,
            cropRight = 0.9f,
            cropBottom = 0.95f,
            rotationDegrees = 2.5f,
            flipHorizontal = true,
            fit = FitMode.FILL,
        ),
    )
}
