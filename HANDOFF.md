# HANDOFF

_Last updated: 2026-10-09 (roadmap PR 1: accessibility must-haves) · algorithm version 1.1_

## Current status (2026-10-08)

- **All green in CI** for the app commit `f8a353a` (branch `claude/premium-editor-5`, run
  [37731711223](https://github.com/Selva461/Pixels/actions/runs/37731711223)): security gate, string
  check, engine build (warnings are errors, 222 tests), Android lint (errors fail), 5 app unit
  tests, debug APK, and **28 device tests including 10 Compose UI tests** on an API 30 emulator.
- The documentation commit goes on a new branch (pushes to existing branches are refused by the
  repository ruleset) and the pull request is opened from the final branch; it supersedes PR #2.
- Development containers without Google Maven can still compile-check the whole app:
  `tools/offline-typecheck/run.sh` (see its README). Use it before every push.
- Release state: debug APK only. No release signing, R8 or store listing yet (see AUDIT.md).

## Known issues

- **Target SDK 35.** Google Play raises the required target API every August; check before
  publishing whether API 36 is required, then test on Android 16. Lint reports it as a warning.
- Not yet checked on real phones: performance on a mid-range device, TalkBack, 200 % font size,
  OEM camera and gallery apps (camera capture, sharing), HEIC input. Checklist in TESTING.md.
- Lint warnings are not triaged (errors fail CI; warnings are in the `build-reports` artifact).
- Face detection uses the legacy `android.media.FaceDetector` (upright faces, eyes visible); it only
  places a soft exposure region. Scene classifier and thresholds are tuned on synthetic images.
- Not implemented: HEIC and colour-profile export. AI masks and
  generative tools are excluded by the owner's choice ("no AI").
- Supply chain: actions use major-version tags (not commit SHAs); no Gradle dependency verification.
  Recommended owner actions are in SECURITY.md.

## Next single action

Owner: review and merge PR #3 (it supersedes #1 and #2), then the roadmap PR, then install the
debug APK from the CI run on a phone and work through the manual checklist in TESTING.md
(especially camera, sharing from a gallery app, landscape and TalkBack).

Development: work through [ROADMAP.md](ROADMAP.md) in order. PR 1 (accessibility must-haves) is
in review; PR 2 (speed baseline) is next. Each roadmap PR stacks on the previous one until the
stack is merged.

## What changed

### 2026-10-09 — roadmap PR 1: accessibility must-haves

FEATURE_SPEC A-04, A-05, A-06 and U-03 built; A-01 names/roles/values/states done (TalkBack
walk-through on a phone still to do).

- `ui/components/Choices.kt`: `ChoiceChip` (selected = inverted + tick, unselected = 3:1 outline,
  optional "edited" dot that is spoken) replaces every `FilterChip`; `StateIconToggle` for on/off
  icon buttons (compare, mask overlay, invert).
- `Theme.kt`: `pixelsColorScheme()` for tests, `PhotoLabelBacking` (near-opaque) for text over the
  photo, `CurveInk` channel colours. `ProSlider` rail uses `outline`; its texts merge into one
  focus stop.
- Editor: adaptive top bar (Share → Compare → Redo move into More when Save would not fit; the
  Save label is measured), tool labels grow with the text, `initialTool` parameter (tests),
  settings-group rows are 48 dp toggleables, named photo areas, crop frame and mask/heal canvases.
- Panels: curve square limited to half the width; preset tiles grow and tick the applied preset;
  mixer swatches 48 dp radio buttons; crop buttons share the row and wrap; history marks the
  current step bold and undone steps italic and "Undone"; colour wheel strings translated.
- Home is one `LazyColumn` (links under the start buttons); Settings and Export sliders have
  names; the watermark field has a real label.
- Tests: `ContrastTest` (JVM, both schemes, photo labels, curve lines) and `AccessibilityTest`
  (device; names, 48 dp targets and 200 % text on every screen, editor tool, sub-view and dialog
  at 320 × 560 dp).

### 2026-10-09 — feature specification and roadmap

- `FEATURE_SPEC.md`: 137 features from established editors (Lightroom, Snapseed, Capture One,
  Photoshop, darktable and others), each with what it does, a "Done when" acceptance test, where
  it is seen, its status in Pixels and a priority (63 built, 11 partial, 63 planned), plus 5 left
  out on purpose. Generated from the design canvas that also holds five screen designs.
- `ROADMAP.md`: the 74 open features split into 22 pull requests, ordered by priority and
  dependency: PRs 1–2 finish the release 1.0 must-haves (accessibility checks, speed baseline).

### 2026-10-08 — strict pass: features, QA, security, audit, documentation

The owner asked for "more and more features" with very strict development, QA, security, UI,
audit and documentation, and an APK. No AI, as before.

- **Engine** (`0102952`): `CalibrationStage` (primaries hue/saturation, shadows tint; column-scaled
  matrix keeps white neutral), `DefringeStage` (purple/green fringes next to edges), white-balance
  As shot, curve presets, WebP, `Border`/`BorderOps`, watermark settings and the `ExportDecorator`
  hook, `BatchExportUseCase` (≤50 photos), named history (`EditDiff`, `EditHistory.jumpTo`),
  project duplicate/rename, mask duplicate/rename, `PresetMath.apply` blending, `EditState.toRequest()`
  as the single edit → request mapping, signed-zero fix in geometry, export-options codec,
  safe output names. Compiler warnings are errors; seeded fuzz tests (`EditFuzzTest`).
- **App** (`f8a353a`): Settings screen, camera capture (`TakePicture` into `files/captures`, no
  permission), Share/Edit with Pixels (`IncomingImages`: content URIs only, own provider refused,
  MIME and 200 MB checks, copied into `files/imports`), Apply to other photos, History panel,
  panel Reset buttons (`PanelControls` is the single list of which slider is in which panel),
  calibration UI, WB Auto/As shot/Pick, curve presets, RGB histogram, mask duplicate/rename,
  recent-edit rename/duplicate/remove, landscape layouts, haptics, export WebP/border/watermark
  (`AndroidWatermarkDecorator`), `AppStorage` for the storage figure.
- **Fixed in review** (details and guards in AUDIT.md): watermark leaking into the open photo,
  crash on wrongly typed settings, share-import crashes, background storage crashes, preset
  Amount 0 %, signed zero, grading not saved, calibration saturation, edits not rendered,
  stale tap-to-pick, disabled-slider accessibility, Home clipped in landscape, plurals.
- **QA:** app unit tests (`PanelControlsTest`), device tests (`SaveFlowTest` WebP/border/
  watermark, `WatermarkDecoratorTest`, `IncomingImagesTest`, `SettingsRepositoryTest`), Compose UI
  tests (`EditorScreenTest`, `HomeAndSettingsTest`) using a recording `EditorActions` proxy,
  Android lint gate, `scripts/check_resources.py`, `tools/offline-typecheck/`.
- **Security:** `scripts/security_gate.py` (permissions, exported components, FileProvider paths,
  backup rules, risky APIs, logging, secrets, workflow permissions), data-extraction rules, decoder
  accepts content URIs only, read-only CI token without persisted credentials. SECURITY.md has the
  threat model.
- **Docs:** AUDIT.md, SECURITY.md, CHANGELOG.md, CONTRIBUTING.md (no outside contributions),
  USER_GUIDE.md, REQUIREMENTS.md § 16 (new requirement IDs with evidence); README, ARCHITECTURE,
  IMPLEMENTATION_PLAN and TESTING updated.

### 2026-10-07 — extra tools and guides

White-balance picker, Auto straighten / Auto upright (`geometry/AutoGeometry.kt`: Sobel edge
angles; level = histogram peak, upright = 1-D searches on a 256 px copy), red-eye retouch mode,
global Hue slider, clipping warnings, and an offline in-app Guide (Home > Guide). A user guide
was also written as a shareable doc.

### 2026-10-07 — pro editor (Premium-level tools, no AI)

The user asked for the features of a paid pro mobile editor and a similar UI, keeping the engine.
Nothing was copied from another app: tools were implemented from scratch and the UI uses the
common pro-editor layout (photo, panel, tool strip) with Pixels' own palette and icons from
Material Symbols. The user chose "no AI", so subject/sky/people masks, AI denoise and generative
remove are not included.

- Engine: `geometry/Optics.kt` + `OpticsWarp` (lens and perspective in one auto-fitted warp),
  brush/range masks (`local/MaskRaster.kt`), `retouch/` (heal/clone + automatic source),
  `ColorGradingStage` + B&W treatment, sharpen radius/detail, vignette midpoint/feather/roundness,
  grain size/roughness, `presets/` (library, amount, user presets, copy/paste), project versions.
  Project files stay schema 1 (all new fields optional).
- App: dark workspace, `EditorTool` strip, `ProSlider`, panels in `ui/panels/`, `ui/local/Masking.kt`
  (mask canvas + panel), `ui/retouch/Healing.kt`, `EditorActions` implemented by the view model.
- Ruleset note: updates to existing branches are refused ("changes must be made through a pull
  request"); each push needs a new branch until the ruleset is narrowed to the default branch.

### 2026-10-07 — remaining editing requirements

- Controls: exposure ±3 EV, brightness, gamma, exposure compensation, midtones, texture, colour
  noise reduction and sharpening masking ("More controls" reveals the advanced ones).
- Geometry: flip vertical, 4:3 and 9:16 crops. HEIC/HEIF accepted on import.
- Tone curves (`planning/ToneCurves.kt`, `CurvesStage`, `ui/adjust/CurvePanel.kt`): master and
  R/G/B, monotone cubic so curves never overshoot; stored in projects.
- Local adjustments (`domain/local/`, `ui/local/LocalPanel.kt`): radial and linear masks with
  exposure, contrast, saturation, temperature, feather and invert, drawn on the photo with drag
  handles; rendered after geometry; stored in projects.
- Face exposure (`analysis/Faces.kt`, `FaceExposureStage`, `AndroidFaceLocator`): faces found on
  device lift toward a natural target (max +0.9 EV auto); backlit faces are not darkened by the
  global exposure cut. Manual "Face exposure" slider in the Portrait group.

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
