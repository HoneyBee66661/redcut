# UI revision 2 + the tracks project — implementation plan

> **For Hermes:** planning only. No code in this document has been written yet. Execute task-by-task; each
> task is one commit on its own branch, PR → CI → rebase-merge, as the repo's guardrails require.

**Goal:** turn the timeline from "one clip list drawn in a canvas" into a fixed-height, gutterless,
multi-track body whose horizontal axis is a quantisation of TIME — with the audio of every imported video
appearing as its own track, track-level selection for cross-clip operations, and Import/Export back to the
original design (resolution moving into an Export popup).

**Architecture:** the document grows a TRACK concept (schema v2, migration from v1). The timeline's geometry
stays the single source of truth for time↔pixel mapping; the viewport stays derived from the playhead
(UI revision 1's model, confirmed twice by the user). Audio is a DERIVED track that references the source's
audio stream rather than a transcoded file. All new pure logic lands in `:core:common` / `:domain:*` so it is
testable on this host; only Compose and Media3 work needs CI.

**Tech stack:** Kotlin, Compose (Material3), Media3 1.9.4 (declared in the catalog; `:engine:media3` is where
it will be used), kotlinx.serialization, JUnit5 + Truth, Gradle (JDK 17), GitHub Actions.

---

## 1. What each of the user's statements means, in this codebase's terms

Twelve statements arrived in one message. Several are ambiguous in a way that changes the design, so each is
resolved here explicitly — with the reading I will build unless the user corrects it.

| # | The user's words (verbatim, trimmed) | Reading I will build | Confirm? |
|---|---|---|---|
| 1 | *tombol import dan export kembali seperti desain awal, hilangkan tombol resolusi* | Top bar shows `Import` and `Export` again; the `Resolution` toggle is deleted. Resolution becomes a setting inside an Export popup. | no |
| 2 | *tinggi track body timeline fixed, tidak fitting container* | Each track row has a FIXED height (`TRACK_HEIGHT = 56.dp`), independent of the canvas height. Extra vertical space shows empty track, not taller clips. | no |
| 3 | *hilangkan outline container* | The `border(...)` added in the previous round goes. | no |
| 4 | *body track mentok kiri, namun bisa off screen kanan* | The 10 % left gutter goes: the track body starts at x = 0. Content scrolls right-to-left freely; there is no right-hand clamp. | no |
| 5 | *body track mentok kiri, namun bisa off screen kanan* + *left clip head saat clip discroll ke kanan, berhenti di playhead. jadi gak ilang ke off screen* | The 10 % left gutter goes: the track body starts at x = 0. Content scrolls right-to-left freely; there is no right-hand clamp. The content's LEFT EDGE (the head of the first clip, i.e. time 0) may travel right only as far as the playhead, so the beginning of the timeline never leaves the screen on the right. **This is already the model**: `scrollCentering(0) = -viewportWidth/2` puts time 0 under the playhead, and because a drag moves the PLAYHEAD (bounded to `0..duration`), the head cannot pass it. Only the gutter changes; the invariant gets its own test in A1. | **confirmed by the user** |
| 5b | *left clip head bakal mentok mid playhead saat di scroll ke kanan dan sebaliknya bagi clip tail?* | **Symmetry confirmed, option a (2026-09-13)**: the line is the centre at BOTH ends — scrolling left, the last frame stops under the line too. The cost is accepted explicitly: half the viewport is empty past each end. Scope is the TIMELINE's first and last frame, NOT each clip (an interior clip's head/tail pass the line freely, or mid-timeline cuts would be impossible). Both halves are now tests: `TimelineGeometryTest` head + tail mirror, tail landed as PR #31 / `9d5aa82`, pinned for content longer *and* shorter than the viewport. | decided |
| 6 | *beri opsi select track in case clipnya split banyak, tujuannya biar bisa merge dll* | A `Selection.Track(trackId)` alongside `Selection.Clip`; tapping the track's lane background selects the TRACK — DECIDED by the user: *"gue ikut rekomendasi lu"*, and the Cut tools then act across its clips (merge, delete all, …). | no |
| 7 | *auto extract audio menjadi track 2* | On import, if the source has audio, a second track appears holding an audio clip spanning the same range. **Modelled as a reference to the source's audio stream, not a transcoded file** — DECIDED by the user: *"referensi aja"* (see §4.3). | decided |
| 8 | *timeline body zone adalah quantisasi waktu sehingga ada stretch effect … applied independently on each clip instead of full track body* | The horizontal axis is time-per-pixel (a quantisation of time, `TimelineZoom.pixelsPerSecond`); a clip's WIDTH is derived from ITS OWN duration, so a clip stretched by speed/trim changes width on its own. Zooming scales the mapping, not a bitmap — which is why there is no image zoom in the body. | **yes** |
| 9 | *definisi clip adalah bagian timeline body, by default saat import kita hanya punya 1 clip, and 1 audio, saat kita split maka clip jadi 2 independent entity* | A clip is a span of a track. First import: one video clip on track 1, one audio clip on track 2. Split produces two independent clips (each with its own id, in/out, effects) — already true today for video. | no |
| 10 | *di bagian clip tidak ada fungsi zoom gambar namun zoom waktu dari detik menjadi 0.5 lalu max per frame step* + *paling coarse 1 detik, 0.5, 0.1, per frame* + *shrink max per 1 hr, incase video is hours long* | The zoom is a time quantisation, and its ladder is DECIDED: coarsest granularity **1 s**, then **0.5 s**, then **0.1 s**, then **one frame** at the tight end. The coarsest zoom-out is set so an hour-long project is manageable on a screen (§4.4). | decided |
| 11 | *beri ruler indikator waktu juga di top clip untuk memudahkan cut ops dan keyframing* | The ruler's ticks/labels are drawn a second time along the TOP EDGE of the clip band (inside the body), so a cut point can be read against a label without looking up at the ruler strip. | no |
| 12 | *zoom visual hanya pada area top 50% screen dengan catatan, clip sedang diselect. ada juga snap vertical horizontal center* | The preview viewport of UI revision 1: a rect with a LOCKED aspect ratio, pinch to zoom, drag to pan, and snapping to the horizontal/vertical centre when a clip is selected; inert when nothing is selected. | no |

## 2. Current context (verified on this host)

* `EditDocument` (schema v1): `clips: List<Clip>` — a flat, ordered list. **No tracks.** `SourceRef` already
  carries `hasAudio`, `rotationDegrees`, `frameRate`, so the probe knows what it needs for §7.
* `Clip`: `id, sourceId, sourceInUs, sourceOutUs, speed, reverse, volume, muted, fadeInMs, fadeOutMs,
  transform`. No `trackId`. Split already produces two independent clips.
* `TimelineZoom`: `MINIMUM = 2 px/s`, `MAXIMUM = 480 px/s`, `DEFAULT = 60 px/s`, `STEP_FACTOR = 1.5`.
* Timeline geometry (40 tests): `pxFor/usFor/contentPxFor/visibleStartPx/scrollCentering/centredPlayheadPx/
  hitTest/rulerTicks/visibleRects`. The viewport is DERIVED from the playhead and unclamped — the model the
  user has now confirmed twice.
* Layout: `EditorLayout.kt` (top bar, transport, tracks slice with a 10 % gutter + outline, wrapping
  toolbar) and `EditorScreen.kt` (Cut tools, import banner, route). `AppShell.kt` carries the thin debug strip.
* Audio: **nothing exists.** No extraction, no audio track, no Media3 usage in feature code; Media3 1.9.4 is
  declared in `gradle/libs.versions.toml`.
* Board: 12 ready tasks, including `t_03adef53` ("Document schema v2: tracks instead of a flat clip list,
  plus the migration") and `t_b2bd5d28` ("preview viewport rect") — this plan refines both and adds the rest.

## 3. Workstreams, in the order they should land

Each workstream is independently shippable and its own PR. A and B are small and unblock the user's next
device pass; C/D/E are one project (the schema); F is independent and can go in parallel.

| WS | Name | Tasks | Why in this order |
|---|---|---|---|
| A | The track body becomes what the user asked for | A1–A5 | Cheapest, biggest visible delta, no schema risk |
| B | Import/Export back, resolution into the popup | B1–B2 | Removes a control the user explicitly rejected |
| C | Schema v2: tracks | C1–C6 | Prerequisite for D and E; touches persistence, so it needs care |
| D | Audio on track 2 | D1–D4 | Depends on C |
| E | Track selection + cross-clip operations | E1–E3 | Depends on C |
| F | Preview viewport (visual zoom) | F1–F4 | Independent of C/D/E |

---

## Workstream A — the track body

### Task A1: Track height is a constant, not a share of the container

**Objective:** a track row is always `TRACK_HEIGHT` regardless of how tall the canvas slice is.

**Files:**
- Modify: `core/common/src/main/kotlin/com/redcut/core/common/timeline/TimelineGeometry.kt` — add
  `val trackHeightPx: Float get() = TRACK_HEIGHT_DP * density` and a `Track` rect derived from it.
- Modify: `feature/editor/src/main/kotlin/com/redcut/feature/editor/timeline/TimelineDraw.kt` — `Track` takes
  the fixed height instead of the canvas height.
- Test: `core/common/src/test/kotlin/com/redcut/core/common/timeline/TimelineGeometryTest.kt`

**Step 1:** failing test — `a track is TRACK_HEIGHT_DP tall whatever the canvas is`:

```kotlin
@Test
fun `a track is a fixed height whatever the canvas is`() {
    // The geometry has no viewport HEIGHT — it maps x. The constant lives here so the fast tier can test it
    // and the drawing layer reads it, which is the same "one rule, one place" the ranges in ClipRanges get.
    assertThat(geometry().trackHeightPx).isEqualTo(56f)          // 56 dp at density 1
    assertThat(geometry(density = 2f).trackHeightPx).isEqualTo(112f)
}

@Test
fun `the first clip's head stops at the playhead when the body is scrolled right`() {
    // The user, twice: "left track body max mentok playhead", then "left clip head saat clip discroll ke
    // kanan, berhenti di playhead. jadi gak ilang ke off screen". Both are this invariant: time 0 can reach
    // the playhead and no further, so the beginning of the timeline never leaves the screen on the right.
    val geometry = geometry(spans = listOf(ClipSpan("a", 0, 10 * oneSecond)))
    val scrolled = geometry.copy(scrollPx = geometry.scrollCentering(0))

    assertThat(scrolled.visibleStartPx).isEqualTo(-geometry.viewportWidthPx / 2f)
    assertThat(scrolled.pxFor(0)).isEqualTo(geometry.viewportWidthPx / 2f)
}
```

**Step 2:** run `./gradlew :core:common:test --tests '*TimelineGeometryTest*'` → expect FAIL (no such member).
**Step 3:** implement (add the parameter to the geometry's constructor with a default; the Canvas passes its
own height for the RULER only).
**Step 4:** re-run → PASS.
**Step 5:** commit `Timeline: fixed track height, not a share of the canvas`.

**Verification:** `./tools/verify.sh` green; 41 geometry tests.

**Status (2026-09-13):** the fixed `trackHeightPx` and the head invariant are on `main`; the TAIL half of the
invariant landed as PR #31 / `9d5aa82` (45 geometry tests, 0 failures; a clip shorter than the viewport is
covered too). What is LEFT of A1 is the `:feature:editor` side — `TimelineDraw`'s `Track` rect taking the
fixed height instead of the canvas height.

### Task A2: Remove the gutter and the container outline

**Files:** `feature/editor/src/main/kotlin/com/redcut/feature/editor/EditorLayout.kt` (delete
`TRACKS_LEFT_INSET_FRACTION`, `TRACKS_TOP_PADDING`, the `.border(...)`, and the `LocalConfiguration` read).

**Step 1:** no test (layout only) — but add a comment stating the reason with the user's words.
**Step 2:** `./gradlew :feature:editor:ktlintCheck :feature:editor:detekt` → PASS.
**Step 3:** commit `Timeline: flush left, no container outline`.

### Task A3: The ruler repeats along the clip's top edge

**Files:** `feature/editor/src/main/kotlin/com/redcut/feature/editor/timeline/TimelineDraw.kt`; test in
`:core:common` if the tick selection moves (it does not — reuse `rulerTicks()`).

**Step 1:** failing test is not possible for drawing; instead assert the DECISION:
`a clip's top labels use the same ticks as the ruler` (a small pure function `ticksForClipTop(ticks, rect)`
that filters to the clip's span) → testable in `:core:common`.
**Step 2–4:** implement + run.
**Step 5:** commit.

### Task A4: Zoom range stated in time

**Objective:** implement the DECIDED ladder — granularities {frame, 0.1 s, 0.5 s, 1 s, …} all reachable, an hour at the coarsest — and NAME the boundaries in time terms.

**Files:** `core/common/src/main/kotlin/com/redcut/core/common/timeline/TimelineZoom.kt` (extract to its own
file) + test.

The ladder is DECIDED by the user (*1 detik, 0.5, 0.1, per frame*) and the zoom-out limit by *"shrink max per 1 hr, incase video is hours long"*. The table below is the arithmetic that follows from those words, plus the culling requirement that comes with a range this wide:

| | pixelsPerSecond | one second is | one frame (60 fps) is | granularity the ruler shows |
|---|---|---|---|---|
| MINIMUM (coarsest) | 0.1 | 0.1 px | 0.0017 px | minutes — an HOUR is 360 px, so an hour-long project is one screen (*"shrink max per 1 hr"*) |
| a long-project stop | 6 | 6 px | 0.1 px | 10 s |
| DEFAULT | 60 | 60 px | 1 px | 1 s |
| closer | 600 | 600 px | 10 px | 0.1 s |
| MAXIMUM (finest) | 480 | — | 8 px | one frame — *"per frame step"* |

Note the non-monotonic last two rows: 600 px/s puts a frame at 10 px, which is CLOSER than 480 px/s at 8 px,
so `MAXIMUM` is 480 only if the ruler's frame granularity is the ordering criterion. **The rule to implement
is the ladder, not the number**: every granularity in the user's list ({frame, 0.1 s, 0.5 s, 1 s, …}) must be
reachable, and the ruler must never crowd below `MIN_TICK_SPACING_PX`. So `MINIMUM = 0.1f` (the hour),
`MAXIMUM = 480f` (a frame at 8 px), `DEFAULT = 60f`, and `rulerIntervalUs()` grows the ladder
`frame → 100 ms → 500 ms → 1 s → 2 s → 5 s → 10 s → 30 s → 1 min → …` as the zoom coarsens.

**Step 1:** failing tests — `the coarsest zoom still separates seconds` (`MINIMUM.pixelsPerSecond >= 24f`),
`the finest zoom makes a frame easier to see than a pixel` (`MINIMUM` of a frame `> 4f`), and
`the range is at least a factor of 16`.
**Steps 2–4:** implement; run.
**Step 5:** commit.

### Task A5: The body's empty space is drawn as track, and off-screen clips are not drawn at all

**Objective:** two things that come as a pair once the zoom range is this wide.

1. **Empty lane, drawn.** With a fixed track height and a centred playhead, the area before time 0 and after
   the last clip is visibly empty lane — the boundary the user needs to see when dragging. `laneRects()`
   spans the whole visible window.
2. **Off-screen bodies culled.** *"clip body off screen not rendered for optimization"* — at 0.1 px/s an
   hour of timeline is one screen and a project may hold hundreds of clips, so the draw pass must build rects
   only for what is near the viewport. `visibleRects()` already does this with a screen of margin; this task
   makes it the STATED contract and tests it:
   `a clip entirely off screen is not returned` and `a clip overlapping the margin is returned once`.

**Files:** `core/common/.../TimelineGeometry.kt` (`laneRects`, `visibleRects`), `TimelineDraw.kt`.

---

## Workstream B — the top bar

### Task B1: Import and Export return; Resolution goes

**Files:** `feature/editor/src/main/kotlin/com/redcut/feature/editor/EditorLayout.kt` (`TopBar`).
**Board:** complete `t_8d1c202c` by deleting it, with the note that resolution moves to the export popup.

### Task B2: The Export popup takes the resolution

**Objective:** export opens a dialog: resolution (1080×1920 / 720×1280 / source), format, and a Start button
that is inert until Phase 5's encoder exists.

**Files:** new `feature/editor/src/main/kotlin/com/redcut/feature/editor/ExportSheet.kt`; state in
`EditorUiState` (`exportSheet: ExportSheet?`), intents (`OpenExport`, `DismissExport`, `SetExportResolution`).
**Test:** a VM test that `OpenExport` with clips opens it and with none does not; a pure test of the
resolution-to-size mapping in `:domain:render`.

---

## Workstream C — schema v2: tracks

### Task C1: `Track` in the document, with a kind

**Files:**
- Create: `domain/document/src/main/kotlin/com/redcut/domain/document/Track.kt`
- Modify: `EditDocument.kt` — `tracks: List<Track> = listOf(Track.VIDEO_MAIN)`; `SCHEMA_VERSION = 2`; keep
  `clips` as a DERIVED view (`val clips: List<Clip> get() = tracks.flatMap { it.clips }`) so every existing
  caller keeps working and the render graph is untouched in this task.
- Test: `domain/document/src/test/kotlin/com/redcut/domain/document/TrackTest.kt`

```kotlin
@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val clips: List<Clip> = emptyList(),
)

@Serializable
enum class TrackKind { VIDEO, AUDIO, OVERLAY, EFFECT }
```

**Step 1:** failing tests — a document with no tracks reads as one empty video track; `clips` flattens in
track order; an audio track's clips do not appear in the video track's order.
**Step 2:** `./gradlew :domain:document:test` → FAIL.
**Step 3:** implement.
**Step 4:** PASS (the existing 150+ document tests must stay green — that is the real assertion of this task).
**Step 5:** commit.

### Task C2: Clips become track-scoped in every command

**Objective:** `AddClip`, `AppendClip`, `SplitClip`, `DeleteClip`, `ReorderClip` name a track; ripple rules
stay per-track.

**Files:** `domain/document/src/main/kotlin/com/redcut/domain/document/*Commands*.kt` + their tests
(`EditCommandsTest`, `CutCommandsTest`, `AdjustCommandsTest`).

**Risk:** this is the task where the existing 200-odd domain tests earn their keep. Expect several
assertions to need updating for the new signature — update them to state the per-track rule, never to
"whatever passes".

### Task C3: Migration from v1, and a test that reads a v1 file

**Files:** `domain/project/src/main/kotlin/com/redcut/domain/project/ProjectFile.kt` (decode path), test with
a v1 JSON literal (the `ProjectFileTest` already has the "unknown field" idiom to copy).

**Step 1:** failing test — a v1 document (flat `clips`) decodes into v2 with one video track holding those
clips, and nothing is lost (`sources`, `effects`, `canvas`, `name`).
**Steps 2–4:** implement + pass.
**Step 5:** commit.

### Task C4: `timeline` (prefix sums) becomes per-track

**Files:** `EditDocument.kt`'s `timeline` derivation + `:domain:render`'s `TimelineCompiler`.
**Note:** the compiler's layer concept is already sequential; a video-only project must compile to the SAME
graph as today. Add a golden test asserting that.

### Task C5: The canvas draws every track, at `TRACK_HEIGHT` each

**Files:** `TimelineDraw.kt`, `TimelineGeometry.kt` (`hitTest` returns `TimelineHit.Track(trackId)` for a
lane's background), `TimelineCanvas.kt`.

### Task C6: Hit-testing and gestures are track-aware

**Objective:** trimming, reorder and selection resolve to a (trackId, clipId) pair.

**Files:** `TimelineGeometry.hitTest`, `TimelineGestures.kt`, `EditorIntent.kt` (`SelectClip(trackId, clipId)`,
`ApplyCut(trackId, tool, clipId)`).

---

## Workstream D — audio on track 2

### §4.3 DECIDED (user: *"referensi aja"*)

**Reference the source's audio stream; do not transcode on import.** Rationale:

* Import must stay fast and must not fail because a codec is awkward — a transcode makes import a
  potentially-minutes-long, lossy, fallible step, and the spec's §13.2 promise is that the editor works on
  what it already has.
* Media3 plays a source's audio track directly, so a derived audio clip (sourceId + in/out, a volume, a mute)
  is enough to render and to edit (trim, move, fade, mute).
* Real extraction becomes necessary only if the user wants the audio as a FILE (share it, replace it). That is
  a Phase-5 export concern, and `:engine:media3`'s Transformer can do it then.

**What "track 2" is, precisely:** an `AUDIO` track whose clips carry `sourceId` of the video source. If a
future op needs independent audio media, the same track holds clips with a different `sourceId` — the schema
does not change.

### Task D1: The import plan emits an audio clip

**Files:** `feature/editor/src/main/kotlin/com/redcut/feature/editor/ImportPlan.kt` (+ its test, which is in
the fast tier): when `SourceRef.hasAudio`, add an audio clip to track 2 with the same in/out as the video
clip.

### Task D2: The document seeds track 2 on first import

**Files:** `ImportCommands.kt` / the VM's import path; test: `a first import leaves one video clip and one
audio clip`.

### Task D3: The audio lane renders like a track, with its own affordances

**Files:** `TimelineDraw.kt` (waveform later, a labelled lane now — the label is what proves the track is
there), `TimelineCanvas.kt`.

### Task D4: Audio-only documents are legal

**Files:** the import planner + probes; test: importing an audio file yields one audio track and no video
track, and export is enabled on it.

---

## Workstream E — track selection

### Task E1: `Selection.Track`

**Files:** `feature/editor/src/main/kotlin/com/redcut/feature/editor/EditorScreenState.kt` (or wherever
`Selection` lives), `EditorIntent.kt`, `EditorViewModel.kt`; VM tests.

New rule, stated for the code comment: tapping a clip selects the CLIP; tapping the lane its clips sit in
selects the TRACK; tapping a selected item deselects (UI revision 1's toggle rule, unchanged).

### Task E2: Cross-clip operations

**Files:** a new `domain/document/.../TrackCommands.kt`: `MergeTrackClips(trackId)` (concatenate adjacent
clips of one source, preserving order, refusing when a non-adjacent source is involved) + tests. This is what
the user said the track selection is FOR.

### Task E3: The toolbar shows track-scoped tools when a track is selected

**Files:** `EditorLayout.kt`'s `BottomToolbar`, `CutTools` becomes `StageTools(state)` and picks the tool row
from the selection (clip tools vs track tools).

---

## Workstream F — the preview viewport

### Task F1: The rect's model

**Files:** create `domain/document/src/main/kotlin/com/redcut/domain/document/ViewportRect.kt` — centre
(normalised), zoom, and the LOCKED ratio derived from `CanvasSpec`; pure maths + tests (aspect lock, clamping
so the rect cannot leave the frame, and the snap targets).

### Task F2: Snapping

**Files:** same file: `snappedToCentre(rect)` — when a clip is selected, x and y within a threshold snap to
the frame's centre lines. Test: a rect 2 px off centre snaps; a rect well off centre does not.

### Task F3: The gestures in the preview

**Files:** `feature/editor/src/main/kotlin/com/redcut/feature/editor/EditorViewport.kt` — pinch (zoom), drag
(pan), both inert when nothing is selected; emits `SetViewport(centre, zoom)`.

### Task F4: The rect is drawn and the transform is stored

**Files:** `TimelineDraw.kt`-equivalent for the preview, `EditorStage.kt`, `EditorIntent.kt`,
`Clip.transform` (already exists — the rect is its source).

---

## 5. Files likely to change (summary)

| Area | Files |
|---|---|
| Timeline maths | `core/common/.../timeline/TimelineGeometry.kt` (+ `TimelineZoom.kt` extracted) |
| Timeline drawing | `feature/editor/.../timeline/TimelineDraw.kt`, `TimelineCanvas.kt`, `TimelineGestures.kt` |
| Layout | `feature/editor/.../EditorLayout.kt`, `EditorScreen.kt`, (+ new `ExportSheet.kt`, `EditorViewport.kt`) |
| Document | `domain/document/.../{Track,EditDocument,TrackCommands,*Commands,ViewportRect}.kt` |
| Persistence | `domain/project/.../ProjectFile.kt` |
| Render | `domain/render/.../TimelineCompiler.kt` (per-track), `RenderGraph.kt` (unchanged if the golden test holds) |
| App | `app/.../navigation/AppShell.kt` (unchanged), `app/.../project/JsonProjectStore.kt` (schema version) |
| Docs | `docs/UI_REVISION_1.md` → split into `UI_REVISION_2.md` for this round |

## 6. Tests and validation per workstream

| WS | Runs on this host | Runs in CI | Runs only on a device |
|---|---|---|---|
| A | geometry tests (`:core:common`) | compile of the drawing | the fixed height, the missing gutter, the ruler on the clip |
| B | resolution mapping (`:domain:render`) | compose compile | the popup's look |
| C | the whole `:domain:document` + `:domain:project` suite (fast tier) | everything | — |
| D | `ImportPlan` tests (fast tier) | media modules compile | that audio actually plays |
| E | `TrackCommands` + VM tests | — | the tap targets |
| F | `ViewportRect` maths | compose compile | pinch/pan/snap |

**Gate for every task:** `./tools/verify.sh` (boundaries + pure tier + fast tier) then
`tools/.../check-cross-module-imports.py` on the changed files, then PR → CI → rebase-merge.

## 7. Risks and trade-offs

1. **Schema v2 is the only irreversible thing here.** Everything else is UI. The migration test (C3) is
   therefore the most important test in the plan: a v1 project must open in v2 with nothing lost, and the v1
   fixture should be a real file from the Drive folder if one can be pulled.
2. **Per-track ripple rules.** Ripple-delete on a video track must not move audio out of sync. Decision: the
   FIRST version keeps ripple per-track (the user's model), and a "linked" flag is explicitly NOT in v2 — the
   cost is that a ripple delete desyncs audio, which the user should see and decide about before I build
   linking.
3. **Audio as a reference, not a file** (D). Trade-off stated in §4.3; the alternative costs import time,
   quality and a new failure mode.
4. **`clips` as a derived view** (C1) keeps the blast radius small but leaves a subtle trap: a caller that
   WRITES through `clips` must be found and moved to tracks. The compiler will not catch a caller that reads
   the flattened list and then writes a single-track command — grep for every `clips =` in the codebase
   during C2 and list them in the PR body.
5. **The zoom range change (A4)** can invalidate saved projects' scroll positions (they are derived now, so
   no) — but it DOES change what a pinch does at the edges.
6. **Nothing in A–F can be verified on this host beyond compile.** Every task's PR body should say which
   claim is device-only, as the previous rounds have.

## 8. Decisions (the four open questions, answered)

1. **Audio: reference, not a file.** *"referensi aja"* → D builds a derived `AUDIO` track holding clips that
   point at the source's audio stream. No transcode on import, no new failure mode, no quality loss. A real
   extraction to a file stays a Phase-5 export concern if it is ever wanted.
2. **Zoom ladder: 1 s → 0.5 s → 0.1 s → per frame**, and the zoom-out limit covers an HOUR (*"shrink max per
   1 hr, incase video is hours long"*). So `MINIMUM = 0.1 px/s` (an hour is ~360 px), `MAXIMUM = 480 px/s`
   (a frame is 8 px), `DEFAULT = 60 px/s`, and the ruler grows the same ladder at every zoom.
3. **Stretch is per clip, not per track**: each clip's width follows its OWN duration, so trimming or
   retiming one clip changes that clip's width and nothing else's. There is no per-clip on-screen scale, and
   no image zoom in the body — the body's axis is time. *"clip body off screen not rendered"* is the
   optimisation that comes with the wide range and is task A5's second half.
4. **Track select: tap the lane's background** (the user's answer: *"gue ikut rekomendasi lu"*). No header
   column on the left; the gutter that would have held one is gone by design (task A2).

## 9. Suggested first PR

Workstream A (A1–A5) + B (B1–B2), because they are what the next device pass would notice, they carry no
schema risk, and A5/B1 remove two of the three things the user rejected in this round. Then C (schema) with
D and E behind it, F in parallel when the device pass confirms the preview area's gestures.
