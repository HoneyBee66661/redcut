package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.logging.NoOpRedcutLogger
import org.junit.Test

/**
 * The editor's wiring, tested as a plain JVM test.
 *
 * No Robolectric and no Hilt: [EditorViewModel] takes a `RedcutLogger` and touches no
 * Android API, so constructing it directly is the whole setup. That is a deliberate
 * property of the class, not a coincidence — the state holder is where an editor's
 * behaviour lives, and a state holder that needs an instrumented runner to test is a
 * state holder whose behaviour is verified on a device, by hand, eventually.
 *
 * What this does NOT prove: rendering, navigation, or that Hilt can build the graph.
 * Those are the `build` job's business (it compiles the generated component) and a
 * screenshot test's, later.
 */
class EditorViewModelTest {

    private fun viewModel() = EditorViewModel(NoOpRedcutLogger)

    @Test
    fun `the editor starts on Cut with an empty history`() {
        val state = viewModel().state.value

        assertThat(state.stage).isEqualTo(Stage.Cut)
        assertThat(state.document.clips).isEmpty()
        assertThat(state.history).isEqualTo(
            HistoryState.Ready(canUndo = false, canRedo = false, topLabel = null),
        )
    }

    @Test
    fun `selecting a stage changes only the stage`() {
        val model = viewModel()
        val before = model.state.value

        model.onIntent(EditorIntent.SelectStage(Stage.Effect))

        val after = model.state.value
        assertThat(after.stage).isEqualTo(Stage.Effect)
        assertThat(after.document).isEqualTo(before.document)
        assertThat(after.history).isEqualTo(before.history)
    }

    @Test
    fun `switching stages never advances the document revision`() {
        // Spec §7.1: "Stage switching is instant and free ... it does not recompile the
        // document". A stage tap that bumped the revision would trigger a recompile of
        // the whole timeline — the difference between a free switch and a stutter.
        val model = viewModel()
        val revisionBefore = model.state.value.revision

        Stage.entries.forEach { model.onIntent(EditorIntent.SelectStage(it)) }

        assertThat(model.state.value.revision).isEqualTo(revisionBefore)
    }

    @Test
    fun `undo and redo on an empty history change nothing`() {
        val model = viewModel()
        val before = model.state.value

        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value).isEqualTo(before)

        model.onIntent(EditorIntent.Redo)
        assertThat(model.state.value).isEqualTo(before)
    }

    @Test
    fun `the stage survives an undo`() {
        // The stage is not history: undoing an edit must not also unwind "the user is
        // looking at the Edit stage", which is what would happen if the whole UI state
        // were snapshotted along with the document.
        val model = viewModel()

        model.onIntent(EditorIntent.SelectStage(Stage.Edit))
        model.onIntent(EditorIntent.Undo)

        assertThat(model.state.value.stage).isEqualTo(Stage.Edit)
    }
}
