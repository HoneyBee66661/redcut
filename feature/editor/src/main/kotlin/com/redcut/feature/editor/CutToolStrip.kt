package com.redcut.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redcut.domain.document.CutAvailability
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.availabilityFor
import com.redcut.domain.document.clipAt
import com.redcut.domain.document.mergeTrackAvailability

/**
 * The Cut tools (FR-2.2–2.6), drawn as a tool strip rather than a row of text buttons.
 *
 * Every button's enabled state AND its explanation come from `availabilityFor` — the same function the
 * ViewModel's command path consults before it applies anything. One rule, two readers: a button that
 * offered what the command would refuse is the classic way an editor feels broken.
 *
 * ### The LibreCuts pattern, re-stated in Compose
 *
 * The reference build draws this strip as a horizontal `LinearLayout` of 60 dp columns, each column an
 * icon in a 40 dp circle above an 11 sp label. This composable is that layout in Compose's vocabulary
 * — [CutToolButton] is the column — and the mapping is deliberate rather than stylistic: a word per
 * tool (the previous row) could not show six tools and their state at a glance, and the label under an
 * icon is what the user's thumb actually reads. The width and heights are the reference build's, so the
 * strip's density matches the muscle memory the screenshots were approved with.
 *
 * When NOTHING can be cut, the reason is shown. When only some tools are blocked, it is not: the
 * reasons differ per tool (delete is unavailable on the last clip while split is fine), and printing
 * one tool's reason under a row of four would be worse than printing none. That rule is
 * [cutToolsReason]; the line it feeds is [CutToolReason], which is drawn in BOTH cases — see there for
 * why the empty one is not free.
 *
 * Its own file because the strip is a responsibility, not a section: `detekt` measured EditorScreen.kt
 * at 11 functions against the file threshold — the same limit the split that created GestureSession
 * answered — and the strip's composable, its button, its two label helpers and its reason row are one
 * thing that the screen only mounts.
 */
@Composable
internal fun CutTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    // The (lane, clip) the tools act on, resolved ONCE and used by BOTH halves below — the enablement rule
    // and the command a tap builds. That is not tidiness: the playhead is ONE number while the timeline has
    // more than one lane, so a button enabled from the end-to-end reading could offer a cut the lane-scoped
    // command then refuses, which is the failure FR-2's "disable the button and explain why" exists to
    // prevent (WS C6).
    val target = remember(state.document, state.selection, state.playheadUs) {
        cutTargetIn(state.document, state.selection, state.playheadUs)
    }
    val rows = remember(state.document, state.selection, state.playheadUs) {
        CUT_TOOLS.map { tool ->
            tool to cutAvailabilityIn(state.document, target, tool, state.playheadUs)
        }
    }
    val reason = cutToolsReason(rows.map { (_, availability) -> availability })

    Column(modifier = Modifier.fillMaxWidth()) {
        // Horizontally scrollable, CapCut-style, and that is a fix rather than a style choice: six tool
        // columns at 60 dp each are 360 dp of row before padding, and a phone is 360 dp wide, so a plain
        // `Row` overflows and drops the last tools off the edge. A tool strip that cannot show all its
        // tools is the same class of bug as a strip too short to render them — the one the device pass
        // just found.
        //
        // The vertical padding is part of the strip, not of a button, so the columns keep one shared
        // edge — the reference build's `paddingVertical=\"8dp\"` on the LinearLayout.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = TOOL_STRIP_PADDING_V),
        ) {
            rows.forEach { (tool, availability) ->
                ToolButton(
                    icon = tool.icon(),
                    contentDescription = tool.label(),
                    label = tool.label(),
                    enabled = availability is CutAvailability.Available,
                    onClick = {
                        target?.let { onIntent(EditorIntent.ApplyCut(tool, it.clipId)) }
                    },
                )
            }
        }
        CutToolReason(reason)
    }
}

/** The lane and clip a Cut tool acts on, as the strip resolved them. */
private data class CutTarget(val trackId: String, val clipId: String)

/**
 * Which (lane, clip) the Cut tools act on, or null when there is nothing on the timeline to act on.
 *
 * The SELECTED clip wins, and its lane comes from the document — that is the C6 reading, and it is what
 * makes the tools act on what the user is working with rather than on whatever the end-to-end walk finds at
 * the playhead. With nothing selected the playhead decides, which is the behaviour the strip has always
 * had: the strip has to answer *something* before the user has tapped a clip, and "a tool at the playhead"
 * is a question the flat reading can still answer while the timeline's lanes are what the tap clarifies.
 *
 * Its own function rather than three lines inside the composable so the resolution can be read on its own:
 * it is the one place that decides which lane a tool means, and the two callers below are the reason it
 * must be decided once.
 */
private fun cutTargetIn(
    document: EditDocument,
    selection: Selection,
    playheadUs: Long,
): CutTarget? {
    val selected = (selection as? Selection.Clip)?.clipId
        ?.takeIf { document.clipById(it) != null }
    val clipId = selected ?: document.clipAt(playheadUs)?.id ?: return null
    val trackId = document.trackIdOf(clipId) ?: return null
    return CutTarget(trackId, clipId)
}

/**
 * The availability rule the strip's row reads, in the lane-scoped reading whenever a target exists.
 *
 * [target] absent is not a case to fold away: with nothing on the timeline, `availabilityFor`'s flat reading
 * is what produces the SENTENCE the user reads ("Import a video to start cutting."), and the buttons are
 * all disabled, so there is no rule to disagree with.
 */
private fun cutAvailabilityIn(
    document: EditDocument,
    target: CutTarget?,
    tool: CutTool,
    playheadUs: Long,
): CutAvailability = if (target != null) {
    document.availabilityFor(tool, target.trackId, target.clipId, playheadUs)
} else {
    document.availabilityFor(tool, playheadUs)
}

/**
 * The stage's tool row, chosen by WHAT IS SELECTED (UI revision 2, §WS E / Task E3).
 *
 * The plan's E3, and the join between the lane selection and the operations it exists for: a track
 * selected means the lane-level tools, anything else means the clip-level strip. One place decides that,
 * so the two rows can never both be offered or both be missing — a screen that branched on the selection
 * in two places would eventually disagree with itself about which row is showing.
 *
 * Only the Cut stage's body goes through here. Edit and Effect have their own bodies (the inspector, the
 * text tools) and neither has a lane operation to offer yet; when one does, this is the function it joins.
 */
@Composable
internal fun StageTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    if (state.selection is Selection.Track) {
        TrackTools(state = state, onIntent = onIntent)
    } else {
        CutTools(state = state, onIntent = onIntent)
    }
}

/**
 * The LANE tools: what can be done to the whole track the user selected (§WS E / Tasks E2-E3).
 *
 * One button so far, and deliberately not a `TrackTool` enum with one member: `MergeTrackClips` is the
 * single lane operation that exists, and the row's shape — same 60 dp column, same always-two-line reason
 * slot as the clip strip — is what the second one will slot into. `mergeTrackAvailability` is the rule the
 * button reads and `MergeTrackClips` is the command it dispatches, and both call `fuseRuns`, so the button
 * cannot offer a merge the command then refuses: the same one-rule-two-readers property the clip strip has
 * with `availabilityFor`, for the same reason.
 *
 * The reason row is drawn even when the button is enabled, for the reason [CutToolReason] gives: it is a
 * SLOT of fixed height, and a row that appeared and vanished would move the preview above it.
 */
@Composable
internal fun TrackTools(state: EditorUiState, onIntent: (EditorIntent) -> Unit) {
    val trackId = state.selection.trackIdOrNull
    val availability = remember(state.document, trackId) {
        trackId?.let { state.document.mergeTrackAvailability(it) }
            ?: CutAvailability.Unavailable(NO_LANE_SELECTED)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = TOOL_STRIP_PADDING_V),
        ) {
            ToolButton(
                icon = Icons.AutoMirrored.Filled.MergeType,
                contentDescription = MERGE_LANE_LABEL,
                label = MERGE_LANE_LABEL,
                enabled = availability is CutAvailability.Available,
                onClick = { trackId?.let { onIntent(EditorIntent.MergeTrack(it)) } },
            )
        }
        CutToolReason((availability as? CutAvailability.Unavailable)?.reason.orEmpty())
    }
}

/**
 * One tool of a strip: the icon-above-label column, disabled greyed.
 *
 * The dimensions are the reference build's toolbar button — a 60 dp-wide column, a 40 dp icon tile, an
 * 11 sp `sans-serif-medium` label 4 dp under it — because the restyle is a port of a layout the product
 * already approved, not a new design. Disabled draws both halves dimmed at Material's own disabled
 * opacity: a greyed glyph over a full-strength label would disagree with itself about whether the tap
 * does anything.
 *
 * The whole column is the target rather than the icon alone: at 60 dp it is comfortably wider than the
 * 48 dp touch floor, and a tap that lands on the label is a tap on the tool — the mistake a 40 dp icon
 * alone would invite.
 *
 * Takes the icon and the two words rather than a [CutTool], which is what lets the lane strip (WS E3)
 * reuse it without pretending a lane operation is a Cut tool. The clip strip passes its tool's own pair,
 * so nothing about its rendering changed when this became general.
 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    contentDescription: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(TOOL_WIDTH)
            .clip(TOOL_SHAPE)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = TOOL_PADDING_V),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TOOL_ICON_TILE)
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(TOOL_ICON),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OPACITY)
                },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            fontSize = TOOL_LABEL_SP,
            maxLines = 1,
            modifier = Modifier.padding(top = TOOL_LABEL_GAP),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OPACITY)
            },
        )
    }
}

/** A tool's button word. Here rather than in the domain because it is a UI word, not a domain name. */
private fun CutTool.label(): String = when (this) {
    CutTool.SPLIT -> "Split"
    CutTool.CUT_LEFT -> "Cut left"
    CutTool.CUT_RIGHT -> "Cut right"
    CutTool.DELETE -> "Delete"
    CutTool.MERGE -> "Merge"
    CutTool.DUPLICATE -> "Duplicate"
}

/**
 * A tool's strip glyph. Here rather than in the domain for the reason [CutTool.label] is.
 *
 * The auto-mirrored variants are the ones named where the glyph carries a direction: they are the
 * non-deprecated homes of those symbols, and the build compiles with `-Werror`, so the old
 * `Icons.Filled` location is not an option.
 */
internal fun CutTool.icon(): ImageVector = when (this) {
    CutTool.SPLIT -> Icons.AutoMirrored.Filled.CallSplit
    CutTool.CUT_LEFT -> Icons.Filled.ChevronLeft
    CutTool.CUT_RIGHT -> Icons.Filled.ChevronRight
    CutTool.DELETE -> Icons.Filled.Delete
    CutTool.MERGE -> Icons.AutoMirrored.Filled.MergeType
    CutTool.DUPLICATE -> Icons.Filled.ContentCopy
}

/**
 * What the explanation row says: the ONE reason every tool shares, or "" when there is nothing to say.
 *
 * Pulled out of [CutTools] so the rule can be read and tested on its own — it is a fact about a list of
 * availabilities and nothing else, and it has two halves. The line appears only when NOTHING can be cut
 * (a reason under a row where three of four tools still work would misdescribe those three), and only
 * when the tools AGREE on it (delete's reason on a row where split is fine says the wrong thing about
 * split). Six identical sentences are one reason, which is what the `distinct` is for.
 *
 * "" rather than null because the caller draws the row either way: the empty string is what "no reason"
 * looks like in a slot that is always the same size, and [CutToolReason] turns it into blank text.
 */
internal fun cutToolsReason(availabilities: List<CutAvailability>): String {
    val allBlocked = availabilities.none { it is CutAvailability.Available }
    val reasons = availabilities.mapNotNull { availability ->
        (availability as? CutAvailability.Unavailable)?.reason
    }.distinct()
    return if (allBlocked && reasons.size == 1) reasons.first() else ""
}

/**
 * How many `bodySmall` lines the explanation row always occupies.
 *
 * Two, because that is what the longest reason needs on a 360 dp screen at the default font scale: the
 * reasons are sentences ("The timeline must keep at least one clip; delete is unavailable on the last
 * one."), not labels.
 */
private const val REASON_LINES = 2

/**
 * The explanation line: drawn ALWAYS, and always [REASON_LINES] lines tall.
 *
 * It used to be drawn only when there was something to say, and that made the toolbar's height a
 * function of what it said. The reason appears exactly as the playhead leaves the last clip,
 * `BottomToolbar` wraps its content, and the preview and the tracks above it take `weight(1f)` each — so
 * the line appearing pushed both flexible halves up by the height of a line, and the line vanishing
 * dropped them straight back. Dragging a clip's end onto the playhead read as a wobble, which is what
 * the device pass reported: *"saat gua scroll clip ke arah kiri dan end of clip menyentuh playhead, ui
 * agak naik beberapa pixel seperti shaking"*. Nothing was jittering geometrically; a row was being added
 * and removed, and this row is the one that was.
 *
 * So the row is a SLOT, and both of the text's bounds are load-bearing. `minLines` is the half that fixes
 * the reported crossing: it reserves the room in the state where there is nothing to say, so the row is as
 * tall with an empty string in it as it is with a reason. `maxLines` is the ceiling, and without it a
 * longer reason — a bigger font scale, a narrower screen — would push the preview on the way in, which is
 * the same jump arriving from the other side. The ellipsis that ceiling can produce is the deliberate
 * cost of it, and it is the cheaper of the two: a truncated tail at a very large font scale beats a
 * preview that moves under the user's finger.
 *
 * The text is `" "` rather than `""` when there is nothing to say, and that is not cosmetic: Compose
 * measures an EMPTY string as zero height, `minLines` included, so an empty string would hand the jump
 * back in the one state this row exists to hold open. A space is a line with no glyph in it — nothing
 * paints, and the line is measured like any other line of the same style.
 */
@Composable
private fun CutToolReason(reason: String) {
    Text(
        text = reason.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall,
        minLines = REASON_LINES,
        maxLines = REASON_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

private val CUT_TOOLS = listOf(
    CutTool.SPLIT,
    CutTool.CUT_LEFT,
    CutTool.CUT_RIGHT,
    CutTool.DELETE,
    CutTool.MERGE,
    CutTool.DUPLICATE,
)

/**
 * The tool strip's geometry — the reference build's toolbar button, restated (see [CutToolButton]).
 *
 * [TOOL_LABEL_SP] is a `TextUnit` rather than a `dp` because it is a type size, and the strip's height
 * stability the reason row depends on is a function of all of them together: every column is the same
 * fixed stack, so the row is as tall for a disabled tool as for an enabled one.
 */
private val TOOL_WIDTH = 60.dp
private val TOOL_ICON_TILE = 40.dp
private val TOOL_ICON = 24.dp
private val TOOL_LABEL_GAP = 4.dp
private val TOOL_PADDING_V = 6.dp
private val TOOL_STRIP_PADDING_V = 8.dp
private val TOOL_LABEL_SP = 11.sp
private val TOOL_SHAPE = RoundedCornerShape(8.dp)

/** Material's own disabled opacity — the value a disabled `TextButton`'s content draws at. */
private const val DISABLED_OPACITY = 0.38f

/** The lane strip's one button word (WS E3). A UI word here, like [CutTool.label] is. */
private const val MERGE_LANE_LABEL = "Merge lane"

/**
 * What the lane strip says when the selection names no lane.
 *
 * Reachable rather than defensive: `StageTools` chooses the row from a `Selection.Track`, and the
 * selection can be cleared between the frame that drew the row and the tap that reads it — the same
 * stale-state window every other id in this screen has. The button is disabled and this is the sentence
 * that says why, rather than a row that silently does nothing.
 */
private const val NO_LANE_SELECTED = "Tap a lane's background to select it, then merge its clips."
