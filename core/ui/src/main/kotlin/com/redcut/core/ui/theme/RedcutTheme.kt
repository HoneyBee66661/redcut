package com.redcut.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The RedCut theme.
 *
 * The palettes are the Material 3 *baseline* schemes, and that is a deliberate
 * choice rather than an unfinished one: RedCut has no design pass yet, so any
 * hand-picked ramp today would be a set of tokens that every screen gets built
 * against and that the real design (Phase 2.1, the inspector framework, is the
 * first place colour matters) then replaces. One file changes when that happens.
 *
 * Dynamic colour (Material You) is off. A video editor needs a *neutral* chrome:
 * a wallpaper-derived accent next to a frame the user is colour-grading is
 * actively harmful, because it makes an untinted reference surface look tinted.
 * That is the one place this module overrides the platform default, and it is
 * worth the sentence.
 *
 * Typography and shape are left at the Material defaults for the same reason as
 * the palette: they are the design system's business and there is no design
 * system yet.
 */
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun RedcutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

