package com.redcut.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.redcut.core.ui.theme.RedcutTheme

/**
 * The single Activity.
 *
 * One Activity for the whole app: the stages are Compose destinations, not
 * separate Activities, because a video editor must survive stage switches with
 * the preview surface and the document in memory (FR-6.6). `configChanges` in
 * the manifest keeps a rotation from tearing down the player while a scrub is in
 * flight.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RedcutTheme {
                StageShell()
            }
        }
    }
}
