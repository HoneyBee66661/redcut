# UI revision 1 — the layout and the timeline model

Notes from the device pass, in the order the user gave them. Written down verbatim-ish before any of it is
built, because half of this is a change of MODEL rather than of pixels, and the model is the part that is
expensive to discover late.

## 1. Home page

* A **+ new project** button, at the top.
* Below it, **the project list, if any**: thumbnails, gallery-like (a grid, not a list of names).

## 2. Opening a project: portrait, split 50 / 50

### Top 50 % — the preview

* A **viewport rectangle** over the preview frame:
  * it zooms in and out, and its **aspect ratio is LOCKED**;
  * it **pans**;
  * it **snaps to the horizontal and vertical centre when a clip is selected** — video clips only, in this
    case;
  * when the timeline has **no selection, the viewport does nothing at all**.
* The top **5 %** of this half is a bar:
  * **left, flush**: exit to the home page, and leaving triggers the **autosave** if anything was imported;
  * **right**: import and export;
  * with clips imported, **import becomes a resolution toggle** and **export becomes active**;
  * with no clips, **export is present but locked**, in its disabled state.

### Bottom 50 % — three slices

* **Top 5 %** (of the bottom half):
  * left: **undo / redo arrows**;
  * horizontally centred: **play / pause**;
  * right: **keyframe** button — **no logic yet, a placeholder**, tracked as a task (below).
* **Middle 85 %**: the **clip track and the other tracks**.
  * A **red vertical line, three times the track body's height?** (as given: "3 times timeline body
    height"), **centred horizontally, aligned to the top**. This line is the **static playhead**.
  * The **timeline body scrolls left and right** to position the playhead: **the tracks move, the playhead
    does not**. This is the opposite of what the editor does today, where the playhead is a position in the
    document drawn at its time.
  * **No elastic rubber-band cosmetics** — explicitly not wanted, so the overscroll note from the previous
    round is dropped rather than deferred.
* **Bottom 10 %**: the toolbar.

## The two things that are structural, not cosmetic

1. **The playhead becomes the centre of the viewport, not a document position.** Today `playheadUs` is a
   time in the timeline and the Canvas draws it wherever that time falls (`pxFor(playheadUs) -
   visibleStartPx`). Under the new model the line is a fixed screen position at the centre of the track
   area, and the SCROLL OFFSET is what encodes the playhead: scrolling left moves the tracks right, so
   earlier material passes under the line. That inverts the relationship the geometry currently assumes,
   and the arithmetic (scrub, trim, tap-to-seek, zoom-about-a-point) all has to be re-derived from it. The
   whole §9.1/§9.3 geometry is in scope.
2. **The stage bar disappears.** Today the three stages are a `TabRow` above the stage body. The new layout
   has no stage bar: the preview is always the preview, and the toolbar at the bottom is what changes with
   the stage. That is a change to `EditorUiState`'s shape (the stage stops being a tab selection and
   becomes what the bottom toolbar shows).

## Punch list (explicitly requested, not yet designed)

* **Keyframe button**: the button is wanted in the 5 % slice; the behaviour is not. Add it as a
  placeholder that does nothing, and keep the real feature tracked as its own task.
* **Resolution toggle**: what "import becomes a toggle of resolution" toggles (preview proxy resolution?
  the export resolution?) is a product question. The button exists and states its state; the menu behind it
  is not designed here.
* **Play / pause**: with the preview being a still frame at the playhead (Phase 1.11's state), play has
  nothing to play yet. The button's place in the layout is real; its behaviour arrives with the
  composition player.
