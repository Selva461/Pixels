# Implementation Plan

Tracks `REQUIREMENTS.md` against the code. Status: ✅ done · 🟡 partial · ⬜ not started.
"Device-verified" means exercised by the instrumented tests on the CI emulator; everything else
in `app/` is compiled in CI but has not been run on a phone by the developer.

## Audit findings (Phase 1)

- **Stack:** Kotlin. `engine/` is a pure-JVM Gradle build (analysis, planning, processing, geometry,
  projects, export orchestration) consumed by `app/` (Android, Jetpack Compose, minSdk 29) as an
  included build. No third-party image library: all processing is deterministic Kotlin.
- **Save defect (root cause, fixed 2026-10-02):** `MediaStoreImageSaver.verifyDecodable` treated
  the always-null result of a bounds-only `BitmapFactory.decodeStream` as "cannot open", so every
  save was reported as failed and the new file deleted. Reproduced and verified by `SaveFlowTest`
  on the CI emulator. A second, UI-level cause (Save button pushed off-screen) was fixed earlier.
- **Build constraint:** the development container cannot reach Google's Maven, so the real Android
  build, lint and device tests run in GitHub Actions on every push. Since 2026-10-08 the whole app
  (main code, unit tests, device tests) is also compile-checked offline with
  `tools/offline-typecheck/` before pushing.

## Requirement status

| Area | Status | Notes |
|---|---|---|
| Import, validation, EXIF orientation | ✅ | Photo picker; type/size/decode checks; orientation baked in before analysis |
| HEIC input | ✅ | image/heic and image/heif allow-listed (platform decoder, minSdk 29); device test ⬜ |
| Colour profile handling | 🟡 | sRGB assumed throughout; wide-gamut/Display P3 sources are converted by the decoder, not managed |
| Exposure, contrast, highlights, shadows | ✅ | Auto + manual |
| Whites, blacks, midtones | ✅ | Manual; tone-curve terms with anchored end points |
| Brightness, gamma, exposure compensation | ✅ | Exposure ±3 EV; brightness (mid-weighted), gamma and compensation under "More controls" |
| White balance: auto, temperature, tint | ✅ | Grey-edge auto WB; **As shot** mode skips the automatic correction; manual temperature/tint are relative (−100..100), not Kelvin |
| White balance eyedropper | ✅ | Colour > Pick white balance: tap a neutral spot; temperature/tint set analytically from the sample |
| Warm-scene protection | ✅ | Dim warm scenes and Night/Food scenes correct casts less |
| Saturation, vibrance | ✅ | |
| HSL colour mixer (8 bands × H/S/L) | ✅ | |
| Individual red, green and blue saturation; colour balance | ✅ | Calibration: red/green/blue primary hue and saturation plus shadows tint; colour grading wheels (shadows/midtones/highlights/global, blending, balance) for colour balance; black-and-white treatment with mixer-based B&W mix |
| Global hue −180°..+180° | 🟡 | Hue slider is ±30° on purpose (larger shifts are not natural corrections); the mixer shifts individual colours |
| Tone curves | ✅ | Master + R/G/B monotone-cubic curves over the histogram (tap add, drag, double-tap remove); presets (Linear, Medium/Strong contrast, Lift shadows, Faded) |
| Histogram | ✅ | Of the edited preview, luminance or RGB (Light panel) |
| Clarity, dehaze, sharpening, noise reduction | ✅ | Plus texture, colour NR, sharpening radius/detail/masking |
| Crop, rotate 90°, flip H/V, straighten, grid | ✅ | Presets Free/Original/1:1/4:5/3:2/4:3/16:9/9:16 |
| Lens distortion, CA, perspective | ✅ | Manual distortion, colour fringing, lens vignetting, Defringe (purple/green edge fringes); Upright-style vertical/horizontal/rotate/aspect/scale/offset, auto-fitted (no empty edges). Auto straighten (Crop) and Auto upright (Geometry) from the photo's straight edges ✅ |
| Selective adjustments / masks | ✅ | Up to 12 masks: brush (paint/erase, size, feather, flow), linear, radial, luminance range, colour range (tap to pick); invert; overlay; 9 local sliders. AI subject/sky masks deliberately not included (user chose no AI) |
| Portrait-specific controls | ✅ | On-device face detection (android.media.FaceDetector) drives auto face exposure incl. backlit faces; manual Face exposure slider; red-eye tool |
| Auto Enhance (analysis-driven, not a preset) | ✅ | Per-image plan with reasons |
| Scene-aware enhancement | ✅ | Heuristic classifier (11 scenes), user override, per-scene limits |
| Enhancement Strength 0–100 % with true zero | ✅ | "Original (no edits)" button = zero state |
| Before/after: divider, press-and-hold | ✅ | |
| Undo, redo, reset | ✅ | Bounded history, one step per finished drag; History panel names every step and jumps to any of them; Reset per panel |
| Projects: save, reopen, recover after restart | ✅ | Versioned JSON, atomic writes, autosave on every commit |
| Unsaved-changes prompt | ✅ | Prompts when the current edit was never exported |
| Export: JPEG/PNG, quality, size, full resolution | ✅ | Device-verified; tiled rendering; ~24 MP cap |
| Export: metadata keep / remove location / remove all | ✅ | Device-verified (REMOVE_LOCATION) |
| Export: verified write, cleanup, no false success | ✅ | Pending → verify → metadata → verify → publish → verify; device-verified |
| Export: cancel | ✅ | Partial file deleted |
| Export: WebP, border, watermark | ✅ | Device-verified (WebP type/size, border size/colour, watermark never on the open photo) |
| Export: colour profile choice, HEIC output | ⬜ | |
| Batch editing | ✅ | Apply chosen settings to up to 50 photos; each saved as a new file and listed in Recent |
| Retouching (spot heal / clone / red eye) | ✅ | Tap to heal with automatic source choice; drag target and source; heal, clone or red-eye; size, feather, opacity. Real pixels only |
| Clipping warnings, in-app guide | ✅ | Light > Show clipping (red highlights, blue shadows on the preview); Home > Guide explains every tool offline |
| Presets, copy/paste, versions | ✅ | 29 built-in presets in 8 categories with amount 0–200 %; user presets saved on device; copy/paste settings by group; named versions inside projects |
| Camera entry point, settings/help screens | ✅ | Take a photo (camera app, no permission); Share/Edit with Pixels from other apps; Settings; in-app Guide; About with privacy policy, terms, licences |
| UI tests | ✅ | 10 Compose UI tests on the CI emulator (tools, sliders, top bar, panel reset, history, batch, home, settings) |
| Security gates | ✅ | `scripts/security_gate.py` in CI; threat model in SECURITY.md |
| Visual regression with real photos | 🟡 | Synthetic golden scenes only; real-photo set not yet collected |
| Performance benchmarks on devices | ⬜ | Desktop timings only (see TESTING.md) |

## Phases

1. Audit and save defect — ✅
2. Core image engine — ✅
3. Auto Enhance (scene-aware, strength) — ✅
4. Advanced editing — ✅ (curves, masks incl. brush and ranges, healing, lens and perspective, grading, calibration, defringe, presets, versions, named history)
5. Export and reliability — ✅ (device-verified, incl. WebP, border, watermark, batch); lifecycle interruption testing ⬜
6. Quality and release — 🟡 CI gates done (lint, unit, device and UI tests, security gate, audit); real-photo visual review, device benchmarks, release signing and store target API ⬜

## Risks

- **No local Android build**: mitigated by the offline type-check (`tools/offline-typecheck/`) and
  by CI, which builds, lints and runs device and UI tests on every push.
- **Heuristic scene classifier**: tuned on synthetic images; expect misclassification on real
  photos. Mitigated by being advisory and overridable, with scores in the debug report.
- **Memory on full-resolution export**: bounded by tiling and a 24 MP cap; very low-memory devices
  may still hit `OUT_OF_MEMORY`, which is reported with a "try a smaller size" message.
- **Persistable URI access**: some providers refuse it; such projects cannot reopen after restart.
