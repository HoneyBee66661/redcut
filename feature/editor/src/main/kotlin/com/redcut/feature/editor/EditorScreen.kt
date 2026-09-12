package com.redcut.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The editor destination: the ViewModel owner.
 *
 * The split between this and [EditorScreen] is the point — everything that needs Hilt or
 * a lifecycle lives here, and the screen below is a pure function of state plus a
 * callback. That is what makes the screen previewable, screenshot-testable (Phase 12.2)
 * and reviewable without a DI graph in your head.
 */
@Composable
fun EditorRoute(
    onExport: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    EditorScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onExport = onExport,
        onBack = onBack,
    )
}

/**
 * The stage host (spec §7.1).
 *
 * The stage bar is a `TabRow` for now, and a `TabRow` is honest: the spec's sketch shows
 * a segmented stage bar, but where it sits relative to the preview and timeline depends
 * on the timeline existing (Phase 1.5). What is real here is the shape — stage in state,
 * one screen, no navigation — so the timeline lands inside it rather than replacing it.
 *
 * The preview and timeline regions are the `Box` below: a sentence naming the phase that
 * fills them, because a fake timeline is worse than an obviously absent one.
 */
@Composable
internal fun EditorScreen(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Projects") }
            Text(
                text = state.document.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = onExport, enabled = state.document.clips.isNotEmpty()) {
                Text("Export")
            }
        }

        TabRow(selectedTabIndex = state.stage.ordinal) {
            Stage.entries.forEach { stage ->
                Tab(
                    selected = stage == state.stage,
                    onClick = { onIntent(EditorIntent.SelectStage(stage)) },
                    text = { Text(stage.label) },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.stage.detail,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }

        HistoryBar(history = state.history, onIntent = onIntent)
    }
}

/**
 * Undo / redo, driven by [HistoryState].
 *
 * Two things worth noting. The `when` is total over the sealed type, so the day
 * `HistoryState.Busy` gains a field (an export's progress) this function fails to compile
 * until it is handled — which is the point of modelling "busy" at all (§7.3). And the
 * buttons are driven by the flags rather than by `undoDepth > 0`: the UI must not be able
 * to disagree with the stack about whether an undo is available.
 */
@Composable
private fun HistoryBar(history: HistoryState, onIntent: (EditorIntent) -> Unit) {
    val (canUndo, canRedo, label) = when (history) {
        is HistoryState.Ready -> Triple(history.canUndo, history.canRedo, history.topLabel)
        HistoryState.Busy -> Triple(false, false, null)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onIntent(EditorIntent.Undo) }, enabled = canUndo) {
            Text(label?.let { "Undo $it" } ?: "Undo")
        }
        TextButton(onClick = { onIntent(EditorIntent.Redo) }, enabled = canRedo) {
            Text("Redo")
        }
    }
}
