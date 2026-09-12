package com.redcut.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.redcut.feature.editor.EditorRoute
import com.redcut.feature.export.ExportScreen
import com.redcut.feature.home.HomeScreen
import com.redcut.feature.settings.SettingsScreen

/**
 * The navigation graph (spec §7.1).
 *
 * The composition root owns the graph, and features stay unaware of each other: a
 * feature exposes a screen that takes callbacks, and `:app` decides where those
 * callbacks lead. That is what makes "features never depend on each other" (spec §4.1
 * rule 2) enforceable instead of aspirational — a `HomeScreen` that could navigate to
 * the editor itself would need to import it.
 *
 * The stages are NOT routes (spec §7.2): switching Cut/Edit/Effect is state inside the
 * editor, which is why `:feature:editor` exposes exactly one destination.
 */
@Composable
internal fun RedcutNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenEditor = { navController.navigate(Routes.EDITOR) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.EDITOR) {
            EditorRoute(
                onExport = { navController.navigate(Routes.EXPORT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EXPORT) {
            ExportScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
