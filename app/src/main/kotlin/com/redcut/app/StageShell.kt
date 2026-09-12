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
 * The three stages of the product (spec §4.2, Phase 0 exit criterion).
 *
 * This is a shell and says so: each stage's body is a sentence naming the phase
 * that fills it. The point of landing it now is that the app has a real, running
 * frame — CI assembles it, and every later stage is inserted into a structure
 * that already exists rather than renegotiated.
 *
 * The tab row is intentionally NOT the final stage affordance. Phase 1.5 decides
 * how stages are entered once there is a timeline to enter them from, and a
 * bottom NavigationBar would prejudge that. A tab row is the honest placeholder:
 * visible, obviously provisional, zero invented interaction.
 */
internal enum class RedcutStage(
    val label: String,
    val detail: String,
) {
    Cut(
        label = "Cut",
        detail = "Import, trim, split, reorder.\n" +
            "The timeline canvas lands in Phase 1.5; trim and split gestures in 1.6-1.9.",
    ),
    Edit(
        label = "Edit",
        detail = "Speed, volume, fades, rotate, crop, reverse.\n" +
            "The inspector framework lands in Phase 2.1; the tools in 2.2-2.5.",
    ),
    Effect(
        label = "Effect",
        detail = "LUTs, colour, text, image overlays, cross-dissolve.\n" +
            "The native GL effect pipeline lands in Phase 3.1-3.9.",
    ),
}

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
