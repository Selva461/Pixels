# Architecture

## Modules

```
engine/domain  (pure Kotlin/JVM — no Android classes; unit-tested on the JVM)
  core/        errors (ErrorCode, OperationResult), logging, timing, ids, algorithm version
  analysis/    ImageAnalyzer (exposure, contrast, colour cast, noise, sharpness), SceneClassifier, Histogram,
               FaceLocator contract + FaceMetrics (platform supplies detection)
  planning/    EnhancementPlanner (decision layer), NaturalLimits, QualityPreset (per scene),
               ManualAdjustments + merger, Looks, ColorMixer, ToneCurves (monotone cubic) + CurvePreset,
               ColorGrading, Calibration
  processing/  PipelineImageProcessor (+ tiling), ProcessingStage contract, stages/, ops/
  geometry/    Geometry, GeometryOps (rotate/flip/optics/straighten/crop), OpticsWarp (lens + perspective), CropMath
  local/       LocalAdjustments (brush, linear, radial, luminance/colour range), MaskRaster, LocalAdjustmentRenderer
  retouch/     Retouch spots (heal/clone), RetouchRenderer, RetouchSourceFinder
  presets/     PresetLibrary, PresetMath (amount, copy/paste), FilePresetStore
  editing/     EditState (the nondestructive edit), EditHistory (undo/redo, timeline, jumpTo),
               EditDiff (names what a step changed, for the History panel)
  project/     Project, ProjectCodec (versioned JSON), FileProjectStore, ProjectManager
  export/      ExportOptions (format JPEG/PNG/WebP, quality, size, metadata, border, watermark),
               Border/BorderOps, Watermark, ExportDecorator (platform hook; must not modify its input)
  validation/  OutputValidator (natural vs structural)
  usecase/     EnhanceImageUseCase — open → analyse → plan → render → validate → export;
               EditState.toRequest() (the one edit → request mapping); BatchExportUseCase
  debug/       DebugReport
engine/harness Desktop CLI running the real engine on image files

app/  (Android)
  data/decoder   AndroidImageRepository (bounds-first decode, subsampling, EXIF orientation),
                 AndroidFaceLocator (android.media.FaceDetector, on device)
  data/exif      ExifNormalizer, MetadataCopier
  data/storage   MediaStoreImageSaver (verified export, JPEG/PNG/WebP), AndroidWatermarkDecorator,
                 IncomingImages (camera captures + validated shared imports), ShareCache,
                 ThumbnailStore, AppStorage (storage figure)
  data/preferences SharedPreferencesSettingsRepository (typed reads fall back to defaults)
  ui/            Compose screens (home, editor + panels, settings, guide, about, debug);
                 EditorViewModel implements EditorActions and coordinates only — no image maths;
                 panels/PanelControls is the single list of which slider belongs to which panel
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

1. A `content://` URI from the photo picker, from a camera capture (`files/captures`, via the
   FileProvider) or from a shared/"Edit with" import (validated and copied to `files/imports`) →
   `AndroidImageRepository.readSource` (type, size, orientation) → decode at working size
   (≤2560 px) with orientation applied. Other URI schemes are refused.
2. `StatisticalImageAnalyzer` + `SceneClassifier` on the working image → `EnhancementSession`
   (also holds a ≤1280 px preview).
3. Each edit: `EditState` → `EnhanceRequest` → plan = planner(analysis, strength, scene preset) +
   manual offsets + colour mixer → pipeline on the preview → validation → geometry → screen.
4. Export: decode the source again at the size needed (≤24 MP) → same plan → pipeline in tiles →
   validation → geometry → resize to the photo's share of the requested size → border →
   `ExportDecorator` (watermark, drawn on a copy) → `MediaStoreImageSaver` (verified) → project
   records the export. Batch export repeats this per photo with the copied settings groups.
5. Every committed edit autosaves the project (edit + bounded history), never pixels.

## Processing order

Exposure → White balance (auto or as shot, + temperature/tint) → Calibration (camera primaries,
shadows tint) → Defringe (purple/green edge fringes, a capture artefact) → Dehaze → Tone
(contrast, highlights, shadows, whites, blacks, midtones) → Noise reduction → Detail (clarity) →
Sharpen → Colour finish (vibrance, saturation) → Colour mixer → Vignette → Grain; then geometry.
The full list, with face exposure, curves, texture and grading, is under Render order below.

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

## Render order

Pipeline (preview or tiled export): Exposure → White balance → Calibration → Defringe → Dehaze →
Face exposure → Tone → Curves → Noise reduction → Clarity → Texture → Sharpen → Colour finish →
Colour mixer → Colour grading / B&W → Vignette → Grain. Then validation, then geometry (turns, flip, lens + perspective
warp, straighten, crop), then retouch spots, then local masks — the last two are positioned on
the photo as the user sees it.

## Trust boundaries

- **Other apps → Pixels:** only `content://` image URIs; Pixels' own provider authority is refused;
  MIME type checked against the supported list; at most 200 MB; copied into app storage before
  use (`IncomingImages`).
- **Pixels → other apps:** the private FileProvider grants per-URI read access to three folders
  (`cache/shared`, `files/captures`, `files/imports`); exports go to MediaStore as new files.
- **Device → anywhere else:** nothing. No permissions (no network), backups and device transfer
  exclude all app data. Enforced in CI by `scripts/security_gate.py`; see SECURITY.md.

