package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CutAvailability
import org.junit.Test

/**
 * What the Cut tools' explanation row says (FR-2.2–2.6), tested as plain JVM code.
 *
 * The rule itself is not new; what makes it worth pinning now is that the row it feeds RESERVES its
 * height — see `CutToolReason` — so whether it has something to say decides nothing about how tall the
 * toolbar is. That only holds if "nothing to say" really is the empty string in every case that is not
 * "all six tools, one shared reason", which is what this covers from that side.
 *
 * The reasons are literals because the rule never reads one: it compares them to each other. Which
 * sentence a given tool gives, and when it gives it, is `availabilityFor`'s business and is tested where
 * it lives (`CutToolsTest`, in :domain:document).
 */
class CutToolsReasonTest {

    private val pastEnd = "Move the playhead onto a clip; it is past the end of the timeline."

    private val otherReason = "A different reason, which the row must not print on its own."

    @Test
    fun `all six tools blocked with one reason say that reason`() {
        // The state the device pass crossed into: a playhead at the end of the last clip blocks every
        // tool with the same sentence. Six copies of one sentence are ONE reason, which is what the
        // `distinct` in the rule is for, and it is the only shape that puts a line under the buttons.
        val rows = List(6) { CutAvailability.Unavailable(pastEnd) }

        assertThat(cutToolsReason(rows)).isEqualTo(pastEnd)
    }

    @Test
    fun `one tool that still works leaves the row empty`() {
        // A reason printed under a row where a button is live would explain that button wrongly — and
        // "nothing to say" has to be the empty string rather than the reason, because the row is drawn
        // at the same height either way and the text is what decides which of the two states it is in.
        val rows = List(5) { CutAvailability.Unavailable(pastEnd) } + CutAvailability.Available

        assertThat(cutToolsReason(rows)).isEmpty()
    }

    @Test
    fun `blocked tools that disagree leave the row empty`() {
        // The row speaks for ALL the tools or for none: with two reasons in it, whichever one was
        // printed would be a claim about the buttons that did not give it.
        val rows = listOf(
            CutAvailability.Unavailable(pastEnd),
            CutAvailability.Unavailable(otherReason),
        ) + List(4) { CutAvailability.Unavailable(pastEnd) }

        assertThat(cutToolsReason(rows)).isEmpty()
    }

    @Test
    fun `a row of no tools has nothing to say`() {
        // "Every tool is blocked" is vacuously true of nothing at all, so this is where the rule's two
        // halves could disagree with each other — the shape that would silently hand the row a reason.
        assertThat(cutToolsReason(emptyList())).isEmpty()
    }
}
