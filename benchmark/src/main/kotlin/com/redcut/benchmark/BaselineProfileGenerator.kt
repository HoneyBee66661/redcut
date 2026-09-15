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
 * Covered: a cold start (`startActivityAndWait`), the composition root's Hilt graph and the
 * project gallery the Home destination renders — its header, the new-project control that
 * opens a project, and the project grid — the Editor destination that control leads to (its
 * ViewModel, the single immutable UI state, the bar that exits and exports, the transport
 * strip, the tracks, the stage tabs and each of the three stage bodies), the Settings
 * destination, and the back navigation between them. Those are the paths Phase 0 has, and
 * they are the ones NFR-1's "cold start < 1.5 s" is measured against.
 *
 * NOT covered yet, and named here so the gap is a known one rather than an oversight: the
 * timeline's SCROLL. Spec §11 asks for "app start + first timeline scroll", and this walk
 * reaches the timeline without ever scrolling it, because it never puts a clip on one — an
 * empty timeline has no content to pan. Clips arrive through the SAF picker (FR-1.1), and
 * that picker is the system's DocumentsUI rather than this app: driving its chrome from here
 * would trade this file's one honest signal (a label moved) for a step that breaks when the
 * picker's does. The scroll step lands with a walk that can put a clip on the timeline
 * without one.
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
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        // Emits STARTUP profile rules as well as baseline ones, and this is the half NFR-1
        // is about: the baseline profile AOT-compiles the paths that run, while the startup
        // profile is what the platform uses during the cold-start window. The first CI run
        // of this generator produced baseline rules only and said so:
        //   "No startup profile rules were generated for the variant `release` ... because
        //    there are no instrumentation tests with baseline profile rule which specify
        //    `includeInStartupProfile = true`."
        // A profile that speeds up the second launch and not the first is the wrong half.
        includeInStartupProfile = true,
    ) {
        // --- Cold start ------------------------------------------------------
        // The path NFR-1 is about. `pressHome()` first so the start is a real one and not
        // a warm resume, which would profile the wrong path and under-report it.
        pressHome()
        startActivityAndWait()

        // --- Home (the project gallery) -> Editor ----------------------------
        // The new-project control is the entry: it is what creates a project and opens the
        // editor, so it is the one control on this screen that leads anywhere. Nothing here
        // touches a project card — the names in that grid are placeholder data, and a walk
        // that clicked one would break when the placeholder does.
        requireLabel(HOME_OPEN_EDITOR).click()

        // --- The three stages ------------------------------------------------
        // Each is a different body composed under the same stage host (spec §7.1), so each
        // one is a different set of classes for ART to find.
        STAGE_LABELS.forEach { label -> requireLabel(label).click() }

        // --- Editor -> Home -> Settings --------------------------------------
        requireLabel(EDITOR_BACK).click()
        // By description rather than by text: this one is an icon, and an icon draws no word
        // for `By.text` to find. See [requireDescription].
        requireDescription(HOME_SETTINGS).click()
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

    /**
     * Waits for a content DESCRIPTION to appear and returns its node.
     *
     * The same wait and the same hard failure as [requireLabel], for the controls that are
     * reachable only this way. The gallery's settings entry is an `IconButton` holding an `Icon`,
     * and the only label it has is that `Icon`'s `contentDescription` — the accessibility tree
     * carries no word for it, so `By.text` searches for something that was never there and times
     * out on a control that is on screen and working. `By.desc` asks the tree the question the
     * control can actually answer.
     *
     * This is not a lenient second chance for a label that moved: the two helpers ask DIFFERENT
     * questions, and neither falls back to the other. A walk that reached for this one where the
     * screen draws text would be looking for the wrong node rather than for the right one by
     * another name.
     *
     * The sentence it fails with is deliberately the same shape as [requireLabel]'s, so a red job
     * names the description it could not find in the words the reader already knows how to act on.
     */
    private fun MacrobenchmarkScope.requireDescription(description: String): UiObject2 =
        device.wait(Until.findObject(By.desc(description)), LABEL_TIMEOUT_MS)
            ?: error(
                "Baseline profile generation could not find \"$description\". Either the UI " +
                    "changed and this generator was not updated, or the app never reached " +
                    "that screen.",
            )

    private companion object {
        /** The app under test. Kept in one place: a rename is an edit here, not a hunt. */
        const val TARGET_PACKAGE = "com.redcut.app"

        /** Generous for a debug build on an emulator, and finite on purpose. */
        const val LABEL_TIMEOUT_MS = 5_000L

        // The words the screens carry, each named with the SCREEN that owns it. They are not
        // stable literals: they are what a user-facing editor exposes to the accessibility tree,
        // and they move whenever the UI does — which is what happened to this list. UI revision 1
        // replaced Home with a project gallery and renamed every one of them, and this file went
        // on matching a screen that no longer existed.
        //
        //   HOME_OPEN_EDITOR   the gallery's new-project button   feature/home HomeScreen
        //   HOME_SETTINGS      the gallery's header settings icon feature/home HomeScreen
        //   EDITOR_BACK        the editor bar's exit button       feature/editor EditorLayout
        //   SETTINGS_BACK      the settings bar's back button     feature/settings SettingsScreen
        //
        // Literals rather than resource ids because uiautomator matches the accessibility tree —
        // and text is not the only thing it exposes there: HOME_SETTINGS is an `IconButton`'s
        // `contentDescription` and is matched by [requireDescription], because an icon-only
        // control draws no word for `By.text` to find.
        const val HOME_OPEN_EDITOR = "Open New Project"
        const val HOME_SETTINGS = "Settings"
        const val EDITOR_BACK = "Exit"
        const val SETTINGS_BACK = "Back"

        /** Spec §4.2's three stages, in the order the stage bar shows them. */
        val STAGE_LABELS = listOf("Cut", "Edit", "Effect")
    }
}
