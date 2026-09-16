package com.redcut.feature.editor

import android.graphics.Bitmap
import android.view.SurfaceView
import com.redcut.core.common.IdSource
import com.redcut.core.common.logging.NoOpRedcutLogger
import com.redcut.core.media.MediaSourceReader
import com.redcut.core.media.PreviewFrames
import com.redcut.core.media.PreviewRenderer
import com.redcut.core.media.PreviewState
import com.redcut.core.media.SourceReadResult
import com.redcut.core.media.ThumbnailSource
import com.redcut.core.media.ThumbnailStore
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.ProbedSource
import com.redcut.domain.document.SourceProbe
import com.redcut.domain.document.clipAt
import com.redcut.domain.project.ProjectStore
import com.redcut.domain.project.ProjectSummary
import com.redcut.domain.project.SavedProject
import com.redcut.domain.project.summary
import com.redcut.domain.render.RenderGraph
import com.redcut.feature.editor.timeline.TimelineThumbnails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * The scaffolding the editor's test classes share: the scheduler they run on, the ids their view
 * models are built with, the factory that wires one, and the fakes it is wired with.
 *
 * This file is where a test class's non-case code goes now that there is more than one of them.
 * `EditorViewModelTest` is the editor's own wiring (stages, undo, import, trim, the cut tools,
 * reorder, the inspector, autosave, the export sheet); `EditorPlaybackTest` is the player-to-timeline
 * direction of FR-2.10. The cases in both are one behaviour each, and a class that holds them grows
 * with the number of behaviours by construction — so when it outgrew detekt's `LargeClass`, the split
 * that rule asks for is this one: the scaffolding out, the cases left where they were.
 *
 * Everything here is `internal` and in the same package as the classes that use it, so nothing needs
 * importing; each declaration is here because BOTH classes ask for it, and the KDoc each one had when
 * it was a private member came with it.
 */

/**
 * The scheduler every case in this package runs on.
 *
 * One instance for the package rather than one per class, because [viewModel] hands it to the editor
 * as the injected IO dispatcher and the cases reach the coroutines that start by being run on the same
 * one — `runTest(dispatcher)`. Two instances would be two schedulers, and the import runs on TWO
 * dispatchers that must share one: `viewModelScope` is Main, the import body is the injected IO
 * dispatcher, and a coroutine queued on a scheduler nobody advances is a test that hangs.
 *
 * Sharing one across classes is safe because no two cases touch the same object: each builds its own
 * view models and its own fakes. A case that ends leaves one coroutine suspended — the renderer's
 * position collector, which is a `collect` on that case's own flows and never completes — and nothing
 * a later case advances can reach back into an object it does not hold. Virtual time only moves
 * forward, and no case reads it.
 *
 * `Dispatchers.setMain(dispatcher)` in each class's `@Before` is the other half of that: the import
 * launches on `viewModelScope`, which is Main.
 */
internal val dispatcher = StandardTestDispatcher()

/**
 * Deterministic ids, so a test can name the exact commands an import produced.
 *
 * One counter for the whole file, shared by every view model [viewModel] builds — which is what the
 * counter did when it lived on the test class and the factory was a member of it. Nothing asserts on
 * a literal id: a case captures the ids it means out of the state it just built. What the counter owes
 * a case is therefore only that two commands never share an id, and a second session over the same
 * store (the reload case) must keep counting rather than mint the ids the first session already used.
 */
private var idCounter = 0

private fun ids(): IdSource = IdSource { "id-${idCounter++}" }

internal fun viewModel(
    reader: MediaSourceReader = RecordingReader(),
    projects: ProjectStore = RecordingProjects(),
    renderer: PreviewRenderer = RecordingRenderer(),
) = EditorViewModel(
    logger = NoOpRedcutLogger,
    sourceReader = reader,
    projects = projects,
    // The timeline's pictures and the preview are not this test's subject: loaders whose source never
    // returns an image keep every case here about state rather than about decoding.
    images = EditorImages(
        thumbnails = TimelineThumbnails(
            ThumbnailStore(source = NoThumbnails, logger = NoOpRedcutLogger),
        ),
        previewFrames = PreviewFrames(source = NoThumbnails, logger = NoOpRedcutLogger),
    ),
    // The renderer is a PORT like the reader and the store: what these cases assert is the WIRING —
    // that the playhead reaches it, and where — never that Media3 drew a frame, which no JVM test
    // can say (that is CI's `testDebugUnitTest` for the compile and a device pass for the pixels).
    previewRenderer = renderer,
    ids = ids(),
    io = dispatcher,
)

/**
 * The preview renderer, recording what it was asked to do.
 *
 * `attach` is deliberately not recorded: which surface and which graph reach the renderer is the
 * stage's business, and the stage is a composable that this tier cannot exercise.
 */
internal class RecordingRenderer : PreviewRenderer {

    /** Every timeline position the renderer was seeked to, in order. */
    val seeks = mutableListOf<Long>()

    /** How many times the renderer was told to give its decoder back (spec §9.1). */
    var releases = 0

    private val preview = MutableStateFlow<PreviewState>(PreviewState.Idle)

    override val state: StateFlow<PreviewState> = preview

    /**
     * Where the picture is (FR-2.10), driven by the test rather than by a decoder.
     *
     * The fake owns the value because the real renderer's belongs to a player: "the frame reached two
     * seconds" is something only a device can say, and this is how a JVM test says it instead. The
     * interface's own default for `positionUs` is NOT overridden to fill a gap — it exists so an
     * implementation that tracks no position compiles unchanged, which is the additive rule. This one
     * overrides it because the position is exactly what the cases below are about.
     */
    private val position = MutableStateFlow(0L)

    override val positionUs: StateFlow<Long> = position

    /**
     * Both halves of what a playing renderer reports: its state says it is playing, and its position
     * says where it has reached. One call, because a renderer that reported one without the other is
     * not a state a real player can be in.
     */
    fun playingAt(us: Long) {
        position.value = us
        preview.value = PreviewState.Ready(isPlaying = true)
    }

    override fun attach(surface: SurfaceView, graph: RenderGraph) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(us: Long) {
        seeks += us
    }

    override fun release() {
        releases++
    }
}

/**
 * The project store, in memory.
 *
 * Records what was saved as well as holding what to reopen, because the two questions this suite asks
 * about autosave are "was it written?" and "what was it called?" — and a fake that only stored the
 * last project could not answer the second.
 *
 * [reopen] is the seam for "a project already existed when this session started". With
 * nothing seeded, `latest()` answers the last SAVE, because that is the port's own
 * promise — "the most recently saved project, or null when there is none" — and what the
 * device does: `JsonProjectStore` writes a pointer to the last saved id and reads it back.
 * A fake that recorded saves but reported nothing to reopen would let a test claim a reload
 * that never happened: the session would load an empty document instead of the project it
 * was just editing.
 */
internal class RecordingProjects(
    private val reopen: SavedProject? = null,
    private val existingNames: MutableList<String> = mutableListOf(),
) : ProjectStore {

    val saved = mutableListOf<SavedProject>()

    override suspend fun save(project: SavedProject) {
        saved += project
        if (project.name !in existingNames) existingNames += project.name
    }

    override suspend fun latest(): SavedProject? = reopen ?: saved.lastOrNull()

    override suspend fun summaries(): List<ProjectSummary> = saved.map { it.summary() }

    override suspend fun savedNames(): List<String> = existingNames.toList()
}

/** A thumbnail source that produces nothing, for tests that do not draw a timeline. */
internal object NoThumbnails : ThumbnailSource {
    override suspend fun thumbnail(sourceId: String, uri: String, positionUs: Long): Bitmap? = null
}

internal fun video(
    name: String = "clip.mp4",
    uri: String = "content://media/1",
    durationUs: Long = 4_000_000L,
    videoCodec: String = "video/avc",
) = SourceReadResult.Read(
    ProbedSource(
        uri = uri,
        displayName = name,
        probe = SourceProbe(
            durationUs = durationUs,
            width = 1920,
            height = 1080,
            frameRate = 30f,
            videoCodec = videoCodec,
            audioCodec = "audio/mp4a-latm",
            hasAudio = true,
        ),
    ),
)

internal fun unreadable(name: String = "gone.mp4") = SourceReadResult.Unreadable(
    ImportRejection.Unreadable(displayName = name, reason = "Permission denied"),
)

/** Returns whatever it was given, in order, and records what it was asked for. */
internal class RecordingReader(
    private val results: List<SourceReadResult> = emptyList(),
) : MediaSourceReader {
    var requested: List<String> = emptyList()

    override suspend fun read(uris: List<String>): List<SourceReadResult> {
        requested = uris
        return results
    }
}

/**
 * The intent the Cut strip sends for [tool]: the tool, on the clip at the playhead (WS C6).
 *
 * A helper rather than a literal at every call site because the payload GAINED a clip, and the tests that
 * tap a Cut tool are about what the tool does — not about how the strip resolved its target. Spelling the
 * resolution out thirteen times would put that logic in the tests instead of in the one place that owns it
 * (`cutTargetIn` in CutToolStrip.kt), and the two would then be free to disagree.
 *
 * It resolves the way the strip does — the SELECTED clip, else the playhead — so a test that cannot find a
 * clip here would fail for the strip too.
 */
internal fun EditorViewModel.cutIntent(tool: CutTool): EditorIntent.ApplyCut {
    val state = state.value
    val clipId = (state.selection as? Selection.Clip)?.clipId
        ?.takeIf { state.document.clipById(it) != null }
        ?: state.document.clipAt(state.playheadUs)?.id
    return EditorIntent.ApplyCut(
        tool = tool,
        clipId = requireNotNull(clipId) { "no clip to cut: neither selected nor at the playhead" },
    )
}
