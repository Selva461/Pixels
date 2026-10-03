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
- **Build constraint:** the development container cannot reach Google's Maven, so Android builds
  and device tests run only in GitHub Actions. As of 2026-10-03 the Actions runs are **blocked by a
  GitHub billing problem on the repository owner's account**; see HANDOFF.md.

## Requirement status

| Area | Status | Notes |
|---|---|---|
| Import, validation, EXIF orientation | ✅ | Photo picker; type/size/decode checks; orientation baked in before analysis |
| HEIC input | 🟡 | Rejected as unsupported today; decoder supports it on API 28+, needs allow-listing + test |
| Colour profile handling | 🟡 | sRGB assumed throughout; wide-gamut/Display P3 sources are converted by the decoder, not managed |
| Exposure, contrast, highlights, shadows | ✅ | Auto + manual |
| Whites, blacks, midtones | ✅ | Manual; tone-curve terms with anchored end points |
| Brightness, gamma, exposure compensation | ⬜ | Covered functionally by Exposure + Midtones; separate sliders not added |
| White balance: auto, temperature, tint | ✅ | Grey-edge auto WB; manual temperature/tint are relative (−100..100), not Kelvin |
| White balance eyedropper | ⬜ | |
| Warm-scene protection | ✅ | Dim warm scenes and Night/Food scenes correct casts less |
| Saturation, vibrance | ✅ | |
| HSL colour mixer (8 bands × H/S/L) | ✅ | |
| Per-channel RGB saturation, colour balance, global hue | ⬜ | |
| Tone curves | ⬜ | Histogram is shown; curve editor not built |
| Histogram | ✅ | Of the edited preview (Adjust tab) |
| Clarity, dehaze, sharpening, noise reduction | ✅ | Single NR control (luma+chroma); separate colour NR, texture, sharpening radius/masking ⬜ |
| Crop, rotate 90°, flip H, straighten, grid | ✅ | Presets Free/Original/1:1/4:5/3:2/16:9; 4:3 and 9:16 ⬜; flip vertical ⬜ (= rotate 180 + flip) |
| Lens distortion, CA, perspective | ⬜ | |
| Selective adjustments / masks | ⬜ | |
| Portrait-specific controls | 🟡 | Skin protection in vibrance/sharpening; Portrait scene limits; face detection ⬜ |
| Auto Enhance (analysis-driven, not a preset) | ✅ | Per-image plan with reasons |
| Scene-aware enhancement | ✅ | Heuristic classifier (11 scenes), user override, per-scene limits |
| Enhancement Strength 0–100 % with true zero | ✅ | "Original (no edits)" button = zero state |
| Before/after: divider, press-and-hold | ✅ | |
| Undo, redo, reset | ✅ | Bounded history, one step per finished drag |
| Projects: save, reopen, recover after restart | ✅ | Versioned JSON, atomic writes, autosave on every commit |
| Unsaved-changes prompt | ✅ | Prompts when the current edit was never exported |
| Export: JPEG/PNG, quality, size, full resolution | ✅ | Device-verified; tiled rendering; ~24 MP cap |
| Export: metadata keep / remove location / remove all | ✅ | Device-verified (REMOVE_LOCATION) |
| Export: verified write, cleanup, no false success | ✅ | Pending → verify → metadata → verify → publish → verify; device-verified |
| Export: cancel | ✅ | Partial file deleted |
| Export: colour profile choice, HEIC output | ⬜ | |
| Camera entry point, settings/help screens | ⬜ | |
| Visual regression with real photos | 🟡 | Synthetic golden scenes only; real-photo set not yet collected |
| Performance benchmarks on devices | ⬜ | Desktop timings only (see TESTING.md) |

## Phases

1. Audit and save defect — ✅
2. Core image engine — ✅
3. Auto Enhance (scene-aware, strength) — ✅
4. Advanced editing — 🟡 (HSL, histogram, dehaze, crop/straighten done; curves, masks, lens, portrait ⬜)
5. Export and reliability — ✅ (device-verified); lifecycle interruption testing ⬜
6. Quality and release — ⬜ (real-photo visual review and device benchmarks)

## Risks

- **No local Android build**: app-layer regressions are only caught in CI. Blocked CI means UI code
  added since the last green run is unverified.
- **Heuristic scene classifier**: tuned on synthetic images; expect misclassification on real
  photos. Mitigated by being advisory and overridable, with scores in the debug report.
- **Memory on full-resolution export**: bounded by tiling and a 24 MP cap; very low-memory devices
  may still hit `OUT_OF_MEMORY`, which is reported with a "try a smaller size" message.
- **Persistable URI access**: some providers refuse it; such projects cannot reopen after restart.
