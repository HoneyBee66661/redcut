package com.redcut.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the shipped baseline profile (spec §11: "cover app start + first timeline
 * scroll"; §13 task 0.6).
 *
 * WHAT A PROFILE IS, AND WHY THIS TEST IS THE ONLY HONEST WAY TO PRODUCE ONE
 *
 * A baseline profile is the list of classes and methods ART should AOT-compile at install
 * time. Which methods those are is a question about the code that actually runs on the
 * paths that matter, and there is exactly one way to know: run them on a device and
 * record. A hand-written list is a guess that goes stale silently — the profile still
 * ships, still looks like a profile, and covers a screen that was renamed two phases ago.
 *
 * WHAT IS COVERED TODAY, AND WHAT IS NOT
 *
 * Covered: a cold start (`startActivityAndWait`), the composition root's Hilt graph and
 * the Home screen it renders, the Editor destination (its ViewModel, the single immutable
 * UI state, the stage bar), each of the three stage bodies, the Settings destination, and
 * the back navigation between them. Those are the paths Phase 0 has, and they are the
 * ones NFR-1's "cold start < 1.5 s" is measured against.
 *
 * NOT covered yet, and named here so the gap is a known one rather than an oversight:
 * the timeline's scroll path and the import path — neither exists before Phase 1.5 and
 * 1.3. Spec §11 asks for "app start + first timeline scroll"; this file gains the scroll
 * step in the same commit that gives the timeline something to scroll.
 *
 * WHY A MISSING LABEL IS A HARD FAILURE
 *
 * [requireLabel] throws when a label never appears. The alternative — clicking "if
 * present" — turns a renamed button into a thinner profile that still passes, which is
 * precisely the failure mode this module exists to avoid. A red weekly job saying "the
 * label moved" is cheap; a profile that quietly stopped covering the editor is not.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
        // --- Cold start ------------------------------------------------------
        // The path NFR-1 is about. `pressHome()` first so the start is a real one and not
        // a warm resume, which would profile the wrong path and under-report it.
        pressHome()
        startActivityAndWait()

        // --- Home -> Editor --------------------------------------------------
        requireLabel(HOME_OPEN_EDITOR).click()

        // --- The three stages ------------------------------------------------
        // Each is a different body composed under the same stage host (spec §7.1), so each
        // one is a different set of classes for ART to find.
        STAGE_LABELS.forEach { label -> requireLabel(label).click() }

        // --- Editor -> Home -> Settings --------------------------------------
        requireLabel(EDITOR_BACK).click()
        requireLabel(HOME_SETTINGS).click()
        requireLabel(SETTINGS_BACK).click()
    }

    /**
     * Waits for a label to appear and returns its node.
     *
     * The wait is on the accessibility tree's frames, not a sleep: uiautomator polls until
     * the deadline, and the deadline exists because "the screen has not drawn yet" and "the
     * screen never appeared" must not be the same outcome for a generator.
     */
    private fun MacrobenchmarkScope.requireLabel(label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label)), LABEL_TIMEOUT_MS)
            ?: error(
                "Baseline profile generation could not find \"$label\". Either the UI " +
                    "changed and this generator was not updated, or the app never reached " +
                    "that screen.",
            )

    private companion object {
        /** The app under test. Kept in one place: a rename is an edit here, not a hunt. */
        const val TARGET_PACKAGE = "com.redcut.app"

        /** Generous for a debug build on an emulator, and finite on purpose. */
        const val LABEL_TIMEOUT_MS = 5_000L

        // The labels the app owns today (HomeScreen, EditorScreen, SettingsScreen). Literals
        // rather than resource ids because uiautomator matches the accessibility tree, and
        // strings are what a user-facing editor exposes there.
        const val HOME_OPEN_EDITOR = "Open the editor"
        const val HOME_SETTINGS = "Settings"
        const val EDITOR_BACK = "Projects"
        const val SETTINGS_BACK = "Back"

        /** Spec §4.2's three stages, in the order the stage bar shows them. */
        val STAGE_LABELS = listOf("Cut", "Edit", "Effect")
    }
}
