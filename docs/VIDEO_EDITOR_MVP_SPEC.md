# RedCut — Technical Specification (MVP)

> Product name **RedCut** (settled — see Appendix A6). `applicationId` is still a
> placeholder: `com.example.redcut`; change it before the first release, not after.
> This document targets a **new, standalone Android repo**, not the LibreCuts codebase.

| Field | Value |
|---|---|
| Version | 1.0 |
| Status | Draft for review |
| Platform | Android (greenfield) |
| Scope | MVP — 3-stage non-linear editor with 720p/1080p export |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| Primary language | Kotlin 2.1+ |
| Native core | C++20 / NDK r27+ / CMake |
| Render engine | Media3 Transformer-first, C++ core via extension points |
| Delivery strategy | **Decided:** MVP on Media3's stock pipeline; C++ core lands post-MVP (§13.2) |

> **Toolchain pins are authoritative in `gradle/libs.versions.toml`, not here.** The
> versions in this document are *floors* and intent. Where the two disagree, the
> catalog wins. Current pins: AGP 8.9.1, Gradle 8.11.1, Kotlin 2.2.21, KSP
> 2.2.21-2.0.5, Media3 1.9.4, Hilt 2.59.2, compileSdk/targetSdk 36, minSdk 26.
> AGP is deliberately held below 9.x — see the note in the catalog.

---

## 1. Product definition

A three-stage, non-linear video editor. The user assembles a timeline, adjusts clips, applies visual effects, and exports to 720p or 1080p MP4. The product's differentiating idea is **workflow organization**: instead of one screen exposing 60 tools (the failure mode of most FOSS editors), the app presents three focused stages that the user can move between freely at any time.

### 1.1 The central design idea: stages are modes, not documents

This is the load-bearing decision of the whole design:

> **There is exactly one `EditDocument`. The three stages are filtered *views* and *tool scopes* over that one document — never separate projects, never a one-way conversion pipeline.**

Consequences that fall out of this for free:

- **Non-linearity is structural.** Jumping Cut → Effect → Cut costs nothing and loses nothing, because no stage "compiles" the document into the next stage's format.
- **Undo is global.** One history stack across all stages. Undoing after a stage switch does the obviously-right thing.
- **Export doesn't care which stage you're in.** It reads the document, not the stage.
- **Stage boundaries are a UI concern, not a data concern.** Adding a fourth stage later is a UI change, not a schema migration.

The one-way pipeline (`cut → edit → effect`, where each stage rewrites the timeline) is the tempting alternative and it is a trap: it makes backward navigation lossy, forces "re-edit" round trips, and doubles the state model. Reject it.

```
                    ┌──────────────────────────────┐
                    │        EditDocument          │  ← single source of truth
                    │  (immutable, versioned)      │
                    └──────────────┬───────────────┘
                                   │
          ┌────────────────────────┼────────────────────────┐
          │                        │                        │
   ┌──────▼──────┐          ┌──────▼──────┐         ┌───────▼──────┐
   │ CUT  stage  │          │ EDIT stage  │         │ EFFECT stage │
   │ scope:      │          │ scope:      │         │ scope:       │
   │  clip       │          │  clip       │         │  effect      │
   │  boundaries │          │  properties │         │  list        │
   └─────────────┘          └─────────────┘         └──────────────┘
        UI mode               UI mode                  UI mode
```

### 1.2 Goals

- Ship a **working, genuinely usable** MVP: import → cut → edit → effect → export.
- Preview and export produce the **same result** — parity is structural, not aspirational.
- Feel smooth on a **3-year-old mid-tier device**, not just a flagship.
- Native code where it measurably pays; Kotlin everywhere else.

### 1.3 Non-goals (MVP)

Explicitly out of scope. Every one of these is a real feature that would be a mistake to start now:

- Multi-track video (MVP is single video track + one audio bed + overlay effects)
- Chroma key, masking, blending modes
- Object/face tracking, auto-reframe
- HDR, 10-bit, log/RAW, color management beyond sRGB
- Transitions beyond a single cross-dissolve
- Cloud sync, collaboration, accounts
- iOS / desktop
- Codecs beyond H.264 + AAC in MP4
- Resolutions beyond 720p/1080p
- Vulkan renderer (see §6.6)
- Subtitle import/export (SRT)
- Auto-caption from audio (speech-to-text)
- Voice recording

> **Caption path (decided 2026-09-15):** TEXT OVERLAY (FR-4.3, Must) is the in-MVP caption feature.
> Subtitle/SRT import and auto-caption from audio stay out of scope per this section.

> **Keyframe animation moved INTO scope (decided 2026-09-15):** previously a non-goal, now an MVP
> workstream (see §13.1 execution order). The model reserves per-property keys; one key = constant.

---

## 2. Requirements

### 2.1 Functional requirements

#### FR-1 — Media import

| ID | Requirement | Priority |
|---|---|---|
| FR-1.1 | Import video via `ACTION_OPEN_DOCUMENT` (SAF). No storage permissions required. | Must |
| FR-1.2 | Import multiple videos in one action; append to timeline in selection order. | Must |
| FR-1.3 | Probe each source on import: duration, resolution, rotation, fps, codec, audio presence. | Must |
| FR-1.4 | Reject unsupported sources with a specific, human-readable reason. | Must |
| FR-1.5 | Take a persistable URI permission so projects survive reboot. | Must |
| FR-1.6 | Import one audio file as the music bed. | Should |
| FR-1.7 | Import one image as an overlay (PNG/JPEG/WebP). | Should |

#### FR-2 — CUT stage

The assembly stage. Everything here manipulates **clip boundaries and ordering**.

| ID | Tool | Behaviour | Priority |
|---|---|---|---|
| FR-2.1 | **Trim** | Drag either edge of a clip to set its in/out points. Non-destructive. Live preview of the frame at the edge being dragged. | Must |
| FR-2.2 | **Cut left** | Remove everything in the active clip *before* the playhead. Clip's in-point moves to the playhead. If the playhead is outside the clip, no-op with a toast. | Must |
| FR-2.3 | **Cut right** | Remove everything in the active clip *after* the playhead. Clip's out-point moves to the playhead. | Must |
| FR-2.4 | **Merge** | Join two or more *contiguous, source-adjacent* clips into one clip. | Must |
| FR-2.5 | **Split** | Split the active clip at the playhead into two independently editable clips. | Must |
| FR-2.6 | **Delete** | Remove the active clip; ripple-close the gap. | Must |
| FR-2.7 | **Reorder** | Drag clips along the timeline to change order. | Must |
| FR-2.8 | **Duplicate** | Duplicate the active clip immediately after itself. | Should |
| FR-2.9 | Frame-step | Step ±1 frame from the playhead (needed for frame-accurate cuts). | Must |
| FR-2.10 | **Playback** | Playing the preview moves the playhead with it, the tracks scroll under the fixed line (UI revision 1), and the playhead stops at the end of the document. | Must |

**Semantics that need to be nailed down (these are the usual sources of subtle bugs):**

- **Cut left/right vs. split+delete.** Cut left ≡ split at playhead + delete the left part. Implement cut left/right *in terms of* split+delete so there is exactly one code path and one set of edge cases.
- **Merge precondition.** Two clips merge only if they reference the same `sourceId` **and** the right clip's `sourceInUs` equals the left clip's `sourceOutUs` (i.e. they are contiguous in the source). Otherwise merge would silently drop footage. When the precondition fails, disable the button and explain why.
- **Ripple vs. overwrite.** MVP is **ripple-only**: deleting or cutting closes the gap. Overwrite mode is a v2 feature. This halves the timeline complexity.
- **Minimum clip duration.** 100 ms. Trim, split, and cut all clamp to this; a clip that would go below it is deleted instead.

#### FR-3 — EDIT stage

Clip-level, temporal/structural properties. **One value per clip per property by default** — a
property may instead hold (time, value) keys when the keyframe workstream (§13.1) is in; one key = constant.

| ID | Tool | Range / options | Priority |
|---|---|---|---|
| FR-3.1 | **Speed** | 0.25× – 4.0×, pitch-corrected audio | Must |
| FR-3.2 | **Volume** | 0 – 200 %, per clip | Must |
| FR-3.3 | **Mute** | Per clip toggle | Must |
| FR-3.4 | **Fade in / Fade out** | 0 – 3000 ms, video (to black) and audio (to silence), independent | Must |
| FR-3.5 | **Rotate** | 90° increments + free 0–360° | Must |
| FR-3.6 | **Flip** | Horizontal / vertical | Should |
| FR-3.7 | **Crop** | Rectangular, free + 1:1 / 9:16 / 16:9 / 4:5 presets | Must |
| FR-3.8 | **Canvas fit** | Fit / Fill / Stretch per clip | Must |
| FR-3.9 | **Reverse** | Reverse clip playback (video + audio) | Should |

#### FR-4 — EFFECT stage

Visual layer applied on top of the composited clip.

| ID | Tool | Options | Priority |
|---|---|---|---|
| FR-4.1 | **Filter presets** | 8–12 curated LUTs (e.g. None, Vivid, Warm, Cool, Mono, Fade, Film, Noir, Sepia, Punch) | Must |
| FR-4.2 | **Manual adjust** | Brightness, Contrast, Saturation, Temperature, Vignette | Must |
| FR-4.3 | **Text overlay** | Content, font (3–5 bundled), size, color, background, position (drag), start/end time | Must |
| FR-4.4 | **Image / sticker overlay** | Position (drag), scale (pinch), rotation, opacity, start/end time | Must |
| FR-4.5 | **Cross-dissolve** | Between two adjacent clips, 0–1500 ms | Should |
| FR-4.6 | **Effect scope** | Each effect applies to the whole document or to a single clip | Must |
| FR-4.7 | **Effect ordering** | Reorder the effect stack; render order is list order | Should |
| FR-4.8 | **Per-effect enable/disable** | Bypass without deleting | Should |

#### FR-5 — Export

| ID | Requirement | Priority |
|---|---|---|
| FR-5.1 | Resolutions: **720p (1280×720)** and **1080p (1920×1080)** only. | Must |
| FR-5.2 | Frame rate: 30 fps (MVP fixed; 24/60 is v2). | Must |
| FR-5.3 | Video: H.264 (AVC) High profile, hardware-encoded where available. | Must |
| FR-5.4 | Audio: AAC-LC, 48 kHz stereo, 128/192 kbps. | Must |
| FR-5.5 | Container: MP4. | Must |
| FR-5.6 | Bitrate presets: Low / Medium / High mapped to resolution-aware targets. | Must |
| FR-5.7 | Orientation: portrait/landscape target derived from the canvas spec. | Must |
| FR-5.8 | Output to `MediaStore` (`Movies/RedCut`), visible in the gallery immediately. | Must |
| FR-5.9 | Progress with percentage + ETA + cancel. | Must |
| FR-5.10 | Export must survive app backgrounding and screen-off. | Must |
| FR-5.11 | Estimated output size shown before export starts. | Should |
| FR-5.12 | Share sheet on completion. | Should |

#### FR-6 — Project lifecycle

| ID | Requirement | Priority |
|---|---|---|
| FR-6.1 | Autosave the document on every mutation (debounced 1 s). | Must |
| FR-6.2 | Restore the last project on cold start. | Must |
| FR-6.3 | Project list on the home screen with thumbnail, name, duration, modified date. | Must |
| FR-6.4 | New / rename / duplicate / delete project. | Must |
| FR-6.5 | Full undo/redo across all stages, minimum 50 levels. | Must |
| FR-6.6 | Survive process death with the document intact. | Must |

### 2.2 Non-functional requirements

| ID | Requirement | Target | Measurement |
|---|---|---|---|
| NFR-1 | Cold start to interactive timeline | < 1500 ms | Macrobenchmark, mid-tier reference device |
| NFR-2 | Preview playback | 1080p30 at ≥ 28 fps sustained, < 2 % dropped frames | `FrameMetricsAggregator` |
| NFR-3 | Scrub latency | < 80 ms from gesture to new frame | Instrumented trace |
| NFR-4 | Export throughput | ≥ 1.5× realtime at 1080p30 | Wall clock vs. media duration |
| NFR-5 | Peak heap (Java) | < 256 MB on a 1080p project | Android Studio profiler |
| NFR-6 | Peak native memory | < 128 MB | `Debug.getNativeHeapAllocatedSize` + native tracking |
| NFR-7 | APK size (per-ABI, arm64-v8a) | < 25 MB | Bundle analysis |
| NFR-8 | Startup + timeline jank | 0 frames > 32 ms during first scroll | JankStats |
| NFR-9 | Crash-free sessions | > 99.5 % | Play Vitals / Sentry |
| NFR-10 | Undo/redo latency | < 16 ms | Instrumented |
| NFR-11 | Min free storage for export | Checked and reported before start | — |
| NFR-12 | Accessibility | All controls labeled; TalkBack traversable; ≥ 48 dp targets | Accessibility Scanner |

### 2.3 Performance budget (per-frame, preview, 1080p)

| Stage | Budget |
|---|---|
| Decode (hardware) | 4.0 ms |
| Upload / color convert | 1.5 ms |
| Effect shader chain (≤ 4 effects) | 3.0 ms |
| Composite + overlay | 1.5 ms |
| Present | 1.0 ms |
| **Total** | **11.0 ms** (of a 33.3 ms frame at 30 fps) |

This budget is the reason the C++ render core exists at all (§6). It is also the reason we are strict about zero per-frame allocation.

---

## 3. Technology stack

| Layer | Choice | Why this and not the alternative |
|---|---|---|
| Language | **Kotlin 2.1+** | Modern Android default. |
| UI | **Jetpack Compose (Material 3)** | Fastest iteration for the stage/inspector UI. Timeline is custom `Canvas` (see §7.1). |
| Composition & export | **Media3 (Transformer + Composition + CompositionPlayer) 1.9+** | One `Composition` object drives both preview and export → preview/export parity is structural. |
| Playback | **Media3 `CompositionPlayer`** (`@ExperimentalApi`) with `ExoPlayer` fallback | `CompositionPlayer` renders the actual edit graph. Fallback keeps us unblocked while multi-sequence preview matures. |
| Native core | **C++20, NDK r27+, CMake** | Hot paths (§6). |
| Graphics | **OpenGL ES 3.1** | Must interop with Media3's GL pipeline. See §6.6 for why not Vulkan. |
| Audio DSP | **Oboe** + custom C++ | Low-latency, and the C++ core owns mixing/resampling anyway. |
| DI | **Hilt** | Standard; `domain` modules stay unannotated. |
| Async | **Coroutines + Flow** | Structured concurrency; cancellation is a hard requirement for scrubbing. |
| Persistence | **kotlinx.serialization** (JSON) to app-private storage | Project files are small; JSON is diffable and debuggable. Room is overkill. |
| Preferences | **DataStore** | — |
| Logging | **Timber** + a release-mode no-op tree | — |
| Crash/analytics | Pluggable, **opt-in, off by default** | Privacy-first posture, and it's a differentiator for the F-Droid audience. |
| Testing | JUnit5 + Turbine + Robolectric (unit); `androidx.benchmark` (perf); screenshot tests | — |
| Build | Gradle Kotlin DSL + version catalog + R8 full mode + Baseline Profiles | — |

### 3.1 Why Media3-first, stated plainly

- **Parity.** `Transformer` and `CompositionPlayer` consume the *same* `Composition`. This eliminates the single most expensive bug class in video editors — "the export doesn't match the preview."
- **No GPL.** `androidx.media3` is Apache-2.0. FFmpeg `-full-gpl` builds (x264/x265) would impose GPL on the whole distributed app.
- **Binary size.** Media3 is a few MB; a full FFmpeg build is 40–100 MB.
- **Maintenance.** `ffmpeg-kit` was retired upstream in 2025; depending on a community fork is a supply-chain risk.
- **Hardware paths.** Media3 already negotiates `MediaCodec`, surface interop, and HDR handling.

**Accepted risks, and the mitigation for each:**

| Risk | Mitigation |
|---|---|
| `CompositionPlayer` is `@ExperimentalApi` and multi-video-sequence preview is still maturing | `PreviewRenderer` is an interface with two implementations (`CompositionPlayerRenderer`, `ExoPlayerRenderer`); a feature flag switches. See §8.4. |
| Some exotic source codecs won't decode | FR-1.4 rejects with a clear reason. FFmpeg can be added later as an `AssetLoader` adapter behind the same seam — the design does not preclude it. |
| Composition-level effects API gaps | That is exactly what the C++ core's `Effect`/`VideoGraph` bindings cover (§6.3). |

---

## 4. Architecture

### 4.1 Module graph

Module boundaries are enforced by Gradle because **on Android, packages do not enforce dependencies — modules do.** Every arrow below is a compile-time guarantee.

```
:app                          Composition root: Hilt wiring, nav, Activity shells
 ├── :feature:home            Project list
 ├── :feature:editor          Stage host + timeline + inspectors  (Compose)
 ├── :feature:export          Export sheet, progress, share
 └── :feature:settings
 ├── :domain:document         EditDocument, Clip, AppliedEffect, EditCommand  (PURE JVM)
 ├── :domain:render           RenderGraph, EffectSpec, TimelineCompiler       (PURE JVM)
 ├── :domain:project          ProjectRepository port, ProjectFile format      (PURE JVM)
 ├── :engine:media3           RenderGraph -> Composition;  Media3 binding     (Android)
 ├── :engine:native           JNI bridge to C++ core + Kotlin-side wrappers   (Android)
 ├── :core:media              MediaResourceBroker, probing, thumbnails        (Android)
 ├── :core:ui                 Design system, theme, shared composables        (Android)
 └── :core:common             Result types, dispatchers, logging              (PURE JVM)
```

**Hard rules:**

1. `:domain:*` and `:core:common` must have **zero Android dependencies**. Not even `android.net.Uri`. Enforced by a CI check that fails the build if `android.*` appears in those modules' source or on their compile classpath. This single rule is what makes timeline semantics testable in milliseconds and is the highest-leverage constraint in this document.
2. `:feature:*` may depend on `:domain:*` and `:core:*`, never on each other.
3. `:engine:*` may depend on `:domain:render`, never on `:feature:*`.
4. Only `:app` and `:engine:native` may touch JNI directly.

### 4.2 Layering at runtime

```
   ┌──────────────────────────────────────────────────────────┐
   │ UI (Compose)                                             │
   │   EditorScreen  ──intent──▶  EditorViewModel             │
   │        ▲                          │                      │
   │        └──── EditorUiState ◀──────┘                      │
   └───────────────────────────┬──────────────────────────────┘
                               │ executes EditCommand (pure)
   ┌───────────────────────────▼──────────────────────────────┐
   │ DOMAIN (pure JVM)                                        │
   │   EditDocument  +  EditCommand  +  UndoStack             │
   │   TimelineCompiler:  EditDocument ──▶ RenderGraph        │
   └───────────────────────────┬──────────────────────────────┘
                               │ RenderGraph (platform-neutral)
              ┌────────────────┴────────────────┐
              ▼                                 ▼
   ┌─────────────────────┐          ┌─────────────────────────┐
   │ :engine:media3      │          │ :engine:native          │
   │ RenderGraph         │          │ C++ render core,        │
   │   ├▶ Composition    │◀────────▶│ audio DSP, analysis     │
   │   ├▶ preview        │  JNI     │ (bound into Media3 via  │
   │   └▶ export         │          │  Effect/VideoGraph)     │
   └─────────────────────┘          └─────────────────────────┘
```

### 4.3 The pivot: one RenderGraph, two compilers

**The domain never emits a codec command, a GL call, or a file path.** It emits a `RenderGraph` describing *what the video looks like*. Adapters consume it.

```kotlin
// :domain:render — pure Kotlin/JVM. No android.*, no androidx.*
@Serializable
data class RenderGraph(
    val revision: Long,
    val canvas: CanvasSpec,
    val layers: List<RenderLayer>,   // index 0 = base video layer
    val audio: AudioGraph,
    val output: OutputSpec
)

@Serializable
sealed interface RenderLayer {
    val timeRange: TimeRange
    val enabled: Boolean

    data class Video(
        val source: SourceRef,
        val sourceRange: TimeRange,      // which slice of the source
        val speed: Float,
        val reverse: Boolean,
        val transform: TransformSpec,    // crop / scale / rotate / fit
        val adjust: ColorAdjustSpec?,    // brightness/contrast/saturation/temp
        val lut: LutRef?,
        val fades: FadeSpec,
        val audio: AudioSpec            // gain, mute, fades
    ) : RenderLayer

    data class Text(val content: TextSpec, val transform: TransformSpec) : RenderLayer
    data class Image(val source: SourceRef, val transform: TransformSpec, val opacity: Float) : RenderLayer
}

@Serializable
data class OutputSpec(
    val width: Int,          // 1280 or 1920
    val height: Int,         // 720 or 1080
    val fps: Int,            // 30
    val videoBitrate: Int,
    val audioBitrate: Int
)
```

`TimelineCompiler` is a **pure function** `EditDocument -> RenderGraph`. It is where every framing, timing, and ordering rule lives, and it is unit-tested with no device, no codec, and no ffmpeg.

- **Export path:** `RenderGraph` → `:engine:media3` → `Composition` → `Transformer` → MP4.
- **Preview path:** `RenderGraph` → `:engine:media3` → `Composition` → `CompositionPlayer` → `SurfaceTexture` → Compose.

Because both paths start from the same value, parity is a **test**, not a hope (§12.3).

---

## 5. Data model

```kotlin
// :domain:document — pure Kotlin/JVM
@Serializable
data class EditDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val sources: List<SourceRef>,        // immutable imported media
    val clips: List<Clip>,               // the single video track, in order
    val audioBed: AudioBed?,
    val effects: List<AppliedEffect>,    // ordered stack; render order = list order
    val canvas: CanvasSpec,
    val createdAt: Long,
    val modifiedAt: Long
)

@Serializable
data class SourceRef(
    val id: String,
    val uri: String,                     // SAF uri, persisted permission
    val displayName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,            // container rotation
    val frameRate: Float,
    val hasAudio: Boolean,
    val videoCodec: String,
    val audioCodec: String?
)

@Serializable
data class Clip(
    val id: String,
    val sourceId: String,
    val sourceInUs: Long,                // in-point within the SOURCE
    val sourceOutUs: Long,               // out-point within the SOURCE
    val speed: Float = 1f,
    val reverse: Boolean = false,
    val volume: Float = 1f,              // 0f..2f
    val muted: Boolean = false,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val transform: TransformSpec = TransformSpec(),
    val enabled: Boolean = true
) {
    /** Duration on the TIMELINE, i.e. after speed is applied. */
    val timelineDurationUs: Long
        get() = (((sourceOutUs - sourceInUs) / speed).toLong()).coerceAtLeast(MIN_CLIP_US)

    companion object { const val MIN_CLIP_US = 100_000L }
}

@Serializable
sealed interface AppliedEffect {
    val id: String
    val scope: EffectScope               // Document | Clip(clipId)
    val timeRange: TimeRange             // within the scope
    val enabled: Boolean

    data class Lut(val ref: LutRef, val strength: Float = 1f) : AppliedEffect
    data class Adjust(val spec: ColorAdjustSpec) : AppliedEffect
    data class Text(val spec: TextSpec, val transform: TransformSpec) : AppliedEffect
    data class Image(val sourceId: String, val transform: TransformSpec,
                     val opacity: Float) : AppliedEffect
    data class Dissolve(val durationMs: Long) : AppliedEffect
}
```

### 5.1 Design notes

- **`sourceInUs`/`sourceOutUs`, not timeline positions.** Clips store source-relative ranges. Timeline position is *derived* by prefix-summing `timelineDurationUs`. This makes ripple edits, speed changes, and reordering trivially correct — and removes an entire class of "timeline position went stale" bugs. This is different from LibreCuts' model and it is worth the divergence.
- **`timelineDurationUs` is computed, never stored.** One source of truth.
- **Effects are a flat ordered list with a `scope`**, not nested inside clips. Reordering the stack and moving an effect between scopes are both simple list operations.
- **`schemaVersion` from day one**, with an explicit migration function chain. Cheap now, impossible to retrofit.
- **No `Uri` in the domain.** Sources are opaque `String` URIs resolved by the adapter. (This is what LibreCuts gets wrong — its `VideoProject` imports `android.net.Uri` and extends `Serializable`, which is precisely why its domain is not unit-testable.)

---

## 6. The C++ native core

### 6.1 Scope and delivery strategy — DECIDED

**Decision (confirmed):** ship the MVP on Media3's stock render pipeline, then grow the C++ core into it after the MVP is shipping. The native core is *designed* here in full, but it is **not** on the MVP critical path.

This is safe to defer because the C++ core attaches at a **seam**, not throughout the app. Two boundaries make the substitution a swap rather than a rewrite:

1. `RenderGraph` is our own platform-neutral type — no Media3 type ever reaches the domain or UI (§4.3).
2. Media3 exposes `GlEffect` / `VideoGraph.Factory` / `AssetLoader`, so native code plugs *into* Media3's render loop rather than replacing Media3.

The full core is specified below **and** sequenced in §13.2. **No MVP feature is blocked on native code.** §6.8 lists the rules that keep this door open — those rules are MVP obligations, not optional hygiene.

| | Ships on Media3 stock pipeline | Grows into C++ core |
|---|---|---|
| Preview render | Media3 `VideoGraph` (GL) | Custom `NativeVideoGraph` |
| Effect shaders | Media3 `GlEffect` presets | Custom GLSL, batched into one pass |
| Audio mix | Media3 `AudioProcessor` chain | Oboe + custom DSP |
| Waveform | Kotlin, coarse | C++ peak pyramid |
| Scene detection | Not in MVP | C++ frame-diff pass |

The seam (`:engine:native`) is designed and stubbed in Phase 0. The core fills in from Phase 5. **No MVP feature is blocked on native code.**

### 6.2 Responsibilities

| Concern | Native? | Rationale |
|---|---|---|
| Effect shader chain (LUT, color adjust, vignette, dissolve) | **C++** | Per-pixel, per-frame, hot. Batching N effects into one fragment shader pass is a large win that Media3's one-effect-one-pass model cannot express. |
| Video compositing (base + overlays) | **C++** | One render pass instead of N. |
| YUV→RGB, colorspace, transfer | **C++** | Per-pixel, and correctness-sensitive. |
| Audio DSP: mix, gain, fades, resample, pitch-corrected speed | **C++** | Sample-rate work, needs Oboe-level latency and no GC. |
| Waveform peak pyramid | **C++** | Reads every audio sample; trivially parallel; must not touch the JVM heap. |
| Scene-change detection, frame sampling | **C++** | Reads every video frame; feeds auto-split suggestions in a later version. |
| Frame-accurate seek index | **C++** | Replaces slow `MediaExtractor` seeks. |
| Timeline/project logic, commands, undo | **Kotlin** | Not hot. Testability and iteration speed dominate. |
| Media3 orchestration, muxing, `MediaCodec` | **Kotlin/Java** | Media3 owns this; do not fight it. |

**Explicit non-goal:** do not reimplement decode or muxing in C++ for the MVP. Media3's `MediaCodec` integration is mature, hardware-accelerated, and handles device quirks you do not want to rediscover. The C++ core *renders into* and *consumes* surfaces; it does not own the codec lifecycle.

### 6.3 The integration seam — how C++ plugs into Media3

This is what makes the mix "seamless" rather than a second parallel universe:

```
   Media3 Transformer / CompositionPlayer
              │
              │  drives
              ▼
   ┌─────────────────────────┐
   │  NativeGlEffect         │  Kotlin, implements androidx.media3 GlEffect
   │   .toGlShaderProgram()  │
   └───────────┬─────────────┘
               │  JNI (once per effect, NOT per frame)
               ▼
   ┌─────────────────────────┐
   │  NativeShaderProgram    │  Kotlin, implements
   │   .configure()          │  BaseGlShaderProgram
   │   .drawFrame()          │  → delegates to C++
   └───────────┬─────────────┘
               │  JNI: bind texture, draw, return. Zero allocation.
               ▼
   ┌─────────────────────────┐
   │  C++ RenderCore         │  EGL context, GLSL programs,
   │   (libredcut_core.so)   │  texture pool, FBO chain
   └─────────────────────────┘
```

Two integration points, in order of preference:

1. **`GlEffect` + `GlShaderProgram` (MVP, Phase 3).** Media3 calls our Kotlin wrapper; the wrapper forwards to C++. Media3 still owns the GL context, the surface flow, and the render loop. Lowest risk, immediate parity, and it already gives us batched custom shaders.
2. **`VideoGraph.Factory` (Phase 6, optional).** We own the entire GL render loop and take decoded frames as `SurfaceTexture`s. Maximum control — needed only if profiling proves Media3's graph is the bottleneck. This is a *replacement* of one Media3 component, not of Media3.

### 6.4 JNI boundary rules

These rules exist to keep the boundary from becoming the bottleneck.

1. **No per-frame JNI calls that allocate.** Every hot-path method takes primitives, direct `ByteBuffer`s, or `long` handles.
2. **No Java object graphs cross the boundary.** Frames move as `AHardwareBuffer`/`Surface`/texture IDs, never as `ByteArray` or `Bitmap`. A single 1080p RGBA `ByteArray` is 8 MB and copying it per frame would alone exceed the entire frame budget.
3. **RAII handles.** Native objects are `long` pointers wrapped in a Kotlin `AutoCloseable` (`NativeRenderCore`, `NativeAudioGraph`). `Closeable` + `use {}`, with a `Cleaner` backstop.
4. **`RegisterNatives` in `JNI_OnLoad`.** Explicit registration, not name mangling — survives ProGuard, fails loudly at startup rather than at first call, and avoids symbol lookup cost.
5. **Cache `JNIEnv*` per thread.** Native worker threads call `AttachCurrentThread` once at thread start and store the `JNIEnv*` in a `thread_local`. Never attach/detach per callback.
6. **Prefer polling native → Kotlin over native callbacks for hot paths.** Callbacks from native into the JVM in a frame loop cause unpredictable jitter. Progress and completion are the exceptions (low frequency, off the hot path) and use a posted callback.
7. **`@FastNative` on hot, simple methods**; **`@CriticalNative`** for static methods taking only primitives — it skips the JNI transition entirely.

```kotlin
// :engine:native — the ONLY place JNI is declared
internal object NativeBridge {
    init { System.loadLibrary("redcut_core") }

    @FastNative external fun nativeCreateRenderCore(width: Int, height: Int): Long
    @FastNative external fun nativeDestroyRenderCore(handle: Long)

    /** Binds an external OES texture, applies the effect chain, renders. No allocation. */
    @FastNative external fun nativeDrawFrame(
        handle: Long,
        inputTextureId: Int,
        outputTextureId: Int,
        transformMatrix: FloatArray,   // reused array, never reallocated
        effectParams: FloatArray,      // packed scalar params for the active chain
        frameTimeUs: Long
    )

    @CriticalNative external fun nativeSetEffectMask(handle: Long, maskLo: Long, maskHi: Long)
}
```

### 6.5 C++ core layout

```
native/
├── CMakeLists.txt
├── jni/
│   ├── JniOnLoad.cpp            # RegisterNatives, version handshake
│   ├── RenderCoreJni.cpp
│   └── AudioEngineJni.cpp
├── render/
│   ├── EglContext.{h,cpp}       # EGL 3.x context, surface binding
│   ├── TexturePool.{h,cpp}      # preallocated, never allocate per frame
│   ├── FboChain.{h,cpp}         # ping-pong render targets
│   ├── ShaderProgram.{h,cpp}    # compile/link cache
│   ├── EffectChainCompiler.{h,cpp}  # N effects -> 1 batched pass
│   └── shaders/
│       ├── base.vert
│       ├── color_adjust.frag    # brightness/contrast/sat/temp
│       ├── lut.frag             # 3D LUT sampling
│       ├── vignette.frag
│       ├── dissolve.frag
│       └── composite.frag       # base + N overlays
├── audio/
│   ├── AudioGraph.{h,cpp}
│   ├── Resampler.{h,cpp}        # Kaiser-windowed sinc
│   ├── TimeStretcher.{h,cpp}    # WSOLA, pitch-preserving
│   └── WaveformPyramid.{h,cpp}
├── analysis/
│   ├── FrameSampler.{h,cpp}
│   └── SceneDetector.{h,cpp}
└── util/
    ├── Logger.{h,cpp}           # __android_log_print, no JNI round trip
    └── ScopeGuard.h
```

**CMake:**

```cmake
cmake_minimum_required(VERSION 3.22)
project(redcut_core CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(redcut_core SHARED
    jni/JniOnLoad.cpp
    jni/RenderCoreJni.cpp
    jni/AudioEngineJni.cpp
    render/EglContext.cpp
    render/TexturePool.cpp
    render/FboChain.cpp
    render/ShaderProgram.cpp
    render/EffectChainCompiler.cpp
    audio/AudioGraph.cpp
    audio/Resampler.cpp
    audio/TimeStretcher.cpp
    audio/WaveformPyramid.cpp
    analysis/FrameSampler.cpp
    analysis/SceneDetector.cpp
    util/Logger.cpp)

target_compile_options(redcut_core PRIVATE
    -Wall -Wextra -Werror -fno-exceptions -fno-rtti -ffast-math)
# -fno-exceptions/-fno-rtti: smaller, faster, and forces explicit error handling.
# -ffast-math is safe here: we do graphics/audio math, not IEEE-exact numerics.

target_link_libraries(redcut_core
    android log EGL GLESv3 ahardwarebuffer oboe)

# 16 KB page size alignment — REQUIRED for Android 15+ devices.
target_link_options(redcut_core PRIVATE "-Wl,-z,max-page-size=16384")
```

> **Non-obvious but mandatory:** `-Wl,-z,max-page-size=16384`. Android 15+ runs on 16 KB page-size devices, and a `.so` without this flag will fail to load there. This is a one-line change that is a shipping blocker if missed, and it is easy to miss.

### 6.6 GLES 3.1, not Vulkan — the reasoning

Media3's render pipeline is **OpenGL ES** (`GlShaderProgram`, `GlUtil`, `SurfaceTexture`, `EGL`). Interop between an internal Vulkan renderer and Media3's GL pipeline means exporting/importing `AHardwareBuffer`s across two APIs on every frame — extra sync, extra copies, and driver-dependent behaviour. For the MVP, **GLES 3.1** keeps us in the same context as Media3, allows direct texture sharing, and is available on effectively every device at minSdk 26. Vulkan is a v2 evaluation gated on profiling evidence, not a v1 goal.

### 6.7 Error handling across the boundary

- C++ never throws (`-fno-exceptions`); functions return `Status`-like enums or `std::optional`.
- Native errors are converted to a Kotlin `sealed class NativeError` at the JNI layer.
- **Native crashes must not take down the app silently.** An `AIHandler` (or `ndk-stack`-based symbolication in CI) is wired up so native faults produce a symbolicated stack. A native crash in a render loop is otherwise a memory-corruption bug with no trace.

### 6.8 Keeping the door open — MVP rules that make Phase 5 cheap

Deferring the C++ core is only safe if the MVP is built in a way that does not foreclose it. These are **MVP obligations**, and each one is cheap now and expensive to retrofit:

| # | Rule | What breaks if violated |
|---|---|---|
| D1 | **No Media3 type outside `:engine:media3`.** `Composition`, `EditedMediaItem`, `EditedMediaItemSequence`, `GlEffect` must never appear in `:domain:*`, `:feature:*`, or a ViewModel signature. | Replacing the render path becomes a rewrite of the UI and domain layers. Enforce with a CI import check, same mechanism as the `:domain:*` purity rule (§4.1). |
| D2 | **Effect params stay declarative.** `EffectSpec` is data (`lutRef`, `brightness`, `vignette`, …), never a constructed shader object or a lambda. | The C++ `EffectChainCompiler` must see the *whole* chain at once to fuse N effects into one fragment pass. A callback/object model cannot be fused and would have to be redesigned. |
| D3 | **One mapper, one place.** All `RenderGraph → Composition` translation lives in `RenderGraphMapper`. UI code never constructs a Media3 effect inline. | Effect construction scattered across the UI means the native swap touches dozens of files. |
| D4 | **Audio stays declarative too.** Gain, fades, speed, and mix are *params* on `AudioGraph`, not a constructed `AudioProcessor` chain. | Phase 5 replaces the Media3 audio chain with Oboe; a processor-object model forces a rewrite of every audio call site. |
| D5 | **Preview is surface/texture-based from day one** — `SurfaceView` + `SurfaceTexture`, never `Bitmap` or `Canvas` rendering. | You cannot retrofit a GL pipeline onto a Canvas-based preview. This is the single hardest mistake to undo. |
| D6 | **`PreviewRenderer` is an interface from day one**, and the UI holds the interface, never `CompositionPlayer` directly. | Also the R1 mitigation (§8.4) — this rule earns its keep twice. |
| D7 | **`MediaResourceBroker` exists from Phase 1**, even though Media3 largely manages codecs on the MVP path. | The native core will want to own decode surfaces and codec lifetime; adding a broker later means auditing every call site. |
| D8 | **Prove the GL/JNI seam early, with a trivial payload.** Phase 3 ships `NativeGlEffect` with one simple native shader (pass-through or color-adjust) — see the Phase 3 contingency below. | Phase 0.4 proves JNI works; it does *not* prove EGL context sharing and texture-ID hand-off across the boundary. Those are the real risks, and you want them proven while the payload is trivial. |

**Phase 3 contingency (write this into the plan, not into a crisis):** if Phase 3 runs long, effects can ship on stock Media3 Kotlin `GlEffect` and the entire native path — including `NativeGlEffect` — moves to Phase 5. Nothing else in the spec depends on D8 being done in Phase 3; D1–D7 are what keep that escape hatch open.

---

## 7. UI architecture

### 7.1 Stage host and navigation

```
┌─────────────────────────────────────────────────────────────┐
│  ┌──────────┬──────────┬──────────┐                         │
│  │   CUT    │   EDIT   │  EFFECT  │   ← segmented stage bar │
│  └──────────┴──────────┴──────────┘                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│                     PREVIEW  (Compose)                      │
│              renders via CompositionPlayer                  │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  ▓▓▓▓▓▓│▓▓▓▓▓▓▓▓▓▓▓▓▓│▓▓▓▓▓▓▓▓│     ← timeline (custom Canvas)│
│         ▲ playhead, draggable clip edges                    │
├─────────────────────────────────────────────────────────────┤
│  [ stage-specific tool bar / inspector ]                    │
└─────────────────────────────────────────────────────────────┘
```

**The timeline is a custom Compose `Canvas`.** Reasons: clip edge hit-testing needs pixel precision and a fixed 48 dp touch target independent of clip width; drag gestures must not be intercepted by the LazyList scroll; and clip thumbnails are drawn as textures, not composables. A `LazyRow` of composables per clip will jank at 50+ clips.

**Stage switching is instant and free** — it changes which inspector is composed and which timeline overlays are drawn. It does **not** recompile the document, because there is nothing to recompile (§1.1).

### 7.2 State: single immutable UI state, MVI-style

**Anti-pattern to avoid explicitly:** LibreCuts exposes nine separate `StateFlow`s from its editor ViewModel, which permits genuinely inconsistent frames (`canUndo == true` while `project == null`). One state object removes the class entirely.

```kotlin
@Immutable
data class EditorUiState(
    val document: EditDocument,
    val history: HistoryState,          // canUndo, canRedo, topLabel
    val stage: Stage,
    val selection: Selection,           // None | Clip(id) | Effect(id)
    val playheadUs: Long,
    val playback: PlaybackState,
    val tool: ToolState,                // active tool + transient drag state
    val export: ExportProgress?,
    val dialogs: DialogState
)

sealed interface HistoryState {
    data class Ready(val canUndo: Boolean, val canRedo: Boolean, val topLabel: String?) : HistoryState
    object Busy : HistoryState
}

sealed interface EditorIntent {
    data class SelectStage(val stage: Stage) : EditorIntent
    data class TrimClip(val id: String, val edge: Edge, val deltaUs: Long) : EditorIntent
    data class CommitTrim(val id: String, val edge: Edge) : EditorIntent
    data class CutLeft(val clipId: String) : EditorIntent
    data class CutRight(val clipId: String) : EditorIntent
    data class MergeClips(val ids: List<String>) : EditorIntent
    data class SplitClip(val clipId: String, val atUs: Long) : EditorIntent
    data class SetSpeed(val clipId: String, val speed: Float) : EditorIntent
    data class AddEffect(val effect: AppliedEffect) : EditorIntent
    object Undo : EditorIntent
    object Redo : EditorIntent
    data class SeekTo(val positionUs: Long) : EditorIntent
    object StartExport : EditorIntent
    // ...
}
```

**`playback` holds two facts and no third: `isPlaying`, and `positionUs` — where the composition is on the timeline.** Its source of truth is the **renderer**, not this state object: the player is what knows whether it is playing and where it is, and the ViewModel copies the renderer's two flows into this field so that one frame of the UI cannot show a playhead from one instant and a play button from another. The playhead follows `positionUs` as it advances (FR-2.10) — the direction that was missing, since the preview followed the playhead from the start and nothing followed the preview.

**Invariants, asserted in debug builds** (so violations crash in CI, not in front of a user):

1. `document.clips.all { it.sourceOutUs - it.sourceInUs >= Clip.MIN_CLIP_US }`
2. `document.clips.size >= 1` at all times after import.
3. Every `AppliedEffect.scope` referencing a clip references an existing clip ID.
4. Every `Clip.sourceId` references an existing `SourceRef.id`.
5. `playheadUs in 0..document.totalDurationUs`

### 7.3 Undo/redo

Command pattern, **snapshot-based**, consistent with LibreCuts' approach — which is correct here, and for a good reason: `EditDocument` is immutable and small (a list of clips plus a list of effects), so a snapshot is a few hundred bytes and a pointer copy. Inverse-command undo would be several times the code and several times the bugs, for no benefit at this state size.

```kotlin
sealed interface EditCommand {
    val label: String                  // shown as "Undo Trim"
    fun apply(doc: EditDocument): EditDocument
}

class TrimClipCommand(val clipId: String, val inUs: Long, val outUs: Long) : EditCommand { /* ... */ }
class CutLeftCommand(val clipId: String, val atUs: Long) : EditCommand { /* ... */ }
class MergeClipsCommand(val ids: List<String>) : EditCommand { /* ... */ }
// ...
```

Three requirements that make this actually pleasant, all of which LibreCuts misses:

1. **Named commands with `equals`.** No opaque `(List<EditOperation>) -> List<EditOperation>` escape hatch. Commands must be comparable, loggable, and testable.
2. **Coalescing.** A trim drag emits per-frame deltas; the history must record **one** entry. `TrimClipCommand` is applied to live state as a *transient preview*, and only `CommitTrim` (on gesture end) pushes a history entry. Without this, one gesture fills the undo stack and "Undo" appears broken.
3. **Bounded at 50** (FR-6.5), dropping oldest.

`HistoryState.Busy` exists because export and native render-core reconfiguration are asynchronous; the UI must disable Undo rather than race it.

---

## 8. Render pipelines

### 8.1 Compilation

```
EditDocument
   │  TimelineCompiler.compile()      pure, JVM-tested
   ▼
RenderGraph
   │  RenderGraphMapper                :engine:media3
   ▼
Composition                            Media3
   ├─────────────▶ CompositionPlayer ──▶ SurfaceTexture ──▶ Compose preview
   └─────────────▶ Transformer ────────▶ MediaCodec ─────▶ MP4 (MediaStore)
```

**Recompilation triggers:** any `EditDocument.revision` change. Compilation is pure and cheap (< 1 ms for typical projects), so it runs on every revision change without debouncing. Preview rebuild is debounced at 120 ms during drags to avoid thrashing the player.

### 8.2 Media3 mapping

| `RenderGraph` element | Media3 construct |
|---|---|
| `RenderLayer.Video` | `EditedMediaItem` with `setDurationUs`, `setRemoveAudio`, `setSpeed` |
| Ordered clips | `EditedMediaItemSequence` |
| `TransformSpec` crop/scale/rotate | `Effects` → `Presentation` / `MatrixTransformation` |
| `ColorAdjustSpec`, `LutRef` | `Effects` → our `NativeGlEffect` (§6.3) |
| `Text`/`Image` overlay | Additional `EditedMediaItemSequence` + `videoCompositorSettings` |
| `Dissolve` | `AlphaScale`/custom `NativeGlEffect` across the overlap |
| `AudioGraph` | `AudioProcessor` chain → `NativeAudioProcessor` |
| `OutputSpec` | `Transformer.Builder.setVideoMimeType(...).setEncoderFactory(...)` |

### 8.3 Export pipeline

```
ExportRequest (720p|1080p, bitrate preset)
   │
   ├─ preflight: free storage, codec availability, document validity
   ├─ build RenderGraph at target OutputSpec
   ├─ build Composition
   ▼
ExportService : LifecycleService, foreground, type=mediaProcessing
   └─ Transformer.exportEditedMediaItem()  →  ContentResolver (MediaStore)
        │
        └─ progress → StateFlow → notification + in-app sheet
```

Android platform details that are not optional:

- On API 34+, a media-processing foreground service **must** declare `android:foregroundServiceType="mediaProcessing"` and hold `FOREGROUND_SERVICE_MEDIA_PROCESSING`, or the service is killed at start.
- On API 31+, foreground services generally **cannot be started from the background**. Export is therefore initiated while the app is foregrounded, and the service is *started immediately* at that moment rather than on a WorkManager delay.
- Export must handle configuration changes and process death: the request is persisted, and on restart the app offers to resume. A cancelled or crashed export leaves no partial file in MediaStore — write to app-private storage, then publish on success.

### 8.4 Preview renderer abstraction

Because `CompositionPlayer` is experimental:

```kotlin
interface PreviewRenderer {
    fun attach(surface: SurfaceView, graph: RenderGraph)
    fun play(); fun pause(); fun seekTo(us: Long)
    fun release()
    val state: StateFlow<PreviewState>
    val positionUs: StateFlow<Long>   // the playhead follows this while playing (FR-2.10)
}

class CompositionPlayerRenderer : PreviewRenderer   // preferred
class ExoPlayerRenderer : PreviewRenderer           // fallback

// Selected by a build flag; remote-disableable if a device-specific
// CompositionPlayer bug appears in the field.
```

This is the single most valuable abstraction in the render layer: it means an `@ExperimentalApi` regression in Media3 is a config change, not a release blocker.

---

## 9. Threading and resource model

### 9.1 Android's scarcest resource: hardware codecs

Android devices expose a small, globally shared pool of hardware decoder/encoder instances. Exceeding it does not degrade gracefully — it throws `MediaCodec.CodecException`. Nothing in the app may construct a `MediaCodec` (or a Media3 `Transformer`/`CompositionPlayer`) except through the broker.

```kotlin
// :core:media
class MediaResourceBroker @Inject constructor(
    private val codecCapacity: Int = detectHardwareCodecCapacity()
) {
    private val decoderSlots = Semaphore(codecCapacity.coerceIn(1, 3))
    private val encoderSlots = Semaphore(1)

    suspend fun <T> withDecoder(block: suspend () -> T): T
    suspend fun <T> withEncoder(block: suspend () -> T): T
}
```

Consequences: preview and export cannot run simultaneously (correct — it would exhaust codecs and thrash memory); thumbnail extraction queues behind playback; the failure mode becomes a short wait instead of a crash.

### 9.2 Threads

| Work | Dispatcher / thread |
|---|---|
| UI + intents | `Dispatchers.Main.immediate` |
| Document compile, commands | `Dispatchers.Default` (pure, CPU-light) |
| Project file I/O | `Dispatchers.IO` |
| Probing, thumbnails | `Dispatchers.IO` + broker |
| Export | Foreground service, its own scope |
| GL render | Media3's dedicated GL thread → C++ |
| Audio DSP | Oboe's high-priority thread → C++ |
| Native analysis | C++ worker pool (`std::thread`, sized `hardware_concurrency() - 1`) |

### 9.3 Memory discipline

- **No frame ever becomes a `Bitmap` in Kotlin state.** Frames live as GPU textures.
- **No per-frame allocation** in the native render path. Texture and FBO pools are preallocated at core construction and sized to the max canvas.
- **Thumbnails** are decoded at ≤ 160 px wide and cached in an LRU sized in *bytes* (16 MB), keyed by `(sourceId, positionUs)`.
- **Waveform** is a precomputed peak pyramid, not raw samples.
- `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` → drop thumbnail cache and any non-visible decode surfaces.
- Native allocations are tracked in debug builds and asserted against NFR-6.

---

## 10. Persistence

### 10.1 Project file

`filesDir/projects/<id>/document.json` — kotlinx.serialization, atomic write (write `.tmp`, `fsync`, `rename`). Sources referenced by SAF URI with persisted permission.

`filesDir/projects/<id>/cache/` — thumbnails, waveform peaks, proxies. **Regenerable**: deleting this directory must never lose user work. That rule is what makes cache-invalidation bugs non-catastrophic.

### 10.2 Migration

```kotlin
object DocumentMigrations {
    private val chain: Map<Int, (JsonElement) -> JsonElement> = mapOf(1 to ::v1ToV2)
    fun migrate(raw: JsonElement, from: Int): JsonElement
}
```

`schemaVersion` is written from day one. An unparseable *newer* version fails with a clear "this project was made with a newer version" message rather than silently corrupting.

### 10.3 Autosave

Debounced 1 s after the last document mutation, off the main thread, skipping if a write is in flight (last-write-wins with a dirty flag). Autosave must never block the UI, and must never be the reason a gesture drops a frame.

---

## 11. Build & configuration

```
redcut/
├── app/                        # composition root, Hilt, nav
├── feature/{home,editor,export,settings}/
├── domain/{document,render,project}/
├── engine/{media3,native}/
├── core/{common,media,ui}/
├── native/                     # CMake + C++ sources
├── gradle/libs.versions.toml
├── build-logic/                # convention plugins
└── docs/
```

- **Convention plugins** in `build-logic/` so each module's build file is ~10 lines and Android/Kotlin config exists once.
- **Version catalog** for all dependencies.
- **ABI splits** — ship `arm64-v8a` (+ `armeabi-v7a` if reach demands it). Universal APKs are how you fail NFR-7.
- **R8 full mode** with keep rules only for JNI entry points (mitigated by `RegisterNatives`).
- **Baseline Profiles** — required to hit NFR-1. Generate with `androidx.baselineprofile`, ship in the APK, and cover app start + first timeline scroll.
- **CI gates:** `:domain:*` purity check (§4.1 rule 1); detekt; ktlint; unit tests; `-Werror` native build; `ndk-stack` symbolication of any native test crash; APK size check against NFR-7.

---

## 12. Testing strategy

### 12.1 The JVM tier (fast, no device) — the bulk of the value

Because `:domain:*` is pure, this is where correctness lives. Target: **< 5 s for the full suite.**

| Target | Examples |
|---|---|
| `TimelineCompiler` | Clip ordering, speed-adjusted durations, effect scoping, canvas fit, fade clamping |
| Commands | Every command's apply; idempotence; invariants preserved |
| Cut semantics | Cut-left ≡ split + delete-left (property test); merge preconditions; min-duration clamping |
| Undo | N random command sequences → undo N → document equals initial (property test) |
| Migration | Every historical schema version parses to current |
| Serialization | Round-trip equality for generated documents (property test) |

### 12.2 Device tier

- Instrumented: import → cut → edit → effect → export on a fixture clip, asserting output exists with correct duration/resolution.
- Screenshot tests for each stage's UI.
- Compose UI tests for stage switching preserving document state (the core product promise).

### 12.3 Preview/export parity — the highest-value test in the suite

```
Given a fixture RenderGraph:
  1. Export via Transformer → MP4
  2. Render the same graph via CompositionPlayer → capture frames at t = 0.5s, 2s, 4s
  3. Extract the same timestamps from the exported MP4
  4. Assert per-channel mean absolute difference < threshold (e.g. 3/255)
```

This test is what makes "one RenderGraph, two compilers" a guarantee rather than a diagram. Run it in CI on an emulator with a software decoder, and nightly on a physical device.

### 12.4 Performance tier

`androidx.benchmark` Macrobenchmark on a fixed mid-tier reference device: startup (NFR-1), scrub (NFR-2), export throughput (NFR-4). **Track these in CI over time** — video editor performance regressions are gradual and invisible without a trend line.

---

## 13. Task breakdown

Estimates are **engineer-days** for one senior Android engineer. `∥` marks work that can run in parallel with the preceding item.

### Phase 0 — Foundation (5–7 d)

| # | Task | Est | Depends |
|---|---|---|---|
| 0.1 | New repo, Gradle KTS, version catalog, convention plugins in `build-logic/` | 1.5 | — |
| 0.2 | Module skeleton for all modules in §4.1, with dependency rules | 1 | 0.1 |
| 0.3 | CI: build, detekt, ktlint, unit tests, `:domain:*` purity check | 1 | 0.2 |
| 0.4 | **JNI seam proof**: CMake + one native `add(a,b)` call, `RegisterNatives`, 16 KB page alignment flag, native crash symbolication in CI | 1.5 | 0.2 |
| 0.5 | Hilt wiring; Timber; DataStore; navigation graph; stage scaffold | 1.5 | 0.2 |
| 0.6 | Baseline Profile infrastructure | 1 ∥ | 0.5 |

**Exit criterion:** app launches to a three-stage shell; CI is green; a native function returns a value on a physical device.

### Phase 1 — Document + Cut stage (12–15 d)

| # | Task | Est | Depends |
|---|---|---|---|
| 1.1 | `EditDocument`/`Clip`/`AppliedEffect`/`SourceRef` + serialization + `schemaVersion` | 2 | 0.2 |
| 1.2 | `EditCommand` hierarchy + `UndoStack` (snapshot, coalescing, 50 deep) + property tests | 2.5 | 1.1 |
| 1.3 | SAF import + probing → `SourceRef` (FR-1.1–1.5) | 2 | 1.1 |
| 1.4 | `:core:media` `MediaResourceBroker` + thumbnail extraction | 2 | 1.3 |
| 1.5 | Timeline `Canvas`: layout, clip rects, thumbnails, playhead, scroll/zoom | 4 | 1.1 |
| 1.6 | Trim gesture + live edge preview + commit/coalesce (FR-2.1) | 2.5 | 1.5, 1.2 |
| 1.7 | Split / cut-left / cut-right / delete / ripple (FR-2.2–2.6) | 2 | 1.6 |
| 1.8 | Merge with source-adjacency precondition (FR-2.4) | 1.5 | 1.7 |
| 1.9 | Reorder, duplicate, frame-step (FR-2.7–2.9) | 2 | 1.5 |
| 1.10 | `TimelineCompiler` v1 + JVM test suite | 2 | 1.1 |
| 1.11 | `CompositionPlayerRenderer` + `PreviewRenderer` iface + ExoPlayer fallback | 3 ∥ | 1.10 |

**Exit criterion: the Cut stage is genuinely usable end-to-end** — import several clips, trim, split, cut left/right, merge, reorder, scrub with correct preview. **This is the first shippable vertical slice.**

### Phase 2 — Edit stage (8–10 d)

| # | Task | Est | Depends |
|---|---|---|---|
| 2.1 | Inspector framework (slider row, segmented control, color picker, scrubber) | 2 | 0.5 |
| 2.2 | Speed with pitch-corrected audio + duration re-layout (FR-3.1) | 2.5 | 1.10 |
| 2.3 | Volume / mute / audio fades (FR-3.2–3.4) | 2 | 2.1 |
| 2.4 | Rotate / flip / crop / canvas fit (FR-3.5–3.8) | 3 | 2.1 |
| 2.5 | Reverse (FR-3.9) | 1.5 | 2.2 |

**Exit criterion:** every Edit tool changes preview correctly and survives undo/redo.

### Phase 3 — Effect stage (12–15 d)

| # | Task | Est | Depends |
|---|---|---|---|
| 3.1 | LUT pipeline: `.cube`/PNG LUT loading, 3D texture upload, `lut.frag` | 3 | 0.4 |
| 3.2 | `NativeGlEffect` + `NativeShaderProgram` Kotlin wrappers over the C++ core | 2.5 | 3.1 |
| 3.3 | Effect chain batching in C++ (`EffectChainCompiler`) | 3 | 3.2 |
| 3.4 | Filter presets — curate and author 8–12 LUTs (FR-4.1) | 2 | 3.1 |
| 3.5 | Color adjust + vignette shaders + inspector (FR-4.2) | 2 | 3.3 |
| 3.6 | Text overlay: model, drag/resize on preview, inspector (FR-4.3) | 3.5 | 2.1 |
| 3.7 | Image overlay: same + pinch/rotate (FR-4.4) | 2 | 3.6 |
| 3.8 | Cross-dissolve between adjacent clips (FR-4.5) | 2 | 3.3 |
| 3.9 | Effect stack list: reorder, scope, enable/disable (FR-4.6–4.8) | 2 | 3.5 |

**Exit criterion:** effects visible in preview; the effect stack reorders correctly; **effect timing is identical after export.**

### Phase 4 — Export + project lifecycle (10–12 d)

| # | Task | Est | Depends |
|---|---|---|---|
| 4.1 | `ExportService` foreground service, `mediaProcessing` type, notification | 2.5 | 1.10 |
| 4.2 | `Transformer` integration; 720p/1080p `OutputSpec`; bitrate presets (FR-5.1–5.7) | 3 | 4.1 |
| 4.3 | MediaStore publish + share sheet (FR-5.8, 5.12) | 1.5 | 4.2 |
| 4.4 | Progress/ETA/cancel (FR-5.9); backgrounding + screen-off survival (FR-5.10) | 2 | 4.2 |
| 4.5 | Preflight: storage, codec availability, estimated size (FR-5.11) | 1.5 | 4.2 |
| 4.6 | **Preview/export parity test harness** (§12.3) | 2.5 | 4.2, 1.11 |
| 4.7 | Project list, rename, duplicate, delete, autosave, restore (FR-6.1–6.4, 6.6) | 3 | 1.1 |

**Exit criterion: MVP COMPLETE.** A user can import, cut, edit, effect, and export a 1080p MP4 that matches the preview.

> **Execution order note.** Phases are numbered by topic, not by date. The actual order is **0 → 1 → 2 → 3 → 4 → 6 → 5**: Phase 6 (hardening) completes the MVP at M3, and only then does Phase 5 (native core) begin at M4. See §13.2.

> **Re-ordered by the user (2026-09-15).** After Phase 1, the delivery order is: **(1)** crop / rotate /
> flip / canvas fit (task 2.4) with the crop rect **keyframable**, **(2)** the keyframe system
> (per-property keys, interpolation, preview/export parity), **(3)** export to 1080p MP4, **(4)** caption
> (manual text overlay, FR-4.3). Phases 2–3 content follows afterwards; task 2.4 moves ahead of its
> phase. The remaining phase work is scheduled around these four.

### Phase 5 — Native core (committed, starts after M3)

| # | Task | Est |
|---|---|---|
| 5.1 | C++ `WaveformPyramid` + Oboe audio graph | 5 |
| 5.2 | `TimeStretcher` (WSOLA) — replaces Media3's stretcher for better quality at extreme speeds | 6 |
| 5.3 | `NativeAudioProcessor` replacing the Media3 audio chain | 4 |
| 5.4 | `SceneDetector` + auto-split suggestions | 4 |
| 5.5 | `FrameSampler` + frame-accurate seek index | 4 |
| 5.6 | Evaluate `VideoGraph.Factory` replacement gated on profiling evidence | 8 |

### Phase 6 — Hardening (5–8 d)

| # | Task | Est |
|---|---|---|
| 6.1 | Device-matrix testing (5+ devices incl. 2 mid-tier, 1 low-end) | 3 |
| 6.2 | Profile against NFR-1–NFR-8; fix regressions | 3 |
| 6.3 | Accessibility pass (NFR-12) | 1.5 |
| 6.4 | Empty/error/edge states; unsupported-codec UX | 1.5 |

### 13.1 Critical path and realistic totals

```
0.1 → 0.2 → 1.1 → 1.2 → 1.5 → 1.6 → 1.7 → 1.8 → 1.10 → 1.11 → 2.2 → 3.1 → 3.2 → 3.3 → 4.2 → 4.6
```

| Milestone | Duration (1 senior eng.) | Duration (2 eng.) |
|---|---|---|
| M1 — Cut stage shippable | ~5 weeks | ~3.5 weeks |
| M2 — MVP feature-complete | ~11 weeks | ~7 weeks |
| M3 — Hardened MVP | ~13 weeks | ~8.5 weeks |
| M4 — Native core complete | +4–6 months | +3 months |

**Reusing an existing codebase** (e.g. porting `CameraPathSolver`, the command/undo pattern, and `TrackingEngine` concepts from LibreCuts) can compress M1–M2 by roughly 1–2 weeks, concentrated in Phase 1.2 and Phase 3.

### 13.2 Delivery strategy — DECIDED: Media3 MVP first, C++ core after

**Decision (confirmed):** the MVP ships on Media3's stock render pipeline. The heavy C++ core is built afterwards and grown into the app behind the `:engine:native` seam.

- **Phases 0–4 ship the MVP** on Media3's stock pipeline. The app is fully functional; no MVP feature depends on native code.
- **Phase 5 grows the C++ core** behind the seam and the `NativeGlEffect` integration point, which exist from Phase 0 and Phase 3 respectively.
- **Phase 3 is the only overlap**, and only partially: `NativeGlEffect` proves the GL/JNI boundary with a trivial shader payload (D8, §6.8), which de-risks Phase 5 without taking over Media3's render loop.
- **The door stays open** only if the MVP honours D1–D7 in §6.8. Those rules are the entire reason this sequencing is safe; skipping them converts an incremental Phase 5 into a rewrite.

**Why deferring is genuinely low-risk here:** the C++ core attaches at two owned boundaries (`RenderGraph`, and Media3's `GlEffect`/`VideoGraph.Factory` extension points), not throughout the app. Nothing in `:domain:*` or `:feature:*` ever names a rendering technology, so the swap is confined to `:engine:*`.

**Revised milestone model:**

| Milestone | Contents | Duration (1 eng.) |
|---|---|---|
| M1 | Cut stage shippable | ~5 weeks |
| M2 | MVP feature-complete (Cut + Edit + Effect + Export) | ~11 weeks |
| M3 | Hardened MVP | ~13 weeks |
| **M4** | **C++ core: Oboe audio graph, WSOLA stretcher, waveform pyramid, scene detection, full shader set** | **+4–6 months** |
| M5 | Optional: `VideoGraph.Factory` replacement, gated on profiling evidence | +2 months |

M4 is now a committed phase rather than a contingency — but it starts *after* M3, and its scope is the pragmatic one in §6.2: the C++ core renders, consumes, and analyses. It does **not** reimplement decode or muxing, which Media3 already does well and which is where a from-scratch media core usually sinks.

---

## 14. Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | `CompositionPlayer` is `@ExperimentalApi`; multi-sequence preview immature | High | `PreviewRenderer` abstraction (§8.4) with `ExoPlayerRenderer` fallback and a remote kill switch. |
| R2 | Native render core overruns | High | Not on the MVP critical path (§13.2). Seam stubbed in Phase 0; core lands in Phase 5. |
| R3 | Preview/export divergence | High | Structural: one `RenderGraph`, two compilers, parity test in CI (§12.3). |
| R4 | Hardware codec exhaustion → `CodecException` crashes | High | `MediaResourceBroker` (§9.1); single owner of all codec resources. |
| R5 | 16 KB page-size incompatibility on Android 15+ | High | `-Wl,-z,max-page-size=16384`, asserted in CI. |
| R6 | Timeline jank at high clip counts | Medium | Custom `Canvas` timeline, not a composable list; Macrobenchmark trend in CI. |
| R7 | SAF URI permissions revoked or media deleted | Medium | Validate source availability on project open; clear "relink source" flow. |
| R8 | Exotic source codecs | Medium | FR-1.4 rejects with a specific reason; `AssetLoader` adapter leaves the FFmpeg door open. |
| R9 | Scope creep into the non-goal list | High | §1.3 is contractual. New features go to v2 unless they displace something. |
| R10 | Effect LUT licensing | Low | Author in-house or use only permissively-licensed LUTs; record provenance per file. |
| R11 | **D1–D7 (§6.8) quietly skipped under MVP deadline pressure**, foreclosing the deferred C++ core | High | These rules are cheap now and expensive later. Enforce D1 and D3 with automated CI checks (import bans, mapper-only construction), not code review. Re-audit D1–D7 at the M3 exit gate. |

---

## 15. Definition of done (MVP)

1. All FR-1 through FR-5 at *Must* priority implemented and tested.
2. All NFR targets met on the mid-tier reference device, with CI trend lines.
3. Preview/export parity test green over a fixture set of ≥ 10 documents.
4. Full JVM suite < 5 s; instrumented suite green on the device matrix.
5. Zero `CodecException` crashes across a 100-export soak test.
6. Round-trip: 20 projects created, force-killed, reopened — zero data loss.
7. APK < 25 MB per ABI; cold start < 1.5 s with Baseline Profiles.
8. Undo/redo correct across every stage and every tool.
9. TalkBack traversable; all controls labeled.
10. No GPL or otherwise incompatible dependency in the shipped artifact.

---

## Appendix A — Open decisions for review

| # | Decision | Options | Recommendation |
|---|---|---|---|
| A1 | Timeline visual style | Fixed-scale clips vs. zoomable | **Zoomable** (pinch), fixed min-width per clip |
| A2 | Effect scope default | Document-wide vs. active clip | **Active clip** if one is selected, else document |
| A3 | Canvas default | Portrait 9:16 vs. landscape 16:9 | Derive from the first imported clip's orientation; make it changeable |
| A4 | `minSdk` | 24 vs. 26 | **26** — `AHardwareBuffer` needs 26, and legacy storage disappears |
| A5 | Audio bed | Include in MVP? | **Yes** — it is FR-1.6 and cheap once the audio graph exists |
| A6 | Product name | RedCut | **Settled:** RedCut. `applicationId` `com.example.redcut` still needs replacing before release |

## Appendix B — Deliberate divergences from LibreCuts

Recorded so they are understood as choices, not accidents:

| LibreCuts | Here | Why |
|---|---|---|
| `VideoProject` imports `android.net.Uri`, extends `Serializable` | Domain is pure JVM (`SourceRef.uri: String`) | Enables the entire fast unit-test tier (§12.1) |
| 10,114-line `VideoEditingActivity` | Feature modules + Compose + ViewModel | Boundaries enforced by Gradle, not convention |
| 9 separate `StateFlow`s from one ViewModel | One `EditorUiState` | Removes the inconsistent-frame class of bugs |
| FFmpeg filter strings built in the ViewModel | `RenderGraph` + two compilers | Preview/export parity becomes structural |
| Snapshot positions stored on the project | `sourceInUs`/`sourceOutUs`; timeline position derived | Removes stale-position bugs across ripple edits |
| Free-form `MutateListCommand` lambdas | Named commands with `equals` | Comparable, loggable, coalescible, testable |
| Hand-rolled `Service` for proxies | Broker for codecs; service only for user-visible export | Matches Android's actual resource and lifecycle rules |
