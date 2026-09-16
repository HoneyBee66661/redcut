package com.redcut.feature.editor.timeline

import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.timeline.ClipTiming
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.core.common.timeline.spansOf
import com.redcut.domain.document.ClipEdge
import com.redcut.feature.editor.EditorIntent
import org.junit.Test

/**
 * The text lane's hit tests (FR-4.3's card 4): which press opens an edge drag, and what a tap on the
 * band means.
 *
 * The same reasoning `TimelineTapTest` gives for existing: the two ways to get a hit test wrong are both
 * quiet. An edge zone too wide eats the body's tap; too narrow and the edge cannot be grabbed at all. A
 * tap that cleared the selection on the band would close the inspector every time the user reached for a
 * caption's edge and stopped short.
 *
 * `textLaneEdgeHit` and `onTextLaneTap` are plain functions over the geometry and a lane value — no
 * Compose runtime, no finger: the answers and the intents ARE the behaviour. The y is LANE space (the
 * ruler's strip already taken off), the same convention the clips' hit tests keep.
 */
class TextLaneHitTest {

    private val oneSecond = 1_000_000L

    /** The Density the dp-to-px conversions run against: one dp is one px, like the geometry's own. */
    private val density = Density(1f)

    /** Two one-second clips at 60 px/s, so the media lanes fill x 0..120. */
    private fun geometry(): TimelineGeometry = TimelineGeometry(
        viewportWidthPx = 400f,
        spans = spansOf(listOf(ClipTiming("clip-0", oneSecond), ClipTiming("clip-1", oneSecond))),
        zoom = TimelineZoom(60f),
        scrollPx = 0f,
        density = 1f,
    )

    /**
     * One caption spanning 0.5s..1.5s: x 30..90 at this zoom. On the band BELOW the one media lane, so a
     * y in the band is a y in no clip lane's.
     */
    private fun lane(): TextLane = TextLane(
        topPx = TimelineGeometry.TRACK_HEIGHT_DP,
        heightPx = TimelineGeometry.TRACK_HEIGHT_DP,
        items = listOf(
            TextLaneItem(
                effectId = "t1",
                content = "word",
                startUs = oneSecond / 2,
                endUs = oneSecond * 3 / 2,
            ),
        ),
    )

    private fun hitAt(screenX: Float, screenY: Float): Pair<TextLaneItem, ClipEdge>? =
        density.textLaneEdgeHit(screenX, screenY, lane(), geometry())

    private fun intentsFor(screenX: Float, selectedTextId: String?): List<EditorIntent> {
        val sent = mutableListOf<EditorIntent>()
        onTextLaneTap(
            screenX = screenX,
            lane = lane(),
            geometry = geometry(),
            onIntent = sent::add,
            selectedEffectId = selectedTextId,
        )
        return sent
    }

    @Test
    fun `a press just inside a caption's left edge is the IN edge`() {
        // 35 px is 5 px into the item's body; the zone is a third of the 60 px body (capped at the 24 dp
        // touch floor, which does not bind here), so x 30..50 is IN.
        assertThat(hitAt(35f, LANE_Y)).isEqualTo(lane().items.single() to ClipEdge.IN)
    }

    @Test
    fun `a press just inside a caption's right edge is the OUT edge`() {
        assertThat(hitAt(85f, LANE_Y)).isEqualTo(lane().items.single() to ClipEdge.OUT)
    }

    @Test
    fun `a press on a caption's body is not an edge, so the tap can have it`() {
        // The body is the tap's and the scroll's; a middle that opened a trim would make half the caption
        // untappable, the failure the clips' edge cap exists to prevent.
        assertThat(hitAt(60f, LANE_Y)).isNull()
    }

    @Test
    fun `a press outside every caption and off the band is no edge at all`() {
        assertThat(hitAt(200f, LANE_Y)).isNull()
        assertThat(hitAt(60f, LANE_Y - 1f)).isNull()
        assertThat(hitAt(60f, lane().bottomPx + 1f)).isNull()
    }

    @Test
    fun `a tap on a caption selects it, and on the band's empty stretch clears`() {
        assertThat(intentsFor(60f, selectedTextId = null))
            .containsExactly(EditorIntent.SelectTextOverlay("t1"))
        assertThat(intentsFor(200f, selectedTextId = "t1"))
            .containsExactly(EditorIntent.ClearSelection)
    }

    @Test
    fun `a tap on the selected caption deselects it`() {
        // The same toggle the clips keep: without it there is no way to say "nothing is selected", and
        // the inspector would keep styling a caption the user has moved on from.
        assertThat(intentsFor(60f, selectedTextId = "t1"))
            .containsExactly(EditorIntent.ClearSelection)
    }
}

/** The middle of the text band: half a track height below the one media lane, at density 1. */
private const val LANE_Y = TimelineGeometry.TRACK_HEIGHT_DP * 1.5f
