package com.redcut.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.redcut.app.navigation.AppShell
import com.redcut.core.ui.theme.RedcutTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity.
 *
 * One Activity for the whole app: the stages are Compose state and the four
 * destinations are Compose routes, not Activities, because a video editor must survive
 * a stage switch with the preview surface and the document in memory (FR-6.6).
 * `configChanges` in the manifest keeps a rotation from tearing down the player while a
 * scrub is in flight.
 *
 * `@AndroidEntryPoint` generates the per-Activity injection point; everything else in
 * the graph is reached from a ViewModel, not from here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RedcutTheme {
                AppShell()
            }
        }
    }
}
