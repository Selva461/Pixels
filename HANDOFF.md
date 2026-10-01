# HANDOFF

_Last updated: 2026-10-01 · algorithm version 1.0_

## What changed

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

## What works (verified)

- `./gradlew -p engine build` — engine compiles with no warnings, **89 tests pass** (unit tests for
  analysis, planning cases A–F, strength scaling, stage order, every stage, validation, error mapping,
  use-case flow and failure paths, plus 9 golden scenarios).
- Visual check through the harness on synthetic scenes: underexposed, overexposed, colour cast and
  hazy outputs look like natural corrections; the clean scene is left untouched.
- Desktop JVM timing at 2560×1920 (noisy low-light scene): decode 202, analyse 219, tone 141,
  denoise 964, detail 270, sharpen 384, validation 58 ms — about 2.3 s total.

## What was NOT verified

- **The Android module has never been compiled.** The build container blocks `dl.google.com`
  (Android SDK, AGP and AndroidX artifacts), so `app/` was written by hand and reviewed, but not built
  or run. Expect small compile fixes on first open in Android Studio. Versions: AGP 8.10.1,
  Kotlin 2.1.21, Compose BOM 2025.05.00, compileSdk 35, minSdk 29.
- No real photos have been processed. All tuning used synthetic scenes (`GoldenScenario`).

## Known issues / limitations

- Processing and export happen at the working resolution (long edge 2560 px), not full sensor
  resolution. Saving full resolution needs tiled processing.
- Denoise allocates ~6 float planes (~120 MB at 2560×1920). `largeHeap` is enabled and OOM maps to a
  controlled `OUT_OF_MEMORY` error, but low-memory devices may hit it.
- Every slider release re-runs the full pipeline at working resolution (likely 2–5 s on a phone).
  A low-resolution preview pass would make the slider feel live.
- Chroma denoise is a plain blur and can bleed colour across strong colour edges.
- White balance uses grey-edge on low-chroma pixels. On very colourful scenes it is less reliable
  (the planner halves the correction there). It has only been tuned on synthetic scenes.
- Sharpening on soft images is deliberately mild (radius 1, overshoot-clamped).
- No JPEG-artifact (deblocking) stage yet; the `09_compressed` golden scenario is not implemented.
- EXIF metadata (date, camera) is not copied to the saved file; only orientation is applied to pixels.
- The harness uses ImageIO, which ignores EXIF orientation.
- `ProcessingStage.execute` takes `PixelBuffer` rather than the abstract `ImageBuffer`, so a
  GPU-backed buffer would need its own stage implementations.
- `ImageAnalyzer.analyze` takes the decoded `PixelBuffer` rather than `ImageSource` (deliberate
  deviation from the spec sketch: decoding belongs to the repository, not the analyzer).

## Next single action

Open the project in Android Studio (or run `./gradlew :app:assembleDebug` with the SDK installed),
fix any compile errors in `app/`, then run the app on a device with 5–10 real photos and compare
the debug report's analysis scores against the synthetic calibration in `AnalysisThresholds` /
`NaturalLimits`.
