package com.redcut.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Settings (storage defaults, diagnostics, about).
 *
 * Note where the preferences store lives: `:app` injects a `DataStore<Preferences>`, and
 * this screen will read it through a port defined in a shared module — a feature cannot
 * depend on `:app` (spec §4.1), so the interface has to live below both. That port and
 * its first key arrive with Phase 4.x; until then this screen has nothing to show and
 * says so.
 *
 * The native seam's diagnostic line is deliberately NOT here either: `:feature:*` may not
 * reach `:engine:native` (rule 2), so that status lives in `:app`'s debug strip.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "No settings yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "The preferences store is wired (one DataStore instance, injected). " +
                        "The first key arrives with the export presets in Phase 4.2.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
