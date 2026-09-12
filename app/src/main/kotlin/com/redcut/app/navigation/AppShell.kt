package com.redcut.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.redcut.app.BuildConfig
import com.redcut.engine.nativecore.NativeSeam
import com.redcut.engine.nativecore.NativeStatus

/** The seam proof's operands (spec §6.5), named so the line reads as a claim, not arithmetic. */
private const val SEAM_PROOF_A = 2
private const val SEAM_PROOF_B = 3

/**
 * The app frame: the navigation graph, plus a debug-only status strip.
 *
 * The strip lives here, in `:app`, and not in a feature, because `:engine:native` may
 * only be reached from the composition root (spec §4.1 rule 4). It is the Phase 0 exit
 * criterion's only checkable form on a device — no emulator and no device exist on the
 * development host, so "installing the CI debug APK and reading this line" IS the test
 * for "a native function returns a value".
 *
 * It shows the SUM rather than just a version, because a registered-but-wrong native
 * function would still report a version; a 5 on screen means C++ computed it. It is
 * `BuildConfig.DEBUG`-gated so it can never quietly become product UI — Phase 0.5 moves
 * the diagnostic story to a real screen when there is one worth having.
 */
@Composable
internal fun AppShell() {
    val navController = rememberNavController()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            RedcutNavHost(navController)
        }
        if (BuildConfig.DEBUG) {
            NativeSeamStrip()
        }
    }
}

@Composable
private fun NativeSeamStrip(modifier: Modifier = Modifier) {
    val status = remember { NativeSeam.status() }
    val ready = status is NativeStatus.Ready
    val line = when (status) {
        is NativeStatus.Ready ->
            "native seam ready: v${status.versionCode} · $SEAM_PROOF_A + $SEAM_PROOF_B = " +
                "${NativeSeam.add(SEAM_PROOF_A, SEAM_PROOF_B)}"
        is NativeStatus.Unavailable -> "native seam unavailable — ${status.reason}"
    }

    NavigationBar(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}
