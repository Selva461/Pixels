# Roadmap

The 74 open features in [FEATURE_SPEC.md](FEATURE_SPEC.md) (63 planned, 11 partly built), split into
22 pull requests in the order they should land. Built features are not listed.

## Rules for every PR

- **Scope:** only the features listed for that PR. Each feature is done when its **Done when** holds.
- **Green CI:** security gate, string check, engine tests (warnings are errors), lint, unit tests,
  APK and device tests. Run `tools/offline-typecheck/run.sh` and the engine tests before pushing.
- **Docs:** update FEATURE_SPEC.md statuses, USER_GUIDE.md, CHANGELOG.md and HANDOFF.md in the same PR.
- **Hard limits:** no AI or generated pixels, no new permissions, no network, no new dependency
  without a written reason.
- **Branches:** the repository ruleset refuses pushes to existing branches, so every update goes out
  on a new branch and the PR is opened from the final green one. PRs stack on the previous one until
  the stack is merged; after that they target the default branch.
- **Release 1.0** needs PRs 1 and 2 plus the manual phone checklist in TESTING.md.

## Order at a glance

| PR | Title | Features | Top priority | Size | Builds on | Status |
|---|---|---|---|---|---|---|
| 1 | [Accessibility must-haves](#pr-1--accessibility-must-haves) | A-04, A-05, A-06, A-01 | P0 | M | — | In progress |
| 2 | [Speed baseline](#pr-2--speed-baseline) | P-01, P-02, P-03 | P0 | M | 1 | Not started |
| 3 | [Slider and compare controls](#pr-3--slider-and-compare-controls) | A-02, A-03, A-11, U-03, A-16 | P1 | M | 1 | Not started |
| 4 | [Keyboard, motion and overlays](#pr-4--keyboard-motion-and-overlays) | A-07, A-08, A-09 | P1 | M | 3 | Not started |
| 5 | [Point colour and global hue](#pr-5--point-colour-and-global-hue) | C-07, C-05 | P1 | M | — | Not started |
| 6 | [Masking upgrades](#pr-6--masking-upgrades) | M-04, M-05, M-10, M-11 | P1 | L | — | Not started |
| 7 | [Precise retouching](#pr-7--precise-retouching) | R-04, U-07 | P1 | M | — | Not started |
| 8 | [Adjustment layers and blend modes](#pr-8--adjustment-layers-and-blend-modes) | X-01, X-02 | P1 | L | 6 | Not started |
| 9 | [RAW and high bit depth](#pr-9--raw-and-high-bit-depth) | I-03, I-04, C-03 | P1 | XL | 2 | Not started |
| 10 | [Tone tools](#pr-10--tone-tools) | L-09, L-10, L-11 | P2 | M | — | Not started |
| 11 | [Looks and preset files](#pr-11--looks-and-preset-files) | C-11, C-12, W-09 | P2 | M | — | Not started |
| 12 | [Detail and geometry](#pr-12--detail-and-geometry) | D-04, G-02, G-07, G-08 | P2 | L | — | Not started |
| 13 | [Mask refinements](#pr-13--mask-refinements) | M-06, M-09, M-12 | P2 | M | 6 | Not started |
| 14 | [Removal, text and frames](#pr-14--removal-text-and-frames) | R-05, X-04, X-05 | P2 | L | 7 | Not started |
| 15 | [Colour management and save options](#pr-15--colour-management-and-save-options) | I-05, O-02, O-05, O-08 | P2 | L | 9 | Not started |
| 16 | [Accessibility and comfort extras](#pr-16--accessibility-and-comfort-extras) | A-10, A-12, A-15, U-05, U-08 | P2 | M | 1, 3 | Not started |
| 17 | [Ratings, flags and labels](#pr-17--ratings-flags-and-labels) | B-02 | P2 | S | — | Not started |
| 18 | [HDR merge](#pr-18--hdr-merge) | H-01 | P2 | L | 9 | Not started |
| 19 | [Creative effects](#pr-19--creative-effects) | E-03, E-04, C-13, R-06, X-03 | P3 | L | 8 | Not started |
| 20 | [Multi-photo merges](#pr-20--multi-photo-merges) | H-02, H-03, H-04 | P3 | XL | 18 | Not started |
| 21 | [Library and output extras](#pr-21--library-and-output-extras) | B-03, B-04, B-05, O-03, O-06, O-11, Z-01 | P3 | XL | 17 | Not started |
| 22 | [Scopes, canvas and gestures](#pr-22--scopes-canvas-and-gestures) | L-14, G-10, A-13, U-06 | P3 | M | 4 | Not started |

Sizes: S under a day, M a few days, L about a week, XL more than a week.

## Release 1.0 (P0)

### PR 1 — Accessibility must-haves

**Why together:** The four open must-haves that can be checked by machine, so release 1.0 does not rest on manual testing alone.

- **A-04 Text up to 200 per cent** (P0, planned). All text follows the phone's font size up to 200 per cent; layouts reflow instead of cutting text off. _Done when:_ at 200 per cent text and the largest display size, nothing on any screen is cut off or overlaps.
- **A-05 Touch targets of 48 dp** (P0, partial). Every tappable control is at least 48 by 48 dp, with space between neighbours. _Done when:_ an automated check finds no smaller target. Most controls pass today; small icon buttons are not yet checked.
- **A-06 Contrast in every theme** (P0, planned). Text has 4.5:1 contrast with its background; large text, icons, slider tracks and focus rings have 3:1. _Done when:_ a test checks every colour pair in every theme, and labels on the photo sit on a solid backing.
- **A-01 Screen reader support** (P0, partial). Every control has a spoken name, role, value and state, such as "Exposure, plus 0.35 EV". _Done when:_ a TalkBack user completes the five main tasks alone. Names and slider values are built; the walk-through on a phone is not done.

**Tests:** JVM test of every theme colour pair against 4.5:1 and 3:1; Compose UI tests on every screen for 48 dp touch targets, text at 200 % without overflow, and a spoken name on every control.

**Notes:** The TalkBack walk-through itself stays a manual release step (TESTING.md).

### PR 2 — Speed baseline

**Why together:** Measure before optimising: one timing harness serves all three targets.

- **P-01 Preview follows the finger** (P0, planned). While a slider moves, the preview updates within 100 ms for 95 per cent of updates, at 1280 px on the long edge. _Done when:_ measured on the reference phone with the main tools in use, and reported in each release's notes.
- **P-02 Fast first look** (P1, planned). A 12-megapixel photo shows its first preview within 1.5 seconds of opening. _Done when:_ measured from tap to first preview over 20 photos on the reference phone.
- **P-03 Full-size save** (P1, planned). A 24-megapixel photo saves at full size within 10 seconds, without running out of memory on a 4 GB phone. _Done when:_ 50 saves in a row finish on a 4 GB phone without a crash.

**Tests:** Engine timing tests on CI; an instrumented test that logs p95 preview time, time to first preview and 24 MP save time with peak memory.

**Notes:** The targets are confirmed only on the reference phone; CI numbers are a trend line.

## Next (P1)

### PR 3 — Slider and compare controls

**Why together:** All change the same editor controls, and they share the new Accessibility settings screen.

- **A-02 Type exact values** (P1, planned). Tap a slider's value to type a number instead of dragging. _Done when:_ the number keyboard has a minus sign, and values outside the range are corrected and announced.
- **A-03 Fine steps with − and +** (P1, planned). Each slider has − and + buttons. A press moves one step and holding repeats; the step is 1, 5 or 10. _Done when:_ every value of every slider can be reached without dragging, and each press is announced.
- **A-11 Compare without holding** (P1, planned). Compare also works as a toggle, a key and a switch action, and announces "Showing original" or "Showing edit". _Done when:_ compare works with a single tap, and every change is announced.
- **U-03 Edited markers** (P1, partial). Tools you have changed show a dot, and a screen reader says "edited" after the tool's name. _Done when:_ every dot has a spoken equivalent. The dots are built; the spoken state is not on every tool yet.
- **A-16 Plain language and help** (P1, partial). Everyday words for tools and messages. A built-in Guide explains every tool; each tool also gets a one-line hint. _Done when:_ every message says what happened and what to do next. The Guide is built; the hints are not.

**Tests:** UI tests for typing a value, − and + steps of 1, 5 and 10, compare as a toggle with announcements, and "edited" in tool names; unit tests for parsing and clamping typed values.

**Notes:** Follows the "Editor · Light" and "Accessibility settings" screen designs.

### PR 4 — Keyboard, motion and overlays

**Why together:** Input and perception options that reach every screen.

- **A-07 Keyboard, D-pad and Switch Access** (P1, planned). Every action works without touch. Focus is always visible and moves in reading order. _Done when:_ the five main tasks work by keyboard alone, with Ctrl+Z, Ctrl+Shift+Z and backslash to compare.
- **A-08 Reduce motion** (P1, planned). When the phone's Remove animations setting is on, panels and messages appear without sliding or fading. _Done when:_ with the setting on, nothing moves except the photo under your finger.
- **A-09 Colour-safe overlays** (P1, planned). Mask and clipping overlays use colours most colour-blind people can tell apart, with optional patterns. _Done when:_ overlays stay distinct in protanopia, deuteranopia and tritanopia simulations and in greyscale.

**Tests:** UI tests that drive the five main tasks with key events; a test with animations off; unit tests for overlay colours and patterns.

**Notes:** Shortcuts: Ctrl+Z, Ctrl+Shift+Z and backslash for compare.

### PR 5 — Point colour and global hue

**Why together:** Both are hue, saturation and luminance shifts in the same colour stage.

- **C-07 Point colour** (P1, planned). Pick any colour in the photo and shift its hue, saturation and luminance, with adjustable range and smoothness. _Done when:_ the affected areas can be shown on the photo, and range and smoothness update live.
- **C-05 Global hue** (P2, partial). Rotates every colour in the photo by the same angle. _Done when:_ ±30° by default to stay natural (built), with the full ±180° behind an extended-range option (missing).

**Tests:** Engine tests: colours outside the range are unchanged, smoothness leaves no hard edges, hue rotation is reversible.

**Notes:** Follows the "Tablet · Point colour" screen design.

### PR 6 — Masking upgrades

**Why together:** One change to the mask model; combining, control points and management all build on it.

- **M-04 Control points** (P1, planned). Tap a point; the mask spreads to similar colour and brightness nearby, within a radius you set. _Done when:_ a mask can hold several points, and each point's reach is shown on the overlay.
- **M-05 Combine masks** (P1, planned). Add, subtract and intersect mask parts. _Done when:_ any mask type can be combined, and the order of parts is visible and editable.
- **M-10 Dodge and burn** (P1, partial). Brighten or darken with soft brush strokes. _Done when:_ a dedicated Lighten and Darken brush with strength. Today this takes a brush mask with Exposure.
- **M-11 Mask management** (P1, partial). Up to 12 masks per photo, named, duplicated and reordered. _Done when:_ masks reorder by drag and by Move up and Move down buttons. Naming and duplicating are built.

**Tests:** Engine tests for add, subtract and intersect and for control-point spread; UI tests for naming, duplicating and reordering up to 12 masks.

**Notes:** Follows the "Editor · Masking" screen design. Changes saved edits: needs a format migration.

### PR 7 — Precise retouching

**Why together:** Brushing along a wire needs true 100 % zoom.

- **R-04 Brush heal and clone** (P1, planned). Paint along wires, cracks or long blemishes to remove them. _Done when:_ a stroke-shaped source is found automatically and can be moved.
- **U-07 Zoom to 100 per cent** (P1, partial). Pinch or double-tap to zoom. At 100 per cent the visible area is drawn from the full-resolution photo. _Done when:_ sharpening and noise at 100 per cent match the saved copy. Today zoom enlarges the preview.

**Tests:** Engine tests: brushed heal copies texture only from the photo; a zoomed render matches the same crop of the full-size save.

### PR 8 — Adjustment layers and blend modes

**Why together:** Blend modes only make sense with layers, and each layer has its own mask.

- **X-01 Adjustment layers** (P1, planned). Stack adjustments as layers, each with its own opacity and mask. _Done when:_ layers reorder by drag and by Move up and Move down buttons, and each can be hidden.
- **X-02 Blend modes** (P2, planned). Multiply, Screen, Overlay, Soft light, Colour and Luminosity for layers. _Done when:_ each mode matches its standard formula in the test set.

**Tests:** Engine tests for each blend mode against reference formulas; render-order tests.

**Notes:** Changes saved edits: needs a format migration and a version bump.

### PR 9 — RAW and high bit depth

**Why together:** RAW needs a high-bit pipeline, and kelvin white balance needs RAW data.

- **I-03 RAW and DNG** (P1, planned). Edit camera RAW files, with demosaicing and camera profiles. _Done when:_ DNG works first, then major camera formats through an open decoder (LibRaw); the RAW file is never modified.
- **I-04 High bit depth** (P2, planned). Process in 16-bit or floating point, so skies and gradients never band. _Done when:_ a smooth-gradient test photo shows no banding after strong curves.
- **C-03 Kelvin readout** (P1, planned). Shows and accepts white balance in kelvin for RAW photos. _Done when:_ 2,000 to 50,000 K, and a typed value is accepted (A-02).

**Tests:** Decode tests on sample DNG files; a banding test on a smooth gradient; kelvin round trips.

**Notes:** Largest PR. The decoder and its licence need the owner's approval before work starts.

## Later (P2)

### PR 10 — Tone tools

**Why together:** Three tone-shaping tools in the same tone stage.

- **L-09 Parametric curve** (P2, planned). Four tonal regions (shadows, darks, lights, highlights) with movable split points, for gentle shaping. _Done when:_ each region runs from −100 to +100 and matches the equivalent point curve.
- **L-10 Levels** (P2, planned). Black point, white point and gamma for each channel, with output levels. _Done when:_ a clipping preview shows which pixels each handle pushes to black or white.
- **L-11 Tone equalizer** (P2, planned). Changes exposure by tonal zone with an edge-aware mask, so local contrast is kept. _Done when:_ nine zones of ±2 EV each, with no halos along high-contrast edges in the test set.

**Tests:** Engine tests: identity at defaults, monotonic output, edge-aware masks keep local contrast.

### PR 11 — Looks and preset files

**Why together:** Looks and presets share import, validation and file handling.

- **C-11 Profiles and film looks** (P2, planned). Base renderings and film emulations applied before other edits, with an amount. _Done when:_ profiles are data files (3D LUTs) with an amount from 0 to 200 per cent.
- **C-12 LUT import** (P2, planned). Load a .cube 3D LUT as a look, with opacity. _Done when:_ malformed files are rejected with a clear message, and files are only ever read as data.
- **W-09 Preset files** (P2, planned). Import and export presets as files, to share or back up. _Done when:_ the format is open and documented, and malformed files are rejected with a message.

**Tests:** Parser tests with malformed .cube and preset files; amount 0 equals no look.

**Notes:** Imported files are untrusted input: size limits and strict parsing.

### PR 12 — Detail and geometry

**Why together:** Detail bands and geometry corrections that reuse the existing lens and perspective stages.

- **D-04 Structure bands** (P2, planned). Separate fine, medium and coarse local contrast controls. _Done when:_ three bands, each −100 to +100, with no halos up to +50 in the test set.
- **G-02 Lens profiles** (P2, planned). Automatic corrections for the lens and camera recorded in the photo. _Done when:_ an open lens database (Lensfun) supplies the corrections, and the manual sliders still fine-tune them.
- **G-07 Guided upright** (P2, planned). Draw two to four lines along edges that should be vertical or horizontal. _Done when:_ lines can be moved after drawing, and the result updates live.
- **G-08 Perspective crop** (P2, planned). Drag four corners onto a rectangle seen at an angle, such as a document or a painting. _Done when:_ the output is that rectangle, squared.

**Tests:** Engine tests on synthetic grids: guided lines become straight, perspective crop gives a rectangle.

**Notes:** Lens profile data needs a licence check before it is bundled.

### PR 13 — Mask refinements

**Why together:** Refinements on top of the new mask model.

- **M-06 Edge-aware refine** (P2, planned). Snaps a soft mask to nearby edges so an adjustment does not spill over them. _Done when:_ refine strength runs from 0 to 100, and the result shows on the overlay.
- **M-09 Local curves and grading** (P2, planned). Tone curve and colour grading inside a mask. _Done when:_ the same controls as the global tools, limited to the mask.
- **M-12 Stylus pressure** (P2, planned). Pressure and tilt change brush size or flow. _Done when:_ it works with active styluses, and finger input behaves as before.

**Tests:** Engine tests: refined masks stop at edges; local curves at defaults change nothing.

### PR 14 — Removal, text and frames

**Why together:** Finishes retouching and the overlay tools.

- **R-05 Classic content-aware removal** (P2, planned). Remove larger objects with a patch-based fill that copies texture from the photo itself. _Done when:_ every filled pixel comes from the photo, and the result can be refined with heal.
- **X-04 Text** (P2, partial). Editable text with font, size, colour and alignment. _Done when:_ text is a movable, editable layer. Today text appears only as a watermark on saved copies.
- **X-05 Frames and borders** (P2, partial). Plain borders and frame styles around the photo. _Done when:_ frames have a live preview and several styles. Today a thin, medium or thick white or black border is added to saved copies.

**Tests:** Engine tests: every filled pixel comes from the photo; text and frames render the same in preview and save.

### PR 15 — Colour management and save options

**Why together:** Wide gamut, new formats and save presets all live in the save path.

- **I-05 Wide gamut** (P2, partial). Keep Display P3 colours through editing and saving. _Done when:_ a P3 photo saved as P3 without edits keeps its colours. Today photos are converted to sRGB.
- **O-02 HEIC and AVIF** (P2, planned). Smaller files at the same quality. _Done when:_ devices without an encoder fall back to JPEG, with a message.
- **O-05 Colour profile** (P2, planned). Save in sRGB or Display P3. _Done when:_ the chosen profile is embedded in the file.
- **O-08 Save presets** (P2, planned). Named combinations of format, size, metadata, border and watermark. _Done when:_ one tap saves a copy with a chosen preset.

**Tests:** Device tests: P3 round trip, HEIC and AVIF saves open in other apps, save presets restore every option.

### PR 16 — Accessibility and comfort extras

**Why together:** Options that build on the PR 1 checks and the PR 3 settings screen.

- **A-10 Spoken histogram and clipping** (P2, planned). The histogram and clipping warnings get a text summary, such as "Mostly mid-tones, 2% of highlights clipped". _Done when:_ TalkBack reads the summary on request, and it updates after each change without interrupting.
- **A-12 High-contrast controls** (P2, planned). An option that raises control contrast to 7:1, thickens slider tracks and outlines every button. _Done when:_ with the option on, all text and controls in the editor reach 7:1.
- **A-15 Alt text on saved copies** (P2, planned). Add a short description when saving. It goes in the IPTC Alt Text (Accessibility) field for other apps to read. _Done when:_ it survives saving in every format with metadata, and removing location never removes it.
- **U-05 One-hand reach** (P2, planned). An option moves Undo, Redo and Compare to a bar just above the tools, within reach of one thumb. _Done when:_ with the option on, every frequent action is reachable by one thumb on a 6.7-inch phone.
- **U-08 Light theme outside the editor** (P2, planned). Home, Settings and the Guide can follow the phone's light or dark setting; the editor always stays dark. _Done when:_ both themes pass the contrast checks in A-06.

**Tests:** Contrast tests rerun for the light theme and the 7:1 option; a metadata test reads the alt text back.

### PR 17 — Ratings, flags and labels

**Why together:** First step for the library.

- **B-02 Ratings, flags and labels** (P2, planned). Star ratings, pick and reject flags, and colour labels. _Done when:_ every label has a name as well as a colour.

**Tests:** Unit tests for storage and filtering; UI test for rating from the keyboard.

### PR 18 — HDR merge

**Why together:** Needs the high-bit pipeline from PR 9.

- **H-01 HDR merge** (P2, planned). Combine bracketed exposures, with alignment and ghost removal. _Done when:_ handheld brackets align, and moving objects come from a single frame.

**Tests:** Engine tests on synthetic brackets: alignment, ghost removal, no clipping.

## To explore (P3)

### PR 19 — Creative effects

**Why together:** Effects that reuse layers, masks and blend modes.

- **E-03 Glow** (P3, planned). A soft bloom around bright areas, for a dreamy look. _Done when:_ strength and warmth are adjustable, and black stays black.
- **E-04 Lens blur and tilt-shift** (P3, planned). A blur you draw yourself, linear or elliptical, with a smooth falloff. No depth estimation is involved. _Done when:_ you draw and move the sharp area, and set blur strength and transition.
- **C-13 Skin tone uniformity** (P3, planned). Evens out skin hue and saturation inside a chosen colour range, without smoothing texture. _Done when:_ fine detail is unchanged; only the spread of hue and saturation narrows.
- **R-06 Frequency separation** (P3, planned). Edit colour and texture separately for careful retouching. _Done when:_ colour edits leave the texture layer unchanged.
- **X-03 Double exposure** (P3, planned). Blend a second photo with opacity and a blend mode. _Done when:_ the second photo can be moved, scaled and masked.

**Tests:** Engine tests: amount 0 equals no change for each effect.

### PR 20 — Multi-photo merges

**Why together:** Shares alignment with HDR merge.

- **H-02 Panorama** (P3, planned). Stitch overlapping photos into one wide image. _Done when:_ there is a choice of projection, with boundary warp or crop.
- **H-03 Focus stacking** (P3, planned). Merge photos focused at different distances into one sharp image. _Done when:_ frames align, and the source of each area can be reviewed and changed.
- **H-04 Burst stacking** (P3, planned). Average a burst to remove noise, or take the median to remove passers-by. _Done when:_ mean and median modes, with the frames aligned first.

**Tests:** Engine tests on synthetic shifted and refocused sets.

### PR 21 — Library and output extras

**Why together:** Extends the library and the save path.

- **B-03 Albums and keywords** (P3, planned). Group edits into albums and tag them. _Done when:_ keywords can be written into saved copies on request.
- **B-04 Search and filters** (P3, planned). Find edits by rating, date, camera, keyword or edit state. _Done when:_ results update as you type, and the number of results is announced.
- **B-05 Compare photos** (P3, planned). Two to four photos side by side, to choose the best. _Done when:_ zoom and pan stay in sync across the photos.
- **O-03 16-bit TIFF and PNG** (P3, planned). Lossless high-bit output for print and further editing. _Done when:_ 16 bits per channel are preserved in the file.
- **O-06 Soft proofing and gamut warning** (P3, planned). Preview how colours will look in a target profile, and mark the colours it cannot show. _Done when:_ the warning uses the overlay colours chosen under A-09.
- **O-11 Print and contact sheets** (P3, planned). Lay out one or more photos on a page, with margins. _Done when:_ Letter and A4 sizes can be saved as a PDF.
- **Z-01 Recorded actions** (P3, planned). Record a sequence of edits and replay it on other photos. _Done when:_ the recorded steps are listed and can be edited before replaying.

**Tests:** Unit tests for search, albums and recorded actions; device tests for TIFF and printing.

**Notes:** Can be split further when it starts.

### PR 22 — Scopes, canvas and gestures

**Why together:** Smaller extras, each optional and off by default.

- **L-14 Waveform and vectorscope** (P3, planned). Scopes that show brightness by position across the photo, and colour by hue and saturation. _Done when:_ both are available as an optional scope view on tablets, each with a text summary.
- **G-10 Expand canvas** (P3, planned). Adds space around the photo and fills it from the edges with a classic patch-based fill, never generated content. _Done when:_ every filled pixel is copied from the photo, and the fill can be undone.
- **A-13 Left-handed layout** (P3, planned). Moves the tool rail and side panel to the left edge, and swaps the main buttons on phones. _Done when:_ the layout switches without mirroring the photo or changing the reading order.
- **U-06 Swipe to adjust** (P3, planned). Swipe up or down on the photo to pick a setting and sideways to change it, as an extra to the sliders. _Done when:_ it can be turned off, never replaces the sliders, and each change is announced.

**Tests:** Engine tests for scopes and canvas fill; UI tests that the swipe and left-handed modes keep every control reachable.
