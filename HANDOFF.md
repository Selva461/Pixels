# HANDOFF

_Last updated: 2026-10-03 · algorithm version 1.1_

## Current status (2026-10-03)

- Engine: `./gradlew -p engine build` — 149 tests pass, no warnings.
- Last green CI (APK + 3 device tests): commit 91a359a (crop/rotate).
- **CI has been blocked since commit 39fe15c** by a GitHub billing error on the repository owner's
  account ("recent account payments have failed or your spending limit needs to be increased").
  Therefore everything in `app/` added since 91a359a — export dialog, projects/Home, redo,
  press-and-hold, leave prompt, grouped Adjust panel, Color tab, scene chips, and the 3 new device
  tests — has **not been compiled or run**. Expect possible compile fixes on the next CI run.

## Known issues

- See IMPLEMENTATION_PLAN.md for unimplemented requirements (curves, masks, lens/perspective,
  portrait face tools, eyedropper, HEIC, colour profiles, Kelvin temperature, settings/help).
- Scene classifier and all thresholds are tuned on synthetic images only.
- Preview renders on every slider move at 1280 px; not yet benchmarked on a phone.

## Next single action

Restore GitHub Actions (fix billing or make the repository public), let CI build commit HEAD, fix
any compile errors in `app/`, and confirm the 6 device tests pass. Then review real photos with the
debug report to calibrate the scene classifier.

## What changed

### 2026-10-03 — design pass against a "looks auto-generated" checklist

The user supplied a list of 30 visual anti-patterns. Applied to the app: own palette (warm paper /
ink neutrals, one muted burnt-orange accent; no Material purple, no wallpaper dynamic colour, no
pure white or pure black), 2–12 dp corners and square-ish buttons, serif wordmark/headings, neutral
grey photo canvas, single-tone histogram, copy without "not X, Y" phrasing or em dashes, skeleton
loaders (editor layout while opening, Recent list rows), and an About screen with a privacy policy,
terms of use and open-source credits (`ui/about`). Not applicable to a native app: pricing tiers,
feature cards, bento grids, hover effects. Not done: a real product demo (needs real photos the
owner has rights to). The privacy text is accurate to the code (no INTERNET permission, no
analytics); have it reviewed before any store listing.

### 2026-10-03 — new requirements (REQUIREMENTS.md): export, projects, controls, scenes

- **Export** (`EnhanceImageUseCase.export`, `ExportOptions`): JPEG/PNG, quality, Full/Large/Medium/
  Small, metadata keep/remove-location/remove-all. Full size re-decodes the source (≤24 MP) and
  renders in 1024 px tiles with stage-declared overlap. `MediaStoreImageSaver` now writes hidden,
  verifies, copies EXIF, verifies, publishes and verifies the published entry; deletes on any
  failure or cancellation. New `INSUFFICIENT_STORAGE` / `PERMISSION_DENIED` errors. Export dialog
  shows exact output dimensions; progress with Cancel.
- **Projects** (`editing/`, `project/`): `EditState`, bounded `EditHistory`, versioned JSON
  `ProjectCodec` (kotlinx.serialization, migration hook), atomic `FileProjectStore`,
  `ProjectManager`. App autosaves every commit, lists Recent edits with thumbnails, resumes with
  history, persists the picker grant, prompts before leaving un-exported edits. Redo,
  "Original (no edits)", press-and-hold original.
- **Controls:** Whites, Blacks, Midtones (tone curve), Dehaze stage, HSL colour mixer stage
  (8 bands), histogram; Adjust tab grouped with descriptions; new Color tab.
- **Scene-aware Auto Enhance:** `SceneClassifier` (11 scenes, advisory, overridable, persisted),
  per-scene `QualityPreset.forScene` limits, warm-dim-scene WB protection, and a planner guard that
  shrinks contrast boosts that would clip the 5th/95th percentiles (found by a failing test).
- Docs: REQUIREMENTS.md, IMPLEMENTATION_PLAN.md (status matrix), ARCHITECTURE.md, TESTING.md.

### 2026-10-02 — crop and rotate

- `domain/geometry`: `Geometry` (quarter turns, flip, straighten ±45°, normalised `CropRect`),
  `GeometryOps` (pixel transforms; straighten auto-crops to the largest same-aspect rectangle so no
  empty corners appear) and `CropMath` (move / corner-resize with optional aspect lock).
- Geometry runs after processing and validation; `EnhancementOutcome.output` and `originalView`
  carry it so before/after stay aligned. Save and share use `output`.
- App: Crop tab (rotate left/right, flip, straighten slider, aspect presets Free/Original/1:1/4:5/
  3:2/16:9) and `CropEditor` overlay (drag inside to move, corners to resize, rule-of-thirds guides).
  While on the tab the preview is rendered uncropped; the crop applies when leaving it. Geometry is
  part of `EditState`, so undo covers it.
- Instrumented test saves a rotated + cropped edit and checks the saved dimensions.

### 2026-10-02 — manual controls, looks, save fix

- **Actual save bug:** `MediaStoreImageSaver.verifyDecodable` treated the (always null) result of a
  bounds-only decode as "cannot open", so every save was reported as failed and deleted. Found by
  `SaveFlowTest` on the CI emulator; fixed.

- **Save fix (likely cause):** the editor's bottom row held 5 buttons and overflowed on normal phone
  widths, squeezing *Save* off-screen. Save/Share/Undo now live in the top bar; a snackbar confirms
  "Saved to Pictures/Pixels" with a **View** action. Save renders the full working resolution
  (never the preview). `SaveFlowTest` (instrumented) checks the real MediaStore path in CI on an
  API 30 emulator.
- **Manual controls** (`ManualControl`): Exposure, Contrast, Highlights, Shadows, Temperature, Tint,
  Vibrance, Saturation, Clarity, Sharpness, Noise reduction, Vignette, Grain. They are offsets on
  top of the automatic plan (`ManualAdjustmentMerger`), recorded in each adjustment's reason and
  clamped to per-control ranges. New stages: `VignetteStage`, `GrainStage`; white balance now takes
  temperature/tint, colour finish takes global saturation.
- **Looks** (`Look.ALL`): None, Vivid, Warm, Cool, Soft, Matte, Mono, Film, Drama — preset slider bundles.
- **Live preview:** edits render a 1280 px preview (`RenderTarget.PREVIEW`), debounced and cancelling
  stale renders. Plan is always decided from the full-resolution analysis.
- **Validation modes:** manual edits use `ValidationMode.STRUCTURAL` (only broken output fails), so
  deliberate creative looks are not rejected; automatic-only edits keep the full natural checks.
- Undo is now a 30-step history of `EditState` (strength + manual sliders + look).

### 2026-10-01 — initial MVP

Initial implementation of the MVP from `Natural_Image_Enhancer_Requirements.md`.

- **Engine (`engine/domain`, pure Kotlin/JVM)**
  - `StatisticalImageAnalyzer` + one estimator per metric (`QualityMetrics.kt`): luminance histogram,
    clipping, tonal spread, chroma, grey-edge colour cast, Immerkær noise sigma (flat regions only),
    Crete no-reference blur metric.
  - `NaturalEnhancementPlanner`: the decision layer. One function per adjustment, all thresholds in
    `NaturalLimits`, every adjustment carries a reason. `PlanScaler` applies user strength
    (0.5 = nominal plan, 1.0 = 2×) then clamps to hard natural ranges.
  - `PipelineImageProcessor` runs `DefaultPipeline`: Exposure → White Balance → Tone → Denoise →
    Detail → Sharpen → Color Finish. Supports per-stage disable/intensity (`StageConfig`), "run until
    stage", listener callbacks and per-stage timing. Makes exactly one working copy; stages edit in place.
  - `NaturalOutputValidator`: dimensions, alpha, not blank, new clipping, chroma explosion, brightness shift.
  - `EnhanceImageUseCase`: open → analyse → plan → process → validate → save, all returning
    `OperationResult`, with structured logs (`PROCESS_START`, `PLAN_CREATED`, `STAGE_COMPLETE`, …)
    keyed by `processingId` (`IMG-20261001-8F31`). Never logs file names, URIs or pixels.
  - `DebugReport`: Input / Analysis / Plan (with reasons) / Pipeline / Stage Timings / Validation.
- **Harness (`engine/harness`)**: desktop CLI running the real use case on image files or the synthetic set.
- **App (`app`)**: photo picker, `AndroidImageRepository` (bounds-first decode, power-of-two
  subsampling, EXIF orientation via `ExifNormalizer`), `MediaStoreImageSaver` (new entry under
  Pictures/Pixels, `IS_PENDING`, re-decode check, deletes partial files), `ShareCache` + FileProvider,
  SharedPreferences settings, `EditorViewModel` with a single `EditorUiState`, Compose screens:
  Home, Editor (before/after with drag divider, horizontal/vertical split, pinch-zoom/pan,
  double-tap zoom, strength slider, undo, reset, save, share), Debug (developer builds only:
  report, stage toggles, stop-after-stage, export report).
