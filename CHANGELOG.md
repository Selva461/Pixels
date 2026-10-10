# Changelog

All notable changes to Pixels. Dates are when the work landed on a branch; nothing has been
published to a store yet. Detailed engineering notes for each entry are in HANDOFF.md.

## [Unreleased] — 2026-10-10

### Smart edit
- **Smart edit** (Auto panel, and automatically for new photos): finds the subject, the sky and the
  background with plain image measurements (focus, colour, texture, position; no AI, nothing
  generated) and edits each part on its own. The subject is brightened and sharpened, the sky's
  highlights, clouds and colour are brought back, the background is calmed so the subject stands
  out. Everything lands as three normal masks in Masking whose sliders you can change.
- **Subject, Sky and Background masks** can also be added by hand in Masking; they are found
  again on each photo, so pasted settings and batches adapt to every picture.
- **People found by an on-device model**: a bundled DeepLab v3 model (2.7 MB, TensorFlow Lite,
  offline) finds people, so "focus on me" works on the person, not the brightest object. It only
  marks where people are; edits are still ordinary sliders. Without a person the rules decide.
- Setting: **Smart edit new photos** (on by default).
- Harness: `--smart` writes before | Auto | Smart edit and a picture of the found regions.

### Added
- **Settings** (Home > Settings): Auto strength for new photos, haptic feedback, ask before
  leaving an unsaved edit, export defaults, storage used by Pixels, clear temporary files, delete
  your presets, delete all edits (each destructive action asks first).
- **Take a photo** from Home (the camera app writes straight into Pixels' private storage; no
  camera permission needed).
- **Share to Pixels / Edit with Pixels** from other apps; the photo is checked and copied in so
  the edit can be reopened later.
- **Apply to other photos**: copy chosen settings to up to 50 photos and save each as a new file,
  with progress, Cancel and a summary.
- **History panel**: every change listed by name (for example "Exposure, Contrast +2"); tap a step
  to go back to it. Named versions moved into the same panel.
- **Calibration** (Colour > Calibration): red, green and blue primaries hue and saturation, and
  shadows tint.
- **Defringe** (Optics): removes purple and green fringes along high-contrast edges.
- **White balance modes**: Auto, As shot (no automatic correction) and Pick.
- **Export**: WebP format, white or black borders (thin, medium, thick), text watermark with
  position, size and opacity. Sized exports include the border in the requested size.
- Curve presets (Linear, Medium and Strong contrast, Lift shadows, Faded); RGB histogram; Reset
  button on every adjustment panel; duplicate and rename masks; rename, duplicate and remove
  recent edits; 5:4, 21:9, 3:4 and 2:3 crops; landscape layouts for Home and the editor; light
  haptic tick when a slider lands on zero.

### Fixed
- A watermarked export could leave the watermark on the photo still open in the editor.
- A damaged settings file could stop the app from starting.
- Preset Amount at 0 % lost your own light and colour changes.
- Rotating or flipping could create duplicate history steps and project files that did not reload
  identically.
- Colour-grading Blending and Balance were not saved when no wheel was moved.
- Calibration saturation had almost no effect.
- Some edits (calibration, white-balance mode) did not reach the preview.
- Sharing a photo to Pixels could crash on unusual sources; file errors in background saves
  could crash the app.
- "1 photos" / "1 spots" wording; WebP quality was labelled "JPEG quality"; Home was clipped in
  landscape.

### App icon
- Pixels has its own icon instead of the system default: a mountain drawn in pixel steps under a
  burnt-orange sun, on ink. It adapts to every launcher shape, has a one-colour version for themed
  icons (Android 13+), and also appears next to the title on Home. Source and the 512 px store
  icon are in `docs/brand/`.

### Accessibility (roadmap PR 1)
- Works with TalkBack: every control has a name; sliders, the before/after divider and the colour
  wheel read their value; tools, curve channels and colour bands with changes are read as
  "edited"; screen titles and sections are headings.
- Text up to 200 per cent: the editor's top bar moves Share, Compare and Redo into More when Save
  would be squeezed; tool labels, preset tiles, recent edit names and panels grow, wrap or scroll
  instead of cutting text off; Home is one scrolling list.
- Every control is at least 48 × 48 dp (colour-mixer swatches, the split-view knob, settings
  groups and heal spots were smaller).
- Contrast: slider rails, curve lines and labels over the photo meet WCAG 2.2; selected chips are
  filled with a tick and on/off buttons are filled when on, so no choice relies on colour alone;
  mask handles and heal circles have a dark edge so they show over any photo.

### Documentation
- [FEATURE_SPEC.md](FEATURE_SPEC.md): 137 features drawn from established editors, each with an
  acceptance test, status and priority, plus 5 left out on purpose with the reason.
- [ROADMAP.md](ROADMAP.md): the 74 open features split into 22 pull requests, in order.

### Security and quality
- No permissions at all; only `content://` images are accepted from other apps, never Pixels' own
  files; imports capped at 200 MB; backups and device transfer exclude all app data.
- CI: security gate, string-resource check, Android lint (errors fail), app unit tests, Compose UI
  tests and new device tests; read-only workflow token. See AUDIT.md and SECURITY.md.

## 2026-10-07 — Pro editor (PR #2)
- Rebuilt the editor as a pro workspace (photo, panel, tool strip) with presets and Amount, Light,
  Colour (mixer, grading, B&W), Effects, Detail, Optics, Geometry, Masking (brush, linear, radial,
  luminance and colour range), Healing (heal, clone, red eye), copy and paste settings, versions.
- White-balance picker, Auto straighten, Auto upright, global Hue, clipping warnings, in-app Guide.

## 2026-10-03 — Export, projects, more controls
- Export options (JPEG/PNG, quality, size, metadata), verified saving, cancel.
- Projects with autosave, Recent edits, undo/redo across restarts.
- Whites, Blacks, Midtones, Dehaze, colour mixer, histogram, scene-aware Auto Enhance.
- Design pass: own palette and type, skeleton loaders, About with privacy policy and terms.

## 2026-10-02 — Crop and manual controls
- Crop, rotate, flip, straighten with aspect presets.
- Manual sliders, Looks, live preview; fixed saving (every save was reported as failed).

## 2026-10-01 — First version
- Natural Auto Enhance (exposure, white balance, tone, noise, detail) with a reason for every
  adjustment, before/after comparison, save and share, developer debug screen.
