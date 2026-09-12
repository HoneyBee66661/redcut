package com.redcut.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The three-stage shell (spec §4.2, Phase 0 exit criterion). Stages themselves are
 * [RedcutStage].
 *
 * A shell, and it says so: each stage's body is a sentence naming the phase that
 * fills it. The point of landing it now is that the app has a real, running frame
 * — CI assembles it — so every later stage is inserted into a structure that
 * already exists rather than negotiated.
 *
 * The tab row is intentionally NOT the final stage affordance. Phase 1.5 decides
 * how stages are entered once there is a timeline to enter them from, and a bottom
 * NavigationBar would prejudge that. A tab row is the honest placeholder: visible,
 * obviously provisional, zero invented interaction.
 */
@Composable
internal fun StageShell(modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val stage = RedcutStage.entries[selected]

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selected) {
            RedcutStage.entries.forEachIndexed { index, entry ->
                Tab(
                    selected = index == selected,
                    onClick = { selected = index },
                    text = { Text(entry.label) },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stage.detail,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
