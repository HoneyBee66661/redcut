package com.redcut.feature.export

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
 * The export sheet (FR-5.x, Phase 4.x).
 *
 * A destination now and a screen later: what matters at Phase 0.5 is that export is
 * reachable and that the reach runs through `:app`'s graph rather than through a feature
 * importing another feature. The real work — resolution presets, bitrate, progress, ETA,
 * cancel, preflight — is Phase 4.1-4.5, and the export itself must go through a
 * foreground service that owns `:engine:media3` (§4.2), never through this screen.
 */
@Composable
fun ExportScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = "Export",
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
                Text(text = "Nothing to export yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "720p/1080p presets, progress and MediaStore publishing land in " +
                        "Phase 4.1-4.5. The preflight check (storage, codec availability, " +
                        "estimated size) is 4.5.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
