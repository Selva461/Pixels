# Architecture

## Modules

```
engine/domain  (pure Kotlin/JVM — no Android classes; unit-tested on the JVM)
  core/        errors (ErrorCode, OperationResult), logging, timing, ids, algorithm version
  analysis/    ImageAnalyzer (exposure, contrast, colour cast, noise, sharpness), SceneClassifier, Histogram,
               FaceLocator contract + FaceMetrics (platform supplies detection)
  planning/    EnhancementPlanner (decision layer), NaturalLimits, QualityPreset (per scene),
               ManualAdjustments + merger, Looks, ColorMixer, ToneCurves (monotone cubic)
  processing/  PipelineImageProcessor (+ tiling), ProcessingStage contract, stages/, ops/
  geometry/    Geometry, GeometryOps (rotate/flip/straighten/crop), CropMath
  local/       LocalAdjustments (radial/linear masks), LocalAdjustmentRenderer (runs after geometry)
  editing/     EditState (the nondestructive edit), EditHistory (undo/redo)
  project/     Project, ProjectCodec (versioned JSON), FileProjectStore, ProjectManager
  export/      ExportOptions (format, quality, size, metadata)
  validation/  OutputValidator (natural vs structural)
  usecase/     EnhanceImageUseCase — open → analyse → plan → render → validate → export
  debug/       DebugReport
engine/harness Desktop CLI running the real engine on image files

app/  (Android)
  data/decoder   AndroidImageRepository (bounds-first decode, subsampling, EXIF orientation),
                 AndroidFaceLocator (android.media.FaceDetector, on device)
  data/exif      ExifNormalizer, MetadataCopier
  data/storage   MediaStoreImageSaver (verified export), ShareCache, ThumbnailStore
  data/preferences SharedPreferencesSettingsRepository
  ui/            Compose screens; EditorViewModel coordinates only — no image maths
  PixelsApplication.AppContainer  manual composition root
```

Mapping to the requirement's logical modules: PhotoImporter/MetadataReader → `AndroidImageRepository`
+ `ExifNormalizer`; ImageAnalyzer → `StatisticalImageAnalyzer`; SceneClassifier → `SceneClassifier`;
AutoEnhancer → `NaturalEnhancementPlanner`; AdjustmentManager → `EditState` + `ManualAdjustmentMerger`;
ImageProcessor/PreviewRenderer → `PipelineImageProcessor` + `RenderTarget.PREVIEW`; HistoryManager →
`EditHistory`; ProjectManager → `ProjectManager`; ExportManager → `EnhanceImageUseCase.export`;
GallerySaver → `MediaStoreImageSaver`; ErrorHandler → `ErrorCode`/`ErrorMapper`/`ErrorMessages`;
PerformanceMonitor → `TimingReport` + structured logs. MaskManager and PermissionManager do not
exist yet (no masks; no runtime permissions are needed on API 29+).

## Data flow

1. Photo picker URI → `AndroidImageRepository.readSource` (type, size, orientation) → decode at
   working size (≤2560 px) with orientation applied.
2. `StatisticalImageAnalyzer` + `SceneClassifier` on the working image → `EnhancementSession`
   (also holds a ≤1280 px preview).
3. Each edit: `EditState` → `EnhanceRequest` → plan = planner(analysis, strength, scene preset) +
   manual offsets + colour mixer → pipeline on the preview → validation → geometry → screen.
4. Export: decode the source again at the size needed (≤24 MP) → same plan → pipeline in tiles →
   validation → geometry → resize → `MediaStoreImageSaver` (verified) → project records the export.
5. Every committed edit autosaves the project (edit + bounded history), never pixels.

## Processing order

Exposure → White balance (auto + temperature/tint) → Dehaze → Tone (contrast, highlights, shadows,
whites, blacks, midtones) → Noise reduction → Detail (clarity) → Sharpen → Colour finish (vibrance,
saturation) → Colour mixer → Vignette → Grain; then geometry.

Deviations from the requirement's suggested sequence, and why:
- **Geometry last, not first.** Validation compares the result with the source pixel-for-pixel, and
  tiles/analysis are defined on the unrotated source. Rotation/crop are exact pixel remaps, so
  applying them last gives the same result.
- **Colour (vibrance/HSL) after denoise/sharpen.** Those stages work on luma only and leave chroma
  differences intact, so colour adjustments commute with them; putting creative colour last keeps
  noise reduction working on the camera's colours.
- Exposure and white balance run in linear light; tone and colour operations on gamma-encoded values.

## Key rules

- Originals are never written. Exports are new MediaStore entries; projects store only references.
- Every automatic adjustment carries a reason (debug report, logs).
- Manual edits switch validation to structural checks only; automatic edits must also pass the
  naturalness checks (no new clipping, no colour explosion).
- Logs contain metadata only — never pixels, file names, URIs or GPS.
