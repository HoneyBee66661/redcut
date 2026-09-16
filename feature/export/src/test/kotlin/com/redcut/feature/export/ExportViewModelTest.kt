package com.redcut.feature.export

import com.google.common.truth.Truth.assertThat
import com.redcut.core.media.ExportController
import com.redcut.core.media.ExportState
import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.videoTrack
import com.redcut.domain.project.ProjectStore
import com.redcut.domain.project.ProjectSummary
import com.redcut.domain.project.SavedProject
import com.redcut.domain.render.ExportPresets
import com.redcut.domain.render.RenderGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The fixture source's length; comfortably longer than the clip cut from it. */
private const val SOURCE_DURATION_US = 4_000_000L

/** The fixture clip's cut: the first half of the source. */
private const val CLIP_OUT_US = 2_000_000L

/** The percent the state-relay test publishes through the fake port. */
private const val RUNNING_PERCENT = 42

/**
 * The export sheet's wiring, as a plain JVM test.
 *
 * No Robolectric and no Hilt: the view model takes a [ProjectStore] and an [ExportController] —
 * both ports — and a dispatcher. What is under test is the one thing only this class can get
 * wrong: the graph it HANDS OVER is compiled at the chosen preset by the same compiler the
 * preview uses. What it does not prove: that the encode runs (the service's business, on a
 * device) or that the port refuses empty graphs (the port's own contract).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startExport compiles at the chosen preset and hands the graph to the port`() = runTest(dispatcher) {
        val document = oneClipDocument()
        val controller = RecordingExportController()
        val model = viewModel(document, controller)

        model.selectResolution(ExportResolution.STANDARD_720P)
        model.startExport()
        advanceUntilIdle()

        assertThat(controller.started).hasSize(1)
        val handed = controller.started.single()
        assertThat(handed.output).isEqualTo(ExportPresets.at720p(document.canvas))
        assertThat(handed.videoLayers).hasSize(1)
    }

    @Test
    fun `the resolution choice is the only thing that changes between two exports`() = runTest(dispatcher) {
        val document = oneClipDocument()
        val controller = RecordingExportController()
        val model = viewModel(document, controller)

        model.startExport()
        advanceUntilIdle()
        model.selectResolution(ExportResolution.STANDARD_720P)
        model.startExport()
        advanceUntilIdle()

        assertThat(controller.started).hasSize(2)
        assertThat(controller.started[0].output).isEqualTo(ExportPresets.at1080p(document.canvas))
        assertThat(controller.started[1].output).isEqualTo(ExportPresets.at720p(document.canvas))
    }

    @Test
    fun `an empty timeline still hands its graph over so the port owns the refusal`() = runTest(dispatcher) {
        val controller = RecordingExportController()
        val model = viewModel(oneClipDocument(enabled = false), controller)

        model.startExport()
        advanceUntilIdle()

        // All-disabled clips ripple to an empty graph; the button is disabled in the UI, and the
        // port is where the empty case is REJECTED (with a Failed state), not the view model —
        // one refusal, in the port, is the refusal the sheet then shows.
        assertThat(controller.started).hasSize(1)
        assertThat(controller.started.single().videoLayers).isEmpty()
    }

    @Test
    fun `the controller's state is the sheet's state, verbatim`() = runTest(dispatcher) {
        val controller = RecordingExportController()
        val model = viewModel(oneClipDocument(), controller)
        advanceUntilIdle()

        controller.publish(ExportState.Running(RUNNING_PERCENT))
        advanceUntilIdle()

        assertThat(model.uiState.value.exportState).isEqualTo(ExportState.Running(RUNNING_PERCENT))
    }

    private fun viewModel(
        document: EditDocument,
        controller: ExportController,
    ) = ExportViewModel(
        projects = FakeProjectStore(
            SavedProject(id = document.id, name = document.name, document = document, updatedAtMs = 0L),
        ),
        controller = controller,
        defaultDispatcher = dispatcher,
    )

    /**
     * One audible clip on one video lane. The source is registered on the document because the
     * compiler drops dangling references — a fixture without one tests the drop, not the compile.
     */
    private fun oneClipDocument(enabled: Boolean = true): EditDocument {
        val source = SourceRef(
            id = "src",
            uri = "content://fixture/src",
            displayName = "src.mp4",
            durationUs = SOURCE_DURATION_US,
            width = CanvasSpec.LANDSCAPE_1080.width,
            height = CanvasSpec.LANDSCAPE_1080.height,
            hasAudio = true,
        )
        return EditDocument(
            id = "doc",
            name = "Doc",
            canvas = CanvasSpec.LANDSCAPE_1080,
            sources = listOf(source),
            tracks = listOf(
                videoTrack(
                    listOf(
                        Clip(
                            id = "clip",
                            sourceId = source.id,
                            sourceInUs = 0L,
                            sourceOutUs = CLIP_OUT_US,
                            enabled = enabled,
                        ),
                    ),
                ),
            ),
        )
    }

    private class FakeProjectStore(private val project: SavedProject?) : ProjectStore {
        override suspend fun save(project: SavedProject) = Unit
        override suspend fun latest(): SavedProject? = project
        override suspend fun summaries(): List<ProjectSummary> = emptyList()
        override suspend fun savedNames(): List<String> = emptyList()
    }

    private class RecordingExportController : ExportController {
        val started = mutableListOf<RenderGraph>()

        override val state = MutableStateFlow<ExportState>(ExportState.Idle)

        override fun start(graph: RenderGraph) {
            started += graph
        }

        override fun cancel() = Unit

        override fun dismiss() {
            state.value = ExportState.Idle
        }

        fun publish(next: ExportState) {
            state.value = next
        }
    }
}
