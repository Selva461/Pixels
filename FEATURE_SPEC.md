# Feature specification

_Version 1.0 draft · 9 October 2026 · statuses as of roadmap PR 1 (accessibility must-haves)_

The best capabilities of established photo editors, written as requirements for Pixels. Every
feature has an ID, what it does, a **Done when** acceptance test, where the capability is
established ("Seen in"), its status in Pixels and a priority. The order of work is in
[ROADMAP.md](ROADMAP.md); the original product requirements stay in [REQUIREMENTS.md](REQUIREMENTS.md).

**Status.** Built: in the app with automated tests (not yet checked by hand on a phone).
Partial: some of it works; the row says what is missing. Planned: not started.
Not planned: left out on purpose, with the reason.

**Priority.** P0 must-have for release 1.0 · P1 next · P2 later · P3 to explore.

**Totals.** 137 features: 67 built, 9 partial, 61 planned, plus 5 not planned.
48 of the 50 P0 features are built; the open ones are A-01 Screen reader support (the TalkBack walk-through on a phone) and P-01 Preview follows the finger.

## Principles every feature must keep

1. **Natural first.** Corrections stay believable. Every automatic change has a written reason, and strength 0 is the original photo.
2. **Non-destructive.** The original is never written to. Every setting stays editable, in any order, after a restart.
3. **Private on the device.** No internet permission, no account and no analytics. Location is removed from saved copies unless you keep it.
4. **Accessible by design.** WCAG 2.2 AA, TalkBack, 200 per cent text, keyboard and Switch Access are release requirements, not extras.
5. **Fast feedback.** The preview follows the finger. Heavy work runs in the background and can always be cancelled.
6. **Real pixels only.** No generated content. Removal and fills copy texture from the photo itself, so the scene stays true.

## Contents

- Light and colour — Light and tone, Colour
- Detail, effects and geometry — Detail, Effects, Optics and geometry
- Local, retouch and compositing — Masks, Retouching, Layers and compositing, Merges
- Workflow, library and files — Editing workflow, Library, Files in, Files out, Automation
- Accessibility, interaction, speed and privacy — Accessibility, Interaction and layout, Performance targets, Privacy and security, How each release is checked, Not planned, and why

## Light and colour

How a photo's brightness, tone and colour are corrected and shaped, from one-tap Auto to precise curves and colour tools.

### Light and tone

Global brightness and tone. Auto proposes a correction; the sliders and curves let you decide.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| L-01 | Auto Enhance | Analyses the photo and corrects exposure, colour cast, contrast and noise, with a written reason for each change. | strength 0 gives back the original exactly, and every automatic change is listed with its reason. | Lightroom, Apple Photos, Snapseed | Built | P0 |
| L-02 | Scene-aware Auto | Recognises scenes such as portrait, landscape, food and night, and applies limits that suit each one. | the detected scene is shown, and choosing another scene re-renders the preview. | Pixels original | Built | P1 |
| L-03 | Exposure | Brightens or darkens the whole photo in camera stops. | the range is ±3 EV in 0.01 steps, 0 is neutral, and a double-tap resets it. | Every major editor | Built | P0 |
| L-04 | Contrast, Highlights, Shadows, Whites, Blacks | The core tone controls: recover bright and dark detail and set the end points. | each runs from −100 to +100 and adds no clipping at 0. | Lightroom, Capture One, Snapseed | Built | P0 |
| L-05 | Brightness, Midtones, Gamma | Fine controls for the middle of the tonal range. | Midtones moves the mid-grey point without moving black or white. | Photoshop, Affinity Photo | Built | P1 |
| L-06 | Face-aware exposure | Finds faces on the device and lifts them towards a natural level, including in backlit portraits. | photos without faces are unchanged, and faces never get darker from the global exposure cut. | Pixels original | Built | P1 |
| L-07 | Point tone curve | Master, red, green and blue curves with points you place and drag over the histogram. | curves never invert; tap adds a point, drag moves it, double-tap removes it. | Lightroom, Snapseed, Photoshop | Built | P0 |
| L-08 | Curve presets | One-tap starting curves: Linear, Medium contrast, Strong contrast, Lift shadows and Faded. | a preset is one undo step and stays editable point by point. | Lightroom, Snapseed | Built | P1 |
| L-09 | Parametric curve | Four tonal regions (shadows, darks, lights, highlights) with movable split points, for gentle shaping. | each region runs from −100 to +100 and matches the equivalent point curve. | Lightroom | Planned | P2 |
| L-10 | Levels | Black point, white point and gamma for each channel, with output levels. | a clipping preview shows which pixels each handle pushes to black or white. | Photoshop, Affinity Photo, Capture One | Planned | P2 |
| L-11 | Tone equalizer | Changes exposure by tonal zone with an edge-aware mask, so local contrast is kept. | nine zones of ±2 EV each, with no halos along high-contrast edges in the test set. | darktable | Planned | P2 |
| L-12 | Clipping warnings | Marks areas that have turned pure white or pure black. | a toggle on the Light panel shows them, in the overlay colours chosen under A-09, after every edit. | Lightroom, Capture One | Built | P0 |
| L-13 | Histogram | Live luminance or RGB histogram of the edited photo. | it updates after every preview render, in luminance and in RGB mode. | Every major editor | Built | P0 |
| L-14 | Waveform and vectorscope | Scopes that show brightness by position across the photo, and colour by hue and saturation. | both are available as an optional scope view on tablets, each with a text summary. | darktable | Planned | P3 |

### Colour

White balance, colour strength and colour shaping. Neutral greys stay neutral unless you choose otherwise.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| C-01 | White balance: Auto, As shot, Pick | Removes colour casts automatically, keeps the camera's own colours, or neutralises a spot you tap. | Pick turns the tapped spot neutral grey, and As shot skips only the automatic correction. | Lightroom, Snapseed | Built | P0 |
| C-02 | Temperature and Tint | Warms or cools the photo and corrects green or magenta casts. | relative values from −100 to +100 for JPEG and other non-RAW photos, and kelvin for RAW (C-03). | Every major editor | Built | P0 |
| C-03 | Kelvin readout | Shows and accepts white balance in kelvin for RAW photos. | 2,000 to 50,000 K, and a typed value is accepted (A-02). | Lightroom, Capture One | Planned | P1 |
| C-04 | Vibrance and Saturation | Vibrance lifts muted colours and protects skin; Saturation changes all colours equally. | at the same setting, skin hues change less with Vibrance than with Saturation. | Lightroom, Apple Photos | Built | P0 |
| C-05 | Global hue | Rotates every colour in the photo by the same angle. | ±30° by default to stay natural (built), with the full ±180° behind an extended-range option (missing). | Photoshop, Affinity Photo | Partial | P2 |
| C-06 | Colour mixer | Hue, saturation and luminance for eight colour bands. | greys stay grey, and each band changes only its own hues with a smooth falloff. | Lightroom, Capture One | Built | P0 |
| C-07 | Point colour | Pick any colour in the photo and shift its hue, saturation and luminance, with adjustable range and smoothness. | the affected areas can be shown on the photo, and range and smoothness update live. | Lightroom, Capture One | Planned | P1 |
| C-08 | Colour grading | Shadows, midtones and highlights wheels plus a global wheel, with blending and balance. | neutral wheels change nothing, and blending and balance are saved with the edit. | Lightroom, Capture One | Built | P0 |
| C-09 | Calibration | Re-tunes the red, green and blue primaries (hue and saturation) and the shadows tint. | neutral greys stay neutral at every setting. | Lightroom, darktable | Built | P1 |
| C-10 | Black and white | Converts to monochrome with a brightness mix for each colour. | switching back to colour restores the photo without loss. | Lightroom, Snapseed | Built | P0 |
| C-11 | Profiles and film looks | Base renderings and film emulations applied before other edits, with an amount. | profiles are data files (3D LUTs) with an amount from 0 to 200 per cent. | Lightroom, VSCO, DxO FilmPack | Planned | P2 |
| C-12 | LUT import | Load a .cube 3D LUT as a look, with opacity. | malformed files are rejected with a clear message, and files are only ever read as data. | Photoshop, Affinity Photo | Planned | P2 |
| C-13 | Skin tone uniformity | Evens out skin hue and saturation inside a chosen colour range, without smoothing texture. | fine detail is unchanged; only the spread of hue and saturation narrows. | Capture One | Planned | P3 |

## Detail, effects and geometry

Sharpness and noise, finishing effects, and everything that changes the photo's shape: lens fixes, perspective, straightening and crop.

### Detail

Sharpening, noise and local contrast. All are classic algorithms that run on the phone.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| D-01 | Sharpening | Amount, radius, detail and masking. Masking keeps flat areas such as sky and skin smooth. | all four controls work from the Detail panel and reset with a double-tap. | Lightroom, Capture One | Built | P0 |
| D-02 | Noise reduction | Separate luminance and colour noise reduction, using classic on-device algorithms. | luminance and colour are separate controls, and low settings keep fine detail. | Lightroom, DxO PhotoLab | Built | P0 |
| D-03 | Texture and Clarity | Texture adjusts fine detail; Clarity adjusts mid-size local contrast. | both run from −100 to +100, and negative values soften. | Lightroom, Capture One | Built | P0 |
| D-04 | Structure bands | Separate fine, medium and coarse local contrast controls. | three bands, each −100 to +100, with no halos up to +50 in the test set. | Snapseed, Capture One | Planned | P2 |
| D-05 | Dehaze | Removes or adds atmospheric haze. | 0 is neutral, and strong settings are flagged by the clipping warning. | Lightroom, DxO PhotoLab | Built | P0 |

### Effects

Finishing touches that add mood without changing what is in the photo.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| E-01 | Vignette | Darkens or lightens the corners after cropping, with midpoint, feather and roundness. | the vignette follows the crop, and roundness goes from rectangle to circle. | Lightroom, Snapseed | Built | P1 |
| E-02 | Grain | Film-like grain with size and roughness. | amount, size and roughness are separate controls. | Lightroom, VSCO, Snapseed | Built | P1 |
| E-03 | Glow | A soft bloom around bright areas, for a dreamy look. | strength and warmth are adjustable, and black stays black. | Snapseed | Planned | P3 |
| E-04 | Lens blur and tilt-shift | A blur you draw yourself, linear or elliptical, with a smooth falloff. No depth estimation is involved. | you draw and move the sharp area, and set blur strength and transition. | Snapseed | Planned | P3 |

### Optics and geometry

Lens fixes, perspective, straightening and crop. Each one zooms the photo just enough to hide empty edges.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| G-01 | Lens corrections | Manual distortion, chromatic aberration and lens vignetting. | the photo zooms just enough to hide empty edges. | Lightroom, DxO PhotoLab, Capture One | Built | P1 |
| G-02 | Lens profiles | Automatic corrections for the lens and camera recorded in the photo. | an open lens database (Lensfun) supplies the corrections, and the manual sliders still fine-tune them. | Lightroom, DxO PhotoLab, darktable | Planned | P2 |
| G-03 | Defringe | Removes purple and green fringes along high-contrast edges. | fringes next to edges are removed, while purple and green objects keep their colour. | Lightroom, Capture One | Built | P1 |
| G-04 | Crop, rotate and flip | Free and fixed ratios from 1:1 to 21:9 and their portrait versions, quarter turns, and horizontal or vertical flip. | the crop applies when you leave the tool and is a single undo step. | Every major editor | Built | P0 |
| G-05 | Straighten and auto level | Straighten by up to ±45°, or let Auto level a tilted horizon. | no empty corners appear after straightening. | Lightroom, Snapseed | Built | P0 |
| G-06 | Perspective and Auto upright | Vertical, horizontal, rotate, aspect, scale and offset, plus automatic upright for buildings. | leaning buildings stand straight, with no empty edges. | Lightroom, Snapseed | Built | P1 |
| G-07 | Guided upright | Draw two to four lines along edges that should be vertical or horizontal. | lines can be moved after drawing, and the result updates live. | Lightroom, darktable | Planned | P2 |
| G-08 | Perspective crop | Drag four corners onto a rectangle seen at an angle, such as a document or a painting. | the output is that rectangle, squared. | Photoshop | Planned | P2 |
| G-09 | Grid and guides | Rule-of-thirds and fine grid overlays while cropping and correcting perspective. | grids help while editing and never appear in saved copies. | Lightroom, Capture One | Built | P1 |
| G-10 | Expand canvas | Adds space around the photo and fills it from the edges with a classic patch-based fill, never generated content. | every filled pixel is copied from the photo, and the fill can be undone. | Snapseed | Planned | P3 |

## Local, retouch and compositing

Edits limited to part of the photo, repairs that copy real pixels, and tools that combine layers or several photos.

### Masks

Masks limit an adjustment to part of the photo. Every mask here is drawn or sampled by you; none relies on AI detection.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| M-01 | Brush mask | Paint and erase a mask with size, feather and flow. | strokes follow the finger smoothly, and Erase removes coverage exactly. | Lightroom, Capture One, Snapseed | Built | P0 |
| M-02 | Linear and radial gradients | Graduated and elliptical masks drawn on the photo, with handles and feather. | handles move and resize the mask, and feather runs from 0 to 100. | Lightroom, Capture One | Built | P0 |
| M-03 | Luminance and colour range | Select by brightness or by a sampled colour, on their own or to refine another mask. | range and smoothness are adjustable, and a tap on the photo samples the colour. | Lightroom, Capture One, darktable | Built | P0 |
| M-04 | Control points | Tap a point; the mask spreads to similar colour and brightness nearby, within a radius you set. | a mask can hold several points, and each point's reach is shown on the overlay. | Snapseed, DxO PhotoLab | Planned | P1 |
| M-05 | Combine masks | Add, subtract and intersect mask parts. | any mask type can be combined, and the order of parts is visible and editable. | Lightroom, darktable | Planned | P1 |
| M-06 | Edge-aware refine | Snaps a soft mask to nearby edges so an adjustment does not spill over them. | refine strength runs from 0 to 100, and the result shows on the overlay. | Capture One, darktable | Planned | P2 |
| M-07 | Overlay and invert | Show where a mask applies, and invert it to adjust the opposite area. | invert is one tap and one undo step, and overlay colours follow A-09. | Lightroom, Capture One | Built | P0 |
| M-08 | Local adjustments | Exposure, contrast, highlights, shadows, temperature, tint, saturation, clarity and sharpness inside each mask. | each mask has its own nine sliders, and masks stay on target after crop and rotation. | Lightroom, Capture One | Built | P0 |
| M-09 | Local curves and grading | Tone curve and colour grading inside a mask. | the same controls as the global tools, limited to the mask. | Capture One, darktable | Planned | P2 |
| M-10 | Dodge and burn | Brighten or darken with soft brush strokes. | a dedicated Lighten and Darken brush with strength. Today this takes a brush mask with Exposure. | Photoshop, Affinity Photo | Partial | P1 |
| M-11 | Mask management | Up to 12 masks per photo, named, duplicated and reordered. | masks reorder by drag and by Move up and Move down buttons. Naming and duplicating are built. | Lightroom | Partial | P1 |
| M-12 | Stylus pressure | Pressure and tilt change brush size or flow. | it works with active styluses, and finger input behaves as before. | Photoshop, Affinity Photo | Planned | P2 |

### Retouching

Removal and repair that copies real pixels from the photo. Nothing is invented.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| R-01 | Spot heal | Tap a blemish; a matching source is found automatically and blended in. | the source can be dragged, and size, feather and opacity are adjustable. | Lightroom, Snapseed | Built | P0 |
| R-02 | Clone | Copies the source exactly, for patterns and hard edges. | clone uses the same handles as heal, and the mode can be switched per spot. | Lightroom, Photoshop, Affinity Photo | Built | P0 |
| R-03 | Red eye | Removes red pupils in flash photos. | only strongly red pixels inside the circle change. | Lightroom, Photoshop | Built | P1 |
| R-04 | Brush heal and clone | Paint along wires, cracks or long blemishes to remove them. | a stroke-shaped source is found automatically and can be moved. | Lightroom, Photoshop, Affinity Photo | Planned | P1 |
| R-05 | Classic content-aware removal | Remove larger objects with a patch-based fill that copies texture from the photo itself. | every filled pixel comes from the photo, and the result can be refined with heal. | Photoshop, Affinity Photo, GIMP | Planned | P2 |
| R-06 | Frequency separation | Edit colour and texture separately for careful retouching. | colour edits leave the texture layer unchanged. | Affinity Photo, Photoshop | Planned | P3 |

### Layers and compositing

Stacking adjustments and images, with the standard blend modes.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| X-01 | Adjustment layers | Stack adjustments as layers, each with its own opacity and mask. | layers reorder by drag and by Move up and Move down buttons, and each can be hidden. | Capture One, Affinity Photo, Photoshop | Planned | P1 |
| X-02 | Blend modes | Multiply, Screen, Overlay, Soft light, Colour and Luminosity for layers. | each mode matches its standard formula in the test set. | Photoshop, Affinity Photo, darktable | Planned | P2 |
| X-03 | Double exposure | Blend a second photo with opacity and a blend mode. | the second photo can be moved, scaled and masked. | Snapseed, Photoshop | Planned | P3 |
| X-04 | Text | Editable text with font, size, colour and alignment. | text is a movable, editable layer. Today text appears only as a watermark on saved copies. | Snapseed, Photoshop | Partial | P2 |
| X-05 | Frames and borders | Plain borders and frame styles around the photo. | frames have a live preview and several styles. Today a thin, medium or thick white or black border is added to saved copies. | Snapseed, VSCO | Partial | P2 |

### Merges

Combining several photos of the same scene into one.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| H-01 | HDR merge | Combine bracketed exposures, with alignment and ghost removal. | handheld brackets align, and moving objects come from a single frame. | Lightroom, Affinity Photo | Planned | P2 |
| H-02 | Panorama | Stitch overlapping photos into one wide image. | there is a choice of projection, with boundary warp or crop. | Lightroom, Affinity Photo | Planned | P3 |
| H-03 | Focus stacking | Merge photos focused at different distances into one sharp image. | frames align, and the source of each area can be reviewed and changed. | Affinity Photo, Photoshop | Planned | P3 |
| H-04 | Burst stacking | Average a burst to remove noise, or take the median to remove passers-by. | mean and median modes, with the frames aligned first. | Affinity Photo, Photoshop | Planned | P3 |

## Workflow, library and files

How edits are kept, repeated and organised, which files come in, and how finished copies go out.

### Editing workflow

Edits are settings, not baked pixels, so they can be revisited, repeated and copied.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| W-01 | Non-destructive editing | The original is never changed; edits are stored as settings. | originals are byte-for-byte identical after any edit or save. | Every major editor | Built | P0 |
| W-02 | Always-editable settings | Every setting stays adjustable at any time, in any order. | changing an early setting keeps every later one. | Lightroom, Snapseed | Built | P0 |
| W-03 | Undo, redo and named history | Every change is a named step, such as "Exposure, Contrast +2"; tap any step to return to it. | history survives a restart, and redo steps stay until you make a new change. | Lightroom, darktable | Built | P0 |
| W-04 | Versions | Named snapshots of an edit inside one project. | you can save, apply and delete versions, and applying one is a single undo step. | Lightroom | Built | P1 |
| W-05 | Virtual copies | Several independent edits of the same original photo. | a duplicate keeps its own history and preview, and the original is untouched. | Lightroom, Capture One | Built | P1 |
| W-06 | Presets with amount | 29 built-in presets in eight groups, plus your own, with an amount from 0 to 200 per cent. | 0 per cent gives back the edit from before the preset exactly. | Lightroom, VSCO | Built | P0 |
| W-07 | Copy and paste settings | Copy chosen groups of settings from one photo and paste them onto another. | crop, masks and healing are never pasted, because they belong to one photo. | Lightroom, Snapseed, Capture One | Built | P0 |
| W-08 | Batch apply | Apply chosen settings to up to 50 photos and save each as a new file. | progress, Cancel and a summary of saved and failed photos are shown. | Lightroom | Built | P1 |
| W-09 | Preset files | Import and export presets as files, to share or back up. | the format is open and documented, and malformed files are rejected with a message. | Lightroom | Planned | P2 |
| W-10 | Autosave and recovery | Every change is saved as you go, and edits reopen after a restart. | no change is lost after the app is force-stopped. | Lightroom | Built | P0 |

### Library

Finding and choosing between edits. Everything stays in Pixels' private storage.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| B-01 | Recent edits | Thumbnails of every edit, with rename, duplicate and remove. | removing an edit asks first and never deletes the photo. | Lightroom, Apple Photos | Built | P0 |
| B-02 | Ratings, flags and labels | Star ratings, pick and reject flags, and colour labels. | every label has a name as well as a colour. | Lightroom, Capture One, darktable | Planned | P2 |
| B-03 | Albums and keywords | Group edits into albums and tag them. | keywords can be written into saved copies on request. | Lightroom | Planned | P3 |
| B-04 | Search and filters | Find edits by rating, date, camera, keyword or edit state. | results update as you type, and the number of results is announced. | Lightroom | Planned | P3 |
| B-05 | Compare photos | Two to four photos side by side, to choose the best. | zoom and pan stay in sync across the photos. | Lightroom | Planned | P3 |

### Files in

Where photos come from and how faithfully they are read.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| I-01 | Picker, camera and share | Open from the system photo picker, take a photo, or share from another app. | no storage or camera permission is needed, and shared files are checked and copied in. | Pixels original | Built | P0 |
| I-02 | JPEG, PNG, WebP and HEIC | The formats phones and the web use every day. | orientation is applied before analysis, and damaged files show a clear message. | Every major editor | Built | P0 |
| I-03 | RAW and DNG | Edit camera RAW files, with demosaicing and camera profiles. | DNG works first, then major camera formats through an open decoder (LibRaw); the RAW file is never modified. | Lightroom, Capture One, darktable, RawTherapee | Planned | P1 |
| I-04 | High bit depth | Process in 16-bit or floating point, so skies and gradients never band. | a smooth-gradient test photo shows no banding after strong curves. | darktable, Affinity Photo | Planned | P2 |
| I-05 | Wide gamut | Keep Display P3 colours through editing and saving. | a P3 photo saved as P3 without edits keeps its colours. Today photos are converted to sRGB. | Lightroom, Apple Photos | Partial | P2 |

### Files out

Saving copies and sharing. A save is only reported when the file has been checked.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| O-01 | Save formats | JPEG, PNG and WebP, with quality for JPEG and WebP. | saved files open in other apps at the size requested. | Every major editor | Built | P0 |
| O-02 | HEIC and AVIF | Smaller files at the same quality. | devices without an encoder fall back to JPEG, with a message. | Apple Photos, Lightroom | Planned | P2 |
| O-03 | 16-bit TIFF and PNG | Lossless high-bit output for print and further editing. | 16 bits per channel are preserved in the file. | Lightroom, Capture One, Affinity Photo | Planned | P3 |
| O-04 | Size and metadata | Full size or a long-edge size; keep all metadata, remove location, or remove all. | location is removed by default, and the exact pixel size is shown before saving. | Lightroom | Built | P0 |
| O-05 | Colour profile | Save in sRGB or Display P3. | the chosen profile is embedded in the file. | Lightroom, Photoshop | Planned | P2 |
| O-06 | Soft proofing and gamut warning | Preview how colours will look in a target profile, and mark the colours it cannot show. | the warning uses the overlay colours chosen under A-09. | Lightroom Classic, Photoshop | Planned | P3 |
| O-07 | Border and watermark | A plain border and a text watermark, on saved copies only. | neither is ever drawn on the photo being edited. | Lightroom, VSCO | Built | P1 |
| O-08 | Save presets | Named combinations of format, size, metadata, border and watermark. | one tap saves a copy with a chosen preset. | Lightroom | Planned | P2 |
| O-09 | Verified saving | Writes a hidden file, checks it, then publishes it; Cancel deletes any partial file. | Pixels never reports "Saved" for a file that cannot be opened. | Pixels original | Built | P0 |
| O-10 | Share | Send a copy to another app without saving it to the gallery. | the temporary copy can be cleared from Settings. | Every major editor | Built | P0 |
| O-11 | Print and contact sheets | Lay out one or more photos on a page, with margins. | Letter and A4 sizes can be saved as a PDF. | Lightroom Classic | Planned | P3 |

### Automation

Repeating work without writing code. Third-party scripts are out of scope (see page 6).

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| Z-01 | Recorded actions | Record a sequence of edits and replay it on other photos. | the recorded steps are listed and can be edited before replaying. | Photoshop, Affinity Photo | Planned | P3 |

## Accessibility, interaction, speed and privacy

Who can use Pixels and how it feels in the hand, how fast it must be, what it keeps private, and what it leaves out on purpose.

### Accessibility

Pixels must work with a screen reader, with large text, without fine finger control and without relying on colour. Each row names the WCAG 2.2 criterion it meets.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| A-01 | Screen reader support | Every control has a spoken name, role, value and state, such as "Exposure, plus 0.35 EV". | a TalkBack user completes the five main tasks alone. Names, roles, values and states are built and checked on every screen by AccessibilityTest; the walk-through on a phone is not done. | TalkBack · WCAG 4.1.2 Name, Role, Value | Partial | P0 |
| A-02 | Type exact values | Tap a slider's value to type a number instead of dragging. | the number keyboard has a minus sign, and values outside the range are corrected and announced. | Lightroom Classic, Capture One · WCAG 2.5.7 Dragging Movements | Planned | P1 |
| A-03 | Fine steps with − and + | Each slider has − and + buttons. A press moves one step and holding repeats; the step is 1, 5 or 10. | every value of every slider can be reached without dragging, and each press is announced. | WCAG 2.5.7 Dragging Movements | Planned | P1 |
| A-04 | Text up to 200 per cent | All text follows the phone's font size up to 200 per cent; layouts reflow instead of cutting text off. | at 200 per cent text and the largest display size, nothing on any screen is cut off or overlaps. AccessibilityTest checks cut-off text at 200 % on a 320 dp screen; overlap is checked by hand. | WCAG 1.4.4 Resize Text, 1.4.10 Reflow | Built | P0 |
| A-05 | Touch targets of 48 dp | Every tappable control is at least 48 by 48 dp, with space between neighbours. | an automated check finds no smaller target. AccessibilityTest checks every tappable control on every screen. | Android accessibility guidelines · WCAG 2.5.8 Target Size | Built | P0 |
| A-06 | Contrast in every theme | Text has 4.5:1 contrast with its background; large text, icons, slider tracks and focus rings have 3:1. | a test checks every colour pair in every theme, and labels on the photo sit on a solid backing. ContrastTest covers both schemes and photo labels. | WCAG 1.4.3 Contrast, 1.4.11 Non-text Contrast | Built | P0 |
| A-07 | Keyboard, D-pad and Switch Access | Every action works without touch. Focus is always visible and moves in reading order. | the five main tasks work by keyboard alone, with Ctrl+Z, Ctrl+Shift+Z and backslash to compare. | Lightroom Classic shortcuts · WCAG 2.1.1 Keyboard, 2.4.7 Focus Visible | Planned | P1 |
| A-08 | Reduce motion | When the phone's Remove animations setting is on, panels and messages appear without sliding or fading. | with the setting on, nothing moves except the photo under your finger. | Android settings · WCAG 2.3.3 Animation from Interactions | Planned | P1 |
| A-09 | Colour-safe overlays | Mask and clipping overlays use colours most colour-blind people can tell apart, with optional patterns. | overlays stay distinct in protanopia, deuteranopia and tritanopia simulations and in greyscale. | Lightroom overlay colour · WCAG 1.4.1 Use of Color | Planned | P1 |
| A-10 | Spoken histogram and clipping | The histogram and clipping warnings get a text summary, such as "Mostly mid-tones, 2% of highlights clipped". | TalkBack reads the summary on request, and it updates after each change without interrupting. | WCAG 1.1.1 Non-text Content | Planned | P2 |
| A-11 | Compare without holding | Compare also works as a toggle, a key and a switch action, and announces "Showing original" or "Showing edit". | compare works with a single tap, and every change is announced. | WCAG 2.1.1 Keyboard, 4.1.3 Status Messages | Planned | P1 |
| A-12 | High-contrast controls | An option that raises control contrast to 7:1, thickens slider tracks and outlines every button. | with the option on, all text and controls in the editor reach 7:1. | WCAG 1.4.6 Contrast (Enhanced) | Planned | P2 |
| A-13 | Left-handed layout | Moves the tool rail and side panel to the left edge, and swaps the main buttons on phones. | the layout switches without mirroring the photo or changing the reading order. | Procreate (right-hand interface) | Planned | P3 |
| A-14 | Haptic feedback | A light tick when a slider lands on its starting value or is reset with a double tap, like a detent. | it can be turned off in Settings and follows the phone's touch feedback setting. | Apple Photos | Built | P2 |
| A-15 | Alt text on saved copies | Add a short description when saving. It goes in the IPTC Alt Text (Accessibility) field for other apps to read. | it survives saving in every format with metadata, and removing location never removes it. | IPTC Photo Metadata 2021.1 | Planned | P2 |
| A-16 | Plain language and help | Everyday words for tools and messages. A built-in Guide explains every tool; each tool also gets a one-line hint. | every message says what happened and what to do next. The Guide is built; the hints are not. | Lightroom Learn · WCAG 3.3.2 Labels or Instructions | Partial | P1 |
| A-17 | Safe destructive actions | Reset, Delete and Discard either ask first or can be undone. | no single tap can lose an edit or a photo, and the original is never at risk. | WCAG 3.3.4 Error Prevention | Built | P0 |

### Interaction and layout

How the editor feels in the hand. Every gesture also has a button or a key that does the same thing.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| U-01 | Photo first | The photo gets all the space the controls do not need. Panels sit beside or below it, never on top. | only the handles of the tool in use are drawn on the photo. | Lightroom, Snapseed | Built | P0 |
| U-02 | Hold to compare and split view | Hold the photo to see the original. Split view shows before and after side by side with a movable divider. | letting go always returns to the edit, and the divider stays where you left it. | Lightroom, Snapseed | Built | P0 |
| U-03 | Edited markers | Tools you have changed show a dot, and a screen reader says "edited" after the tool's name. | every dot has a spoken equivalent. Tools, curve channels and colour bands say "edited". | Lightroom, Capture One | Built | P1 |
| U-04 | Landscape and tablet layouts | In landscape and on tablets the photo sits beside a side panel, with the tool rail along the edge. | every screen works in both orientations, and turning the phone never loses an edit. | Lightroom, Affinity Photo for iPad | Built | P1 |
| U-05 | One-hand reach | An option moves Undo, Redo and Compare to a bar just above the tools, within reach of one thumb. | with the option on, every frequent action is reachable by one thumb on a 6.7-inch phone. | Snapseed, VSCO | Planned | P2 |
| U-06 | Swipe to adjust | Swipe up or down on the photo to pick a setting and sideways to change it, as an extra to the sliders. | it can be turned off, never replaces the sliders, and each change is announced. | Snapseed | Planned | P3 |
| U-07 | Zoom to 100 per cent | Pinch or double-tap to zoom. At 100 per cent the visible area is drawn from the full-resolution photo. | sharpening and noise at 100 per cent match the saved copy. Today zoom enlarges the preview. | Lightroom, Capture One | Partial | P1 |
| U-08 | Light theme outside the editor | Home, Settings and the Guide can follow the phone's light or dark setting; the editor always stays dark. | both themes pass the contrast checks in A-06. | Google Photos | Planned | P2 |

### Performance targets

Targets, not measurements yet. They are checked before each release on a mid-range reference phone chosen by the owner.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| P-01 | Preview follows the finger | While a slider moves, the preview updates within 100 ms for 95 per cent of updates, at 1280 px on the long edge. | measured on the reference phone with the main tools in use, and reported in each release's notes. | Lightroom, Snapseed | Planned | P0 |
| P-02 | Fast first look | A 12-megapixel photo shows its first preview within 1.5 seconds of opening. | measured from tap to first preview over 20 photos on the reference phone. | Lightroom, Apple Photos | Planned | P1 |
| P-03 | Full-size save | A 24-megapixel photo saves at full size within 10 seconds, without running out of memory on a 4 GB phone. | 50 saves in a row finish on a 4 GB phone without a crash. | Lightroom, Snapseed | Planned | P1 |

### Privacy and security

Photos stay on the phone. Each promise is checked automatically on every build where it can be.

| ID | Feature | What it does | Done when | Seen in | Status | Priority |
|---|---|---|---|---|---|---|
| S-01 | On the device only | Pixels asks for no permissions at all, not even internet access. No account, no analytics, no adverts. | the build's security check fails if any permission is added. | Snapseed (offline editing) | Built | P0 |
| S-02 | Location off by default | Saved copies have their location removed unless you choose to keep it. | a saved copy carries no GPS data unless you kept it on purpose. | Apple Photos, Lightroom | Built | P0 |
| S-03 | No backups of personal data | Pixels opts out of Android cloud backup and device transfer, so edits and copies are not sent that way. | the security check fails the build if backup is switched back on. | Android data extraction rules | Built | P0 |
| S-04 | Safe imports | Shared photos are accepted only as content links from other apps, in supported types, up to 200 MB. | bad links, wrong types, oversized files and Pixels' own files are each refused with a message. | Android security guidance | Built | P0 |

### How each release is checked

Automated checks run on every build. The rest are done by hand, on the reference phone, before each release.

**On every build**

- UI tests check each control's name, role, value and state
- On every screen at 320 dp: spoken names, 48 dp touch targets, and no text cut off at 200 %
- A contrast test for every colour pair in both themes
- Android lint, including its accessibility checks
- Security check: no permissions, no backups, private file sharing

**Before each release**

- TalkBack walk-through of the five main tasks
- The same tasks by keyboard and with Switch Access
- Every screen at 200 per cent text and the largest display size
- Overlays in colour-blindness simulations and in greyscale
- Speed targets P-01 to P-03 measured

**The five main tasks**

1. Open a photo from the library or another app
2. Brighten it and bring back the sky with Light
3. Add a mask and change it
4. Undo a step and compare with the original
5. Save a copy without location

Standard: WCAG 2.2 level AA, applied with Android's accessibility guidelines. With high-contrast controls on (A-12), contrast reaches level AAA.

### Not planned, and why

Common in other editors and left out of Pixels on purpose. Each row says what Pixels offers instead.

| ID | Feature | Why it is left out | Instead | Seen in |
|---|---|---|---|---|
| N-01 | Generative fill, removal and expand | Every pixel in a saved photo must come from the photo itself. | Spot heal, Clone and content-aware removal copy real texture from the photo (R-01, R-02, R-05). | Photoshop, Lightroom, Google Photos |
| N-02 | AI masks, AI denoise and upscaling | Pixels uses no machine-learning models, so results are predictable and nothing is invented. | Brush, gradient, colour and luminance range masks, and classic noise reduction (M-01 to M-03, D-02). | Lightroom, Photoshop |
| N-03 | Face and body reshaping | It changes how a person looks, and can change who they appear to be. | Spot heal removes dust and blemishes without changing any shapes (R-01). | Photoshop, Facetune |
| N-04 | Cloud sync, accounts and social feeds | Pixels has no internet permission, so photos cannot leave the phone without you (S-01). | Save a copy, or share it to any app you choose (O-10). | Lightroom, VSCO |
| N-05 | Plug-ins and scripts | Pixels runs no downloaded or dynamic code, which keeps its security easy to check. | Presets, copy and paste settings, and batch apply (W-06 to W-08). | Photoshop, GIMP, darktable |

---

Product names are trademarks of their owners. "Seen in" shows where a capability is
established; no design, code or assets were copied.
