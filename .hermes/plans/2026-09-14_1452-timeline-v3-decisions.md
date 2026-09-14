# Timeline v3 — decision record, then plan (WS T, WS S, WS L)

> **For Hermes:** this file is DECISIONS first. No code lands until the user has read it. Each decision
> states what it costs to DEFER, because the window in which these are cheap closes when the audio lane
> (WS D), the keyframe workstream (WS K) and the first saved user projects land.
>
> **Origin:** a review of `SysAdminDoc/ClearCut` (formerly `SysAdminDoc/NovaCut`, `com.novacut.editor`,
> MIT, Kotlin/Compose/Media3/Media3 1.11, Room schema v10, OTIO + FCPXML + EDL interchange) as a
> field-level reference, at the user's request. The user's answers to the seven questions this raised,
> verbatim: **1 deal · 2 schema v3 · 3 link · 4 you decide · 5 ms · 6 mapping otio · 7 deal**, plus the
> standing rule: *keep what is already best for our design philosophy, and take what is best from them too.*
> That rule is §2 and §3 — read them before reading the decisions, because every decision below is
> constrained by them.

**Goal:** fix the timeline's data model and time arithmetic ONCE, before three workstreams (audio lane,
keyframes, cross-clip operations) and a second migration stack assumptions on top of it.

**Architecture:** one migration (v2 → v3) carries every model change in this document, because they are
cheap together and expensive apart: SourceRef's frame rate becomes exact, tracks gain the attributes a
real lane needs, a track's contents become an ordered list of items (so a gap is representable), clips
gain a link group and an audio sync nudge, and the document reserves a nesting hook. Then the link
SEMANTICS land as their own workstream (WS L) with the enumeration test in §5.3.

**Tech stack:** Kotlin 2.2, kotlinx.serialization, JUnit5 + Truth, Gradle KTS (JDK 17), Medц3 for the
Android tier. Nothing here needs native code.

---

## 1. Baseline, verified on main `5b95902` (2026-09-14)

| Fact | Where |
|---|---|
| `EditDocument{ schemaVersion, id, name, sources, tracks: List<Track>, effects, canvas, createdAtMs, modifiedAtMs, revision }`, `clips` DERIVED (`tracks.flatMap { it.clips }`) | `domain/document/.../EditDocument.kt` |
| `Track{ id, kind: TrackKind, clips: List<Clip> }`; `TrackKind = VIDEO / AUDIO / TEXT_OVERLAY` | `domain/document/.../Track.kt` |
| `Clip{ id, sourceId, sourceInUs, sourceOutUs, speed, reverse, volume, muted, fadeInMs, fadeOutMs, transform }` — positions DERIVED from the track's order | `domain/document/.../Clip.kt` |
| `SourceRef{ ..., frameRate: Float = 30f, hasAudio, rotationDegrees }` | `domain/document/.../SourceImport.kt` |
| v1 → v2 migration works and is tested (`promotedFromV1` + `DocumentMigrationTest`) | `domain/document/.../DocumentMigration.kt` |
| Timeline geometry is µs ↔ px, playhead-centred, `TRACK_HEIGHT_DP = 56f`, and `laneRects()` returns **ONE** lane | `core/common/.../TimelineGeometry.kt` |
| Every clip command names its track; `UndoStack` snapshots + coalesces; commands are total | `domain/document/...` |
| Preview and export share one `RenderGraph` with two compilers | `domain/render/...` |

Consequences worth stating plainly: the timeline is **one lane wide** today (tasks C4/C5 are open), the
audio lane does not exist, keyframes do not exist, no user project has been saved by a released build.

## 2. What we KEEP because it is already better (the user's rule, first half)

1. **Derived positions.** A clip's timeline position is a prefix sum over its track, never a stored
   field. ClearCut stores `clip.timelineStartMs` and then needs `effectiveTimelineOffsetMs` helpers to
   reconcile it with the track — exactly the stale-position bug class our spec's Appendix B refuses.
2. **Pure-JVM domain with total commands.** `:domain:*` sees no `androidx.*`; every `EditCommand`
   validates preconditions and returns the document UNCHANGED rather than throwing. ClearCut's
   `EditCommand.kt` is 5 KB with a `GestureUndoTransaction` bolted on; ours is exercised by 183
   `:domain:document` tests in the fast tier.
3. **One UndoStack owner, snapshot-based, coalescing.** ClearCut ships two histories (command undo +
   snapshot history panel). One mechanism, property-tested, is better than two that can disagree.
4. **Structural preview/export parity.** One `RenderGraph`, two compilers, a parity test in CI (spec
   §12.3). This is a stronger guarantee than any codebase reviewed here offers.
5. **µs precision** everywhere, and **one persistence path** (JSON + `schemaVersion` + migration).
   ClearCut uses `Long` ms AND Room AND a 126 KB JSON autosave engine.
6. **A fixed 56 dp track height** — the user's own instruction (*"tinggi track body timeline fixed,
   tidak fitting container"*). ClearCut stores a per-track `trackHeight` in the model; we will NOT,
   because a height in the document is a UI preference that then needs migration forever.
7. **Module boundaries enforced by Gradle + ktlint + detekt.** ClearCut's `EditorViewModel.kt` is
   **308 KB** and its `Timeline.kt` **168 KB** (355 main files, 5.32 MB of source). That shape is the
   reason this repo exists in its current form; it is a warning, not a template.

## 3. What we TAKE because it is better than ours (the user's rule, second half)

| Borrowed idea | Source | Why it beats what we have |
|---|---|---|
| A rational **Timebase** type with NTSC presets, frame snapping, `addFrames`, `formatTimecode` | ClearCut `model/TimelineTimebase.kt` | We have µs and a `Float` frame rate. `29.97` is `30000/1001`; a `Float` cannot hold it, so frame math drifts today. |
| Their documented **timecode drift fix** (HH:MM:SS from elapsed real time, frame index within the second) | same file's comment | A real bug class in fractional rates (~3.6 s/hour). Cheaper to write down now than to debug later. |
| `TrackType` including an **ADJUSTMENT** kind | ClearCut `model/Project.kt` | Our `TrackKind` has `VIDEO/AUDIO/TEXT_OVERLAY`; the 4th kind was deferred in the umbrella card, not rejected. |
| Per-track `isLocked / isVisible / isMuted / isSolo / volume / pan / opacity / blendMode / isCollapsed` | same | A lane without a mute is a lane users cannot work with, and adding the flag LATER means every existing command must be re-audited for it. |
| `Clip.audioSyncOffsetMs`, clamped ±60 s and frame-quantized, with negative values quantized by magnitude | same | Straightforward A/V nudge; the negative-magnitude detail is a documented trap (a naive snap collapses −1 frame to 0). |
| **OTIO / FCPXML / EDL** as the interchange boundary, with a separate validator | ClearCut `TimelineExchangeEngine` (68 KB) + `TimelineExchangeValidator` | Confirms the industry's answer: the standard for timeline DATA is OpenTimelineIO, not any app's internal model. |
| **Gaps as first-class items** (OTIO `Gap`) | OTIO Timeline Structure | Their CHANGELOG has a release titled *"Gap-preserving reorder"* — the receipt that contiguity-by-construction bites once reorder/ripple exist. |
| A CHANGELOG read as a **risk register** | ClearCut `CHANGELOG.md` (150 versions) | The titles name the bug classes that arrive in this order: frame-quantized edits → transactional gestures → gap-preserving reorder → multi-track live composition → truthful exports. |

---

## 4. Decisions

Each decision: the call, why, what it costs to DEFER, what changes, how it is verified.

### D1 — A rational timebase, and an exact source frame rate (WS T)

**Decision.** Add `Timebase(numerator: Int, denominator: Int)` in `:core:common` (pure JVM) with NTSC
presets (`24000/1001`, `30000/1001`, `60000/1001`), `frameIndexAt` / `frameIndexAtOrBefore` /
`frameIndexAtOrAfter`, `timeUsAt(frameIndex)`, `snapUs`, `addFrames`, `formatTimecode`. It works in µs
(the document's unit). `SourceRef` gains exact `frameRateNumerator` / `frameRateDenominator` and keeps
`frameRate: Float` only as a derived convenience for code that needs a number.

**Why.** Frame-quantized editing is what makes keyframes, cut points and export timecode agree. ClearCut
had to add it later (their release *"Frame-quantized timeline edits"*); with `Float` rates we cannot even
express 29.97 correctly.

**Cost of deferring.** WS K (keyframes) bakes in whatever arithmetic exists when it lands, and every
command that moves a boundary afterwards must be re-audited. Retrofitting a timebase into a shipped
document is a migration plus a bug hunt.

**Changes.** New `core/common/.../timeline/Timebase.kt` + test (pure tier). `SourceRef` rational fields
(migration default: derive from the existing `Float`, i.e. `30f → 30/1`). `TimelineGeometry` gains
`snapToFrame` where a gesture commits.

**Verified by.** Fast-tier tests: `29.97 = 30000/1001` snaps a known µs value to the expected frame;
`timeUsAt(frameIndexAt(t))` is idempotent; timecode for `23.976` keeps wall-clock seconds; a fractional
clip's `frameStep` does not drift over 1000 frames.

### D2 — Schema v3: tracks become lanes with attributes (WS S)

**Decision.** One migration carries:

| Field | Type | Default on migration |
|---|---|---|
| `Track.isLocked` / `isVisible` / `isMuted` / `isSolo` | `Boolean` | `false / true / false / false` |
| `Track.volume` / `pan` / `opacity` | `Float` | `1f / 0f / 1f` |
| `Track.blendMode` | `enum` (NORMAL, plus a first set) | `NORMAL` |
| `Track.isCollapsed` | `Boolean` | `false` |
| `Track.kind` | extended with `ADJUSTMENT` | unchanged for existing tracks |
| `Clip.linkGroupId` | `String?` | `null` |
| `Clip.audioSyncOffsetUs` | `Long` | `0` |
| `EditDocument.sequences` | `List<Sequence>` | `emptyList()` (see D7) |

Attributes that do not apply to a kind (a `volumed` TEXT_OVERLAY) are rejected in `Track.init` and by
the commands, per this repo's habit of making illegal states unrepresentable at the boundary.

**Solo semantics — DECIDED (user, 2026-09-14): MULTI-SOLO.** Several tracks may be soloed at once; when at
least one track carries `isSolo`, every non-solo track is effectively muted; when none does, the ordinary
`isMuted` rules apply. The rule is ONE pure function (`effectiveMuted(track, allTracks)`) consulted by the
render graph, the waveform pass and the export compiler — the same rule in both compilers, which is the
only way preview and export cannot disagree (§12.3). Solo on a VIDEO lane means "hide the other picture
layers", same function.

**Why.** A mute, a lock, a solo and a per-track volume are the minimum a multi-lane timeline needs, and
the user just asked for a lane model. Adding them now is one migration over documents that do not exist
yet.

**Cost of deferring.** This is the worst one to defer. `isLocked` is a rule that EVERY mutation command
must honour ("a lock check added later is a rule every existing command must remember" — the exact trap
the repo has already hit once with `trackId`). After a released build has saved projects, each new field
is a migration over real user data plus a re-audit of every command.

**Changes.** `Track.kt`, `Clip.kt`, `EditDocument.kt`, `DocumentMigration.kt` (+v2→v3), `ProjectFile.kt`,
`planImport` (initial values), every command that must respect a locked/muted lane, `TimelineCompiler`
(volume/mute feed the render graph).

**Verified by.** A `DocumentMigrationTest` case that reads a REAL v2 file (a project saved by the current
debug APK, pulled from the device or the Drive folder — the same rule C3 set for v1), asserting nothing
is lost and every default above is applied. Plus fast-tier tests that a locked track refuses trim, cut,
ripple, merge and reorder.

### D3 — Linked audio/video is REAL (WS L)

**Decision (user's answer: "link").** A link is **clip-level**: `Clip.linkGroupId: String?`. Clips sharing
a non-null id form a group; a mutation that applies to one member applies to every member, **in one
command, one undo entry**. `planImport` sets the group when it seeds the audio companion (WS D), which is
where the "default behaviour" lives — no second track-level flag.

**Why clip-level and not track-level.** ClearCut links at the track (`Track.isLinkedAV`). That cannot
express two pairs in one project — and the audio lane workstream will produce exactly that as soon as a
second video is imported onto a track that already holds one. The link belongs to the clip pair.

**Cost of deferring.** The v2 plan accepted "audio desyncs on ripple" as a KNOWN cost, explicitly so the
user could see it and decide. The user has now decided. Deferring further means the desync ships, and
every command written before links exist has to be revisited for link semantics.

**Changes.** `Clip.linkGroupId`; a single resolver in `:domain:document` (`LinkGroup.of(document, clipId)`)
that every affected command consults; `planImport`; WS E's cross-clip operations.

**Verified by.** (a) Functional tests per affected command: a linked pair trims/cuts/deletes/ripples
together and produces ONE undo entry; (b) the **enumeration test** in §5.3 — the anti-"every command
honours X" guard; (c) an unlinked clip keeps behaving exactly as today.

### D4 — A gap is a first-class item, positions stay derived (WS S, with D2)

**Decision (the user delegated this one, then confirmed on 2026-09-14).** A track holds an **ordered list of items**:
`Track.items: List<TrackItem>` where `TrackItem` is a sealed interface with `Clip` and `Gap(durationUs)`.
`Track.clips` remains a derived view (`items.filterIsInstance<Clip>()`). Positions remain DERIVED — a
prefix sum over `items`, so §2.1 survives intact and gains a hole it can name. Transitions are NOT items
in v3 (see D6); the list shape leaves room for a `Transition` variant later without a migration.

**Why.** Today contiguity is a consequence of the model: a track is a concatenation, so "leave 2 s of
black between these clips" or "place this clip later in time" is unrepresentable. OTIO models a gap as an
item for exactly this reason, and ClearCut's CHANGELOG contains *"Gap-preserving reorder"* — evidence that
reorder without a gap concept is a bug factory: the moment reorder exists, someone has to decide what
happens to the hole, and with no hole in the model the answer gets invented per call site.

**Cost of deferring.** Every operation that currently walks a concatenation — ripple, merge, reorder,
split, the geometry's prefix sums, `TimelineCompiler` — must be re-derived later, and the rename/split of
`clips` → `items` reaches every module.

**Changes.** `Track.kt` (items + derived clips), `EditCommand.kt` (commands address an ITEM index, not a
clip index, where a gap can sit), `MergeRun`, `ReorderTarget`, `CutTools`, `TimelineCompiler`,
`TimelineGeometry` (prefix sums over items), `planImport`.

**Verified by.** Fast-tier: a gap's duration survives save/load; ripple across a gap; trim cannot extend
INTO a gap (a gap has no source); split at a gap boundary is a no-op; prefix-sum arithmetic with a
leading gap, a middle gap, a trailing gap; reorder across a gap.

**Editability in v3: ARITHMETIC ONLY** (the user's answer, 2026-09-14). A gap is created by ripple or
delete and then exists as arithmetic: it is NOT selectable, not draggable and not deletable in the UI. The
reason this is the safe choice is that making it SELECTABLE later only ADDS work — hit-testing a hole in
the lane, a `Selection.Gap` state, gap-local edge gestures, a duration label — and needs no migration,
because the hole is already real in the model. The reverse order (UI first, model later) is the one that
would need both.

### D5 — Audio sync nudge: µs inside, ms on screen

**Decision — CONFIRMED 2026-09-14** (the user's answer was *"keep nano sec"* and they clarified it: *"i meant
microseconds"*):
the field is `Clip.audioSyncOffsetUs`, **µs in the document, ms on screen**. The document keeps exactly
one unit, and the UI does the one conversion it needs in one place.

Why µs and not nanoseconds, recorded so the question does not come back: (a) **Media3 speaks µs** —
`C.MICROS_PER_SECOND`, `Util.msToUs`, every `ExoPlayer` / `Transformer` seek and trim — so a ns document
would convert at every boundary and lose the direct mapping that makes the preview/export parity test
cheap; (b) µs is already finer than anything the media can express — **one audio sample at 48 kHz is
20.8 µs**, so ns resolution is ~1000× finer than a sample, i.e. it encodes noise, not information;
(c) one `Long` in µs covers 292 000 years of timeline and 4.7 hours of… nothing — range is not the
problem in either unit.

Semantics are ClearCut's: clamped to ±60 s, frame-quantized against the project `Timebase`, and a NEGATIVE
offset is quantized by magnitude so it does not collapse to zero. And the reason ms was rejected, stated
where the next reader will find it: a 30 fps frame is 33.333 ms and a 29.97 fps frame is 33.367 ms, so an
integer-ms field cannot land on a frame boundary — it always leaves a residue, gets re-snapped on the next
round trip, and cannot express a sub-frame nudge (half a frame at 30 fps is 16 666 µs), which is the
standard way to fix lip-sync.

**Cost of deferring.** Nil in itself (one field, default 0); it rides along with D2 because a second
migration for one integer is waste.

**Verified by.** Fast-tier: `-1` frame quantizes to `-1` frame (not 0); the clamp holds at both ends; a
nudge does not move the clip in the timeline, only the audio it draws from.

### D6 — OTIO is the interchange, never the internal model

**Decision.** Adopt `OpenTimelineIO` as the **external** format (import/export, Phase 4/5), with our own
model staying as it is. Record the mapping now so the model cannot quietly become unexportable:

| RedCut | OTIO | Note |
|---|---|---|
| `EditDocument` | `Timeline` | |
| `Track(kind = VIDEO / OVERLAY / TEXT_OVERLAY / ADJUSTMENT)` | `Track` | OTIO `Track.kind` is Video/Audio; overlay kinds ride as metadata or as Video tracks + an `Effect` |
| `Track(kind = AUDIO)` | `Track` (Audio) | |
| `Clip` | `Clip` + `source_range: TimeRange` | rate = our `Timebase` |
| `Gap` | `Gap` | direct |
| `Transition` (deferred, spec FR-4.5) | `Transition` | we keep it as an effect on the incoming clip until it earns an item |
| nested `Sequence` (deferred, D7) | `Stack` / nested `Track` | |
| `TransformSpec`, `AppliedEffect`, LUTs | `Effect` (name + metadata) | |
| `durationUs` of a track | `duration()` | derived in both |

**Painter order — DECIDED (user, 2026-09-14): `tracks[0]` is the BOTTOM layer.** That is OTIO's Stack
rendering order (the first entry is the bottom), so export is direct, and the DRAWING pass is the side that
reverses the list. The consequence goes into the spec rather than staying implicit: the UI iterates
`tracks` in REVERSE (the last track is drawn on top), and a test pins it — a document of two video tracks
plus one `TEXT_OVERLAY` must not render the overlay underneath the picture. Skipping this decision is how
an export silently flips layers, which is a bug that reaches the user's finished file.

**Cost of deferring.** Cheap to defer in code, expensive in ambiguity: every module that walks `tracks`
is making an implicit decision today.

**Verified by.** A document of two video tracks + one audio track + one gap exports to OTIO and back with
every duration and the layer ORDER preserved (this test can be written before any export code exists, as
a pure round trip over the mapping).

### D7 — Reserve the nesting hook, ship no nesting (WS S)

**Decision (user's answer: "deal").** `EditDocument.sequences: List<Sequence>` where a `Sequence` is a
track list plus a canvas. `TrackItem` gains no nested variant in v3; `sequences` is always empty. No UI,
no commands, no behaviour. One field, one default, one migration line.

**Why.** A nested sequence (compound clip / sub-timeline) is the one model change that cannot be added
later without reshaping `EditDocument`: today a track holds clips, and a clip holds a source. A
`Sequence` reference is the smallest thing that keeps the door open — the same reasoning §13.2 used for
the C++ core, and the same reasoning the spec's Appendix-B table uses for `SourceRef.uri: String`.

**Cost of deferring.** Without it, nesting arrives as a document rewrite (v4) with a migration that has to
guess where sequences would have been.

**Verified by.** A serialization test: a document with a non-empty `sequences` list round-trips; an empty
one costs nothing (asserted by the existing golden-file test staying byte-identical).

---

## 5. Sequencing, and the guard that makes D2–D4 safe

### 5.1 Order

| # | Workstream | Contains | Why here |
|---|---|---|---|
| 1 | **WS T** — timebase | D1 | Pure JVM, no schema dependency, unblocks WS K |
| 2 | **WS S** — schema v3 | D2 + D4 + D5 + D7, one migration | All of it is one change to the document; splitting it means four migrations |
| 3 | **WS L** — link semantics | D3 | Needs `linkGroupId` (WS S). Must land BEFORE WS E (cross-clip ops) and before WS D seeds audio pairs |
| 4 | **C4 / C5** — the timeline draws every lane | existing tasks | Runs on the final shape instead of being reworked after WS S |
| 5 | **WS D**, **WS E**, **WS K** | existing tasks | D/E depend on S+L; K depends on T |

Recommendation, stated as a trade-off: this puts the USER-VISIBLE work (multi-lane drawing) behind two
invisible workstreams. The cost is one more session before the timeline looks different on screen; the
alternative is drawing lanes twice and migrating the model under a UI that just gained track selection.

### 5.2 The migration fixture

v2 is hours old, so a "real v2 file" exists only if we make one: install the current debug APK, create a
project with two clips, pull `redcut_project*.json` off the device via `adb`, and commit it as the
migration fixture. This is the same rule C3 set for v1 (*"a v1 project must open in v2 with nothing
lost, and the v1 fixture should be a real file"*) applied one version later. Without it the migration test
only proves our own serializer is self-consistent.

### 5.3 The enumeration guard (the one that stops the "EVERY X" bug class)

The repo has already been bitten twice by a rule stated over a SET: *"clips track-scoped in EVERY
command"* was swept, and then a parallel lane added `SetTransform`, which never got a `trackId`; and
`isLinkedAv`/`isLocked` will be exactly the same shape of rule. So: a **fast-tier test that enumerates the
sealed `EditCommand` subclasses** (`EditCommand::class.sealedSubclasses`, available on the JVM tier where
`:domain:document` runs) and asserts each one declares its link behaviour and its lock behaviour through a
`when` that the compiler makes exhaustive. A new command added by a future lane then cannot slip past —
the test fails the moment the class exists, not when a user hits the desync.

---

## 6. Risk register, borrowed from ClearCut's CHANGELOG order

Their release titles are a timeline of what bit them, in order:
*"Frame-quantized timeline edits"* → *"Transactional timeline gestures"* → *"Live extended-trim preview"*
→ *"Gap-preserving reorder"* → *"Multi-track live composition"* → *"Track-verified audio-only and stem
exports"* → *"Truthful exports"* → *"Resilient project deserialization"*.

| Risk | Our guard |
|---|---|
| Frame-quantized edits arrive late | D1 before WS K |
| A gesture commits more than one undo entry | Already solved: `UndoStack` coalescing + `GestureSession` |
| Reorder loses the hole | D4 (gaps as items) before C5's drawing and WS E |
| Multi-track composition diverges preview vs export | Already structural (one `RenderGraph`, two compilers) — extend the parity test to ≥2 lanes and to muted/solo |
| Export claims work it did not verify | WS P4.5 preflight + a parity test over ≥10 documents (spec §12.3) |
| A saved project stops deserializing | `schemaVersion` + migration + a real-file fixture per version (§5.2) |

## 7. Non-goals for v3 (contractual, so the schema does not grow a UI it cannot honour)

- No UI for lock / mute / solo / link toggles — the fields exist and the commands honour them; the
  chrome arrives with the lane work.
- No per-track height stored in the document (the user's fixed 56 dp rule stands).
- No transitions as items; no nesting UI; no preview proxies.
- **No selectable gaps** (arithmetic only, D4), **no exclusive solo** (multi-solo, D2), and **no ms or ns
  in the document** (µs only, D5). Each of these is a decision, not an omission: the alternative is named
  in §4 next to the reason it was not taken.
- No OTIO code in v3 — only the mapping (D6) and the declared painter order.

## 8. Decisions confirmed by the user (2026-09-14) — no open questions

| # | Question | Answer | Where it lands |
|---|---|---|---|
| 1 | Time unit for the sync nudge | **µs in the document, ms on screen** — the user's wording was *"keep nano sec"*, and they confirmed what they meant: **microseconds** (2026-09-14). Nanoseconds stay refused, with the reason in D5 (Media3 speaks µs; one 48 kHz sample is 20.8 µs, so ns would encode noise) | D5 / WS S |
| 2 | Painter order | **`tracks[0]` is the BOTTOM layer** (OTIO Stack order, direct export) | D6, and the spec |
| 3 | Gap editability in v3 | **Arithmetic only** — created by ripple/delete, not selectable in the UI. Making it selectable later only ADDS UI | D4 / WS S |
| 4 | Solo semantics | **Multi-solo** — several tracks may be soloed; ≥1 solo ⇒ every non-solo track is effectively muted, via ONE pure `effectiveMuted(track, allTracks)` used by preview and export alike | D2 / WS S |

Anything that comes up during WS S that is not covered here is a question for the user, not a default.
