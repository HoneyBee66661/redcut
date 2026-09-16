package com.redcut.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.redcut.core.common.di.DefaultDispatcher
import com.redcut.core.media.ExportController
import com.redcut.core.media.ExportState
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.timelineDurationUs
import com.redcut.domain.project.ProjectStore
import com.redcut.domain.render.TimelineCompiler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The export sheet's state holder: one compile, one hand-off, one observed state.
 *
 * ### Where the document comes from, and why that is not a second source of truth
 *
 * The editor autosaves every change through [ProjectStore] (Phase 1.5's wiring), so the sheet's
 * [ProjectStore.latest] read IS the document the user was just editing — there is no document
 * hand-off between routes to drift. The graph is compiled HERE, at the chosen preset, by the same
 * [TimelineCompiler] the preview compiles with — which is the whole of the parity story (§12.3):
 * the port receives a compiled graph, not a document, so the export cannot grow a second compile
 * path the preview would have to agree with after the fact.
 *
 * Everything after `start` belongs to the [ExportController] port: the sheet observes the port's
 * state and touches nothing else, because the encode lives in a foreground service this module
 * cannot see (§4.1 rule 2).
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val projects: ProjectStore,
    private val controller: ExportController,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * What the sheet shows. [hasTimeline] is about live clips — a document whose clips are all
     * disabled compiles to an empty graph, and the honest button is a disabled one. [exportState]
     * is the service's view, verbatim: the sheet does not own a second export-state machine.
     */
    data class UiState(
        val hasTimeline: Boolean = false,
        val resolution: ExportResolution = ExportResolution.HIGH_1080P,
        val exportState: ExportState = ExportState.Idle,
    ) {
        /** Preparing and Running both mean "the encode owns the screen's future". */
        val isExporting: Boolean
            get() = exportState is ExportState.Preparing || exportState is ExportState.Running
    }

    /** The document the sheet compiles from, read once per visit — the editor autosaves its edits. */
    private var document: EditDocument? = null

    private val hasTimeline = MutableStateFlow(false)

    private val resolution = MutableStateFlow(ExportResolution.HIGH_1080P)

    /**
     * [SharingStarted.Eagerly] rather than `WhileSubscribed`: the export state must be current the
     * instant the composable reads it, including the moment the user returns from the editor with
     * an export already in flight — a subscription-lagged flow would show a stale Idle for a frame.
     */
    val uiState: StateFlow<UiState> = combine(
        hasTimeline.asStateFlow(),
        resolution.asStateFlow(),
        controller.state,
    ) { hasTimeline, resolution, exportState ->
        UiState(hasTimeline = hasTimeline, resolution = resolution, exportState = exportState)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch(defaultDispatcher) {
            projects.latest()?.document?.let { loaded ->
                document = loaded
                hasTimeline.value = loaded.timelineDurationUs > 0L
            }
        }
    }

    /** The sheet's one edit: which of the two FR-5.1 resolutions the export compiles at. */
    fun selectResolution(next: ExportResolution) {
        resolution.value = next
    }

    /**
     * Compiles the current document at the chosen preset and hands the graph to the port.
     *
     * A no-op when no document loaded — a sheet opened into an empty project has nothing to
     * export, and the refusal the user needs is the disabled button, not a state change.
     */
    fun startExport() {
        val current = document ?: return
        viewModelScope.launch(defaultDispatcher) {
            controller.start(
                TimelineCompiler.compile(current, resolution.value.preset(current.canvas)),
            )
        }
    }

    /** Folds a terminal export state back to Idle, per the port's [ExportController.dismiss]. */
    fun dismiss() {
        controller.dismiss()
    }
}
