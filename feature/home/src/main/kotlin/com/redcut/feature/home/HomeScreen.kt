package com.redcut.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The project list (FR-6.1–6.4, Phase 4.7).
 *
 * Stateless and callback-driven on purpose: it receives where to go and does not know
 * what an editor is, which is what makes "features never depend on each other"
 * (spec §4.1 rule 2) a compile-time fact rather than a convention. Every navigation in
 * this app passes through `:app`.
 *
 * The empty state is the only state, and says so: there is no project storage yet, so
 * "No projects" is not a loading artefact — it is the truth, and naming the phase that
 * changes it is more useful to the next reader than a spinner.
 */
@Composable
fun HomeScreen(onOpenEditor: () -> Unit, onOpenSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "RedCut", style = MaterialTheme.typography.headlineSmall)
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "No projects yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Importing media lands with the Cut stage (Phase 1.3); project " +
                        "storage, rename and autosave with Phase 4.7.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onOpenEditor) { Text("Open the editor") }
            }
        }
    }
}
