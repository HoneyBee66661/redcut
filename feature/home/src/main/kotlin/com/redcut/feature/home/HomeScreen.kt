package com.redcut.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Thumbnail aspect ratio for the project cards: 16:9. */
private const val THUMBNAIL_ASPECT_RATIO = 16f / 9f

@Composable
fun HomeScreen(onOpenEditor: () -> Unit, onOpenSettings: () -> Unit) {
    // Temporary fake data.
    // Replace this with your project repository/state later.
    val projects = listOf(
        ProjectPlaceholder("Project 01", "2 days ago"),
        ProjectPlaceholder("Project 02", "Yesterday"),
        ProjectPlaceholder("Project 03", "5 days ago"),
        ProjectPlaceholder("Project 04", "Last week"),
        ProjectPlaceholder("Project 05", "Last week"),
        ProjectPlaceholder("Project 06", "2 weeks ago"),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ─────────────────────────────
        // Header
        // ─────────────────────────────
        HomeHeader(onOpenSettings = onOpenSettings)

        Spacer(modifier = Modifier.height(8.dp))

        // ─────────────────────────────
        // New Project Button
        // ─────────────────────────────
        Button(
            onClick = onOpenEditor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )

            Spacer(modifier = Modifier.size(8.dp))

            Text("Open New Project")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ─────────────────────────────
        // Project section
        // ─────────────────────────────
        Text(
            text = "Projects",
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ─────────────────────────────
        // Project Grid
        // ─────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(projects) { project ->
                ProjectCard(project)
            }
        }
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "RedCut",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
            )
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectPlaceholder) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Temporary thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(THUMBNAIL_ASPECT_RATIO)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "THUMBNAIL",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = project.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
