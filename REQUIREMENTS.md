# Pixels --- Natural Photo Enhancement

## Product Requirements, Technical Specification & Claude Code Implementation Guide

**Document:** `REQUIREMENTS.md`\
**Product:** Pixels (existing application upgrade)\
**Primary objective:** Transform ordinary photographs into polished,
professional-looking images through natural, non-generative image
processing.

------------------------------------------------------------------------

## 1. Product Vision

Upgrade the existing Pixels app into a capable, easy-to-use photo
editor. Improve photographic quality by adjusting the image's existing
pixels---exposure, white balance, tone, color, detail and
composition---while preserving the original scene, people, objects,
textures and identity.

### Core principles

-   Natural-looking results; restrained corrections over aggressive
    filters.
-   Non-generative processing: do not invent, add, replace or remove
    image content.
-   Nondestructive editing: preserve the original and store edits
    separately.
-   Beginner-friendly automatic enhancement with comprehensive manual
    control.
-   Reliable preview, project recovery and full-resolution export.
-   Modular architecture that supports future features without a
    rewrite.
-   Local processing by default; no photo upload to external services
    without explicit consent.

### The app must

-   Analyze a photo and recommend appropriate adjustments.
-   Support scene-aware enhancement for portraits, landscapes, food,
    architecture, night, indoor, nature, beach, documents and low-light
    photos.
-   Let users tune or override automatic adjustments.
-   Compare original and enhanced versions.
-   Undo, redo and reset edits.
-   Save and reopen editable projects.
-   Export readable images at selected dimensions and quality.
-   Report save failures accurately and retain editing progress.

### The app must not

-   Generate or add objects, people, skies, backgrounds or synthetic
    detail.
-   Change facial structure, body shape, identity or skin texture.
-   Apply aggressive HDR, saturation, sharpening, denoising or blur by
    default.
-   Overwrite the source photo without explicit user consent.
-   Claim an export succeeded before validating the saved output.

------------------------------------------------------------------------

## 2. Scope and Priorities

### P0 --- Release-blocking foundation

1.  Audit the existing app and its framework.
2.  Reproduce and fix the reported saving/export defect.
3.  Preserve the original image.
4.  Establish reliable image decoding, orientation and color handling.
5.  Implement a testable adjustment state model.
6.  Add basic exposure, white balance, contrast and color controls.
7.  Verify saved output is valid and readable.

### P1 --- Core editing experience

-   Adaptive Auto Enhance.
-   Highlights, shadows, whites, blacks and midtones.
-   Saturation, vibrance and basic color correction.
-   Enhancement Strength control.
-   Before/after comparison.
-   Undo/redo, reset and project persistence.
-   Full-resolution export and clear failure recovery.

### P2 --- Advanced editing

-   HSL, tone curves and histogram.
-   Detail, sharpening and noise reduction.
-   Crop, straighten, lens and perspective correction.
-   Selective/local adjustments and masks.
-   Portrait-specific natural corrections.

### P3 --- Future extensions

-   Batch editing.
-   RAW processing.
-   Custom presets.
-   Layers and blending.
-   Advanced color grading.
-   Optional accelerated processing and expanded masking.

Do not implement all advanced features before the core workflow is
stable.

------------------------------------------------------------------------

## 3. Functional Requirements

### 3.1 Import and source preservation

-   Import images using the platform's supported photo picker or file
    APIs.
-   Validate file type, integrity, dimensions and decode success.
-   Read metadata where available, including EXIF orientation,
    dimensions and color profile.
-   Apply orientation correctly before display or export.
-   Preserve the original source; never edit it in place.
-   Support formats available in the existing platform. JPEG and PNG are
    minimum export targets; HEIC input should be retained where
    supported.
-   Display actionable errors for unsupported, corrupt or inaccessible
    images.

### 3.2 Exposure and lighting

Provide independent, validated controls:

  Parameter               Purpose                                        Suggested range
  ----------------------- -------------------------------------------- -----------------
  Exposure                Overall brightness in exposure-value terms         -3 to +3 EV
  Brightness              Perceived overall lightness                       -100 to +100
  Contrast                Separation between light and dark                 -100 to +100
  Highlights              Bright tonal regions                              -100 to +100
  Shadows                 Dark tonal regions                                -100 to +100
  Whites                  Brightest tonal regions                           -100 to +100
  Blacks                  Darkest tonal regions                             -100 to +100
  Midtones                Middle luminance values                           -100 to +100
  Gamma                   Midtone response                                    0.5 to 2.0
  Exposure compensation   Fine exposure correction                           -2 to +2 EV

Requirements: - Detect underexposure, overexposure and clipping. -
Recover details only where source data contains recoverable
information. - Avoid turning highlights grey or lifting shadows until
depth is lost. - Preserve plausible scene contrast and lighting. - Keep
parameter ranges configurable and validated.

### 3.3 White balance

-   Temperature: suggested 2000--12000 K.
-   Tint: suggested -100 to +100.
-   Auto white balance.
-   User-selectable neutral point/eyedropper where supported.
-   Warmth adjustment for simple workflows.
-   Avoid neutralizing intentionally warm scenes such as sunsets or
    candlelight.
-   Explain that clipped or absent color information cannot be reliably
    reconstructed.

### 3.4 Color correction

-   Saturation: -100 to +100.
-   Vibrance: -100 to +100.
-   Hue: -180° to +180°.
-   Color balance: -100 to +100.
-   Natural Color restraint: 0--100.
-   Individual red, green and blue saturation controls.
-   HSL controls for red, orange, yellow, green, aqua, blue, purple and
    magenta, each with Hue, Saturation and Luminance.
-   Preserve realistic skin tones, foliage, skies and scene atmosphere.
-   Prevent channel clipping and unnatural color shifts.

### 3.5 Tone curve and histogram

-   RGB master curve and separate red, green and blue curves.
-   Shadow, midtone and highlight control points.
-   Smooth interpolation and safe point limits.
-   Reset and undo.
-   Histogram visualization behind the curve.
-   Avoid unstable or invalid curve states.
-   Clearly distinguish preview histogram from source histogram, if both
    are shown.

### 3.6 Detail and noise

  Parameter                   Purpose                          Suggested range
  --------------------------- ------------------------------ -----------------
  Sharpening                  Edge definition                           0--100
  Radius                      Sharpening spread                      0.1--3 px
  Detail                      Fine-detail emphasis                      0--100
  Masking                     Restrict sharpening to edges              0--100
  Luminance noise reduction   Reduce brightness noise                   0--100
  Color noise reduction       Reduce chroma noise                       0--100
  Texture                     Fine texture contrast               -100 to +100
  Clarity                     Local contrast                      -100 to +100
  Dehaze                      Atmospheric haze correction         -100 to +100

-   Avoid halos, ringing, amplified noise and waxy textures.
-   Preserve skin, hair, foliage and fine detail.
-   Do not imply that sharpening restores detail absent from the source.
-   Make denoising intensity adjustable and reversible.

### 3.7 Lens and geometry

-   Lens distortion and vignetting correction.
-   Chromatic aberration correction.
-   Perspective and vertical/horizontal alignment.
-   Rotation, straightening and horizon correction.
-   Crop presets: Original, 1:1, 4:3, 3:2, 16:9 and 9:16.
-   Freeform crop, rotate 90° left/right, flip horizontal/vertical and
    composition grid.
-   Keep geometric edits separate and reversible.

### 3.8 Selective adjustments

Support extensible local editing through: - Linear gradient. - Radial
gradient. - Brush mask. - Subject, sky and background masks where
supported.

Masks may identify existing regions but must not create or replace image
content. Support relevant local exposure, tone, color and detail
adjustments. Allow mask editing, inversion, feathering and reset where
technically supported.

### 3.9 Portrait-specific controls

-   Face-region exposure correction.
-   Natural skin-tone color correction.
-   Subtle face-region noise reduction.
-   Eye-region exposure correction.
-   Hair-detail preservation.
-   Natural skin texture preservation.
-   Red-eye correction if supported.

Do not reshape faces, enlarge eyes, whiten teeth, smooth skin
aggressively or automatically beautify a person.

### 3.10 Auto Enhance

Auto Enhance must analyze the input and recommend a set of coordinated
adjustment values. It must not apply one fixed preset to every image.

Analysis should consider: - Exposure and luminance distribution. -
Histogram and clipping. - White balance and likely color cast. -
Contrast and tonal separation. - Color balance and saturation. - Noise,
blur and detail. - Likely scene type. - Face-region locations where
relevant. - Resolution, compression artifacts and image integrity.

Scene classification is advisory; users must be able to override it.

Scene-aware priorities: - Portrait: natural skin tone, restrained
highlights, realistic detail. - Landscape: balanced sky/foreground,
realistic greens and depth. - Night: noise control, highlight protection
and preserved nighttime atmosphere. - Food: accurate color, restrained
warmth and texture. - Architecture: perspective, neutral color and
balanced contrast. - Nature: natural foliage and detail. - Beach:
realistic water, sand and highlight control. - Indoor: mixed-light
correction and exposure. - Low light: restrained shadow recovery,
denoising and sharpening. - Documents: legibility, contrast and
background correction.

#### Naturalness constraints

-   Prefer small coordinated changes over extreme adjustments.
-   Protect important highlights and shadows from unnecessary clipping.
-   Preserve believable skin colors and natural textures.
-   Avoid oversaturated foliage and skies.
-   Avoid excessive local contrast, artificial HDR and edge halos.
-   Do not sharpen noise or compression artifacts.
-   Preserve lighting direction and scene atmosphere.
-   Never invent missing details.

#### Enhancement Strength

Provide a 0--100% slider that scales the proposed adjustments rather
than selecting a separate fixed filter. Include a true original/zero
state. Label strength clearly and allow users to review the result.

------------------------------------------------------------------------

## 4. User Experience and Screens

### Home

-   Import from gallery/files.
-   Camera entry point only if already supported or approved for
    implementation.
-   Recent edits and saved projects.
-   Settings and help.

### Editing

-   Large image preview.
-   Original/enhanced comparison via press-and-hold and/or draggable
    divider.
-   Auto Enhance and Enhancement Strength.
-   Categorized adjustment panels.
-   Undo, redo, reset and save/export.
-   Clear unsaved-changes behavior.

### Advanced editor

-   Exposure, light, white balance, color, HSL, curves, detail, geometry
    and selective adjustments.
-   Histogram and contextual parameter descriptions.
-   Reset individual parameter or group.
-   Avoid crowding the beginner workflow; advanced tools can be
    progressively disclosed.

### Export

-   Format, resolution, quality and color profile.
-   Metadata retention/removal options.
-   Save to gallery or supported destination.
-   Progress, cancel where feasible, success details and actionable
    error states.

### Interaction requirements

-   Auto Enhance produces a preview.
-   Press-and-hold reveals the original.
-   All edits are reversible.
-   Slider changes update preview without blocking the UI.
-   Reset restores the original editing state.
-   Closing with unsaved changes prompts the user.
-   No inert controls: every visible action must work or be clearly
    marked unavailable.

------------------------------------------------------------------------

## 5. Technical Architecture

First inspect the current repository and extend its existing framework
and conventions. Do not choose a new framework or image library before
understanding the existing implementation.

Recommended logical modules:

  -----------------------------------------------------------------------
  Module                              Responsibility
  ----------------------------------- -----------------------------------
  `PhotoImporter`                     Import and validate source images

  `MetadataReader`                    Read EXIF, orientation, resolution
                                      and color profiles

  `ImageAnalyzer`                     Analyze exposure, color, noise and
                                      detail

  `SceneClassifier`                   Estimate scene type

  `AutoEnhancer`                      Generate adjustment recommendations

  `AdjustmentManager`                 Store, validate and update edit
                                      parameters

  `ImageProcessor`                    Run non-generative image operations

  `PreviewRenderer`                   Render responsive previews

  `MaskManager`                       Manage local masks

  `HistoryManager`                    Undo, redo and restore state

  `ProjectManager`                    Persist editable project data

  `ExportManager`                     Full-resolution render and encoding

  `GallerySaver`                      Save to device or selected
                                      destination

  `PermissionManager`                 Handle platform permissions

  `ErrorHandler`                      Actionable error handling and
                                      diagnostics

  `PerformanceMonitor`                Measure latency, memory and
                                      failures
  -----------------------------------------------------------------------

### Data flow

1.  Import original image.
2.  Read metadata and apply orientation.
3.  Decode and normalize color representation.
4.  Generate a preview representation.
5.  Analyze exposure, color, noise, detail and scene.
6.  Generate recommended adjustments.
7.  Apply naturalness constraints.
8.  Render preview.
9.  Let user compare and adjust.
10. Render full-resolution output.
11. Encode, write and verify output.
12. Commit to destination and report confirmed status.

### Engineering principles

-   Keep UI, processing, persistence and export separate.
-   Use a nondestructive edit model.
-   Store adjustment instructions independently of original image bytes.
-   Define explicit color-space and transfer-function handling.
-   Keep preview and full-resolution rendering separate.
-   Reuse intermediate results where safe.
-   Use background execution for expensive work.
-   Use GPU acceleration only when supported and beneficial.
-   Avoid unnecessary full-resolution buffer copies.
-   Keep processing modules independently testable.
-   Validate parameters and intermediate results.
-   Use versioned project schemas and migrations.

### Suggested processing sequence

Load → orientation → decode/color normalization → geometric correction →
white balance → exposure/tone → color/HSL → local adjustments → noise
reduction → detail/sharpening → output color transform → encoding.

The exact ordering may vary for mathematically correct linear-light
processing, masks and denoising. Document deviations.

------------------------------------------------------------------------

## 6. Save and Export Reliability (Critical)

The reported save defect is a release blocker and must be investigated
before adding advanced features.

### Requirements

-   Export at selected dimensions and resolution.
-   Support JPEG and PNG at minimum, and supported HEIC workflows where
    available.
-   JPEG quality control.
-   Correct orientation and color profile behavior.
-   Explicit metadata preservation/removal.
-   Use supported platform media/file APIs.
-   Request permissions only when required.
-   Show progress for lengthy exports.
-   Prevent accidental duplicate saves and source overwrites.
-   Verify output exists and decodes successfully.
-   Provide clear success/failure messages.
-   Retain the editor state after failure.
-   Cancel safely where feasible.
-   Prevent partial output from appearing as a successful save.

### Export state flow

`Validate state → choose options → render full resolution → encode → write temporary output → verify decode/integrity → commit destination → verify committed file → report success`

If validation fails, clean up partial output, retain the editing session
and show an actionable error.

### Failure cases

-   Permission denied.
-   Insufficient storage.
-   Unsupported format.
-   Decode/encode error.
-   Interrupted export or app lifecycle interruption.
-   Invalid source.
-   Duplicate filename.
-   Gallery or destination write failure.
-   Low-memory condition.

A save is successful only after the destination contains a valid,
readable image---not merely when the save button is tapped or encoding
returns success.

------------------------------------------------------------------------

## 7. Projects, History and Recovery

A project should store: - Project ID. - Original image reference. -
Relevant metadata. - Adjustment parameters. - Scene classification if
used. - Masks and crop/rotation. - Enhancement engine version. -
Creation and modification timestamps. - Export settings. - Bounded edit
history or snapshots.

Requirements: - Reopen and continue editing. - Version project schema
and support migrations. - Avoid storing duplicate full-resolution images
for every edit. - Group slider movement into logical history actions. -
Recover safely after ordinary app restarts. - Do not delete the original
when a project is removed unless explicitly requested.

------------------------------------------------------------------------

## 8. Performance and Memory

Initial targets, to be benchmarked on actual supported devices: - Basic
preview: within 1 second for typical supported photos. - Auto Enhance
preview: within 3 seconds for typical supported photos. - Slider
interaction: responsive and non-blocking. - Undo/redo: near-instant for
ordinary edits. - Export: show progress; do not promise a fixed
completion time. - Large images: use tiled or memory-efficient
processing where needed. - App interruption: preserve recoverable state.

Measure preview latency, full-resolution export time, peak memory, crash
rate and failure rate. Adjust targets based on real device measurements
rather than assumptions.

------------------------------------------------------------------------

## 9. Quality Validation

### Automated checks

-   Output dimensions and format.
-   Orientation and color profile handling.
-   Unexpected clipping introduced by processing.
-   Invalid parameter handling.
-   Image decode integrity.
-   Export readability.
-   Memory use and processing latency.
-   Channel corruption, banding and artifacts.
-   Preservation of source file.

### Visual regression set

Maintain fixed representative test images: - Underexposed and
overexposed portraits. - Backlit people. - Mixed indoor lighting. -
Night photography. - Landscapes with bright skies. - Low-light noisy
scenes. - Natural saturated colors. - Compressed images. -
High-resolution photographs.

For each, document expected behavior and perform visual review. Higher
contrast, sharpness or saturation is not automatically better;
naturalness requires human visual evaluation.

------------------------------------------------------------------------

## 10. Testing Requirements

  Category      Coverage
  ------------- -------------------------------------------------------
  Unit          Adjustment functions, ranges, color transforms, masks
  Integration   Import, analysis, enhancement, rendering
  Export        Formats, quality, resolution, metadata
  Storage       Save, reopen, overwrite protection, retry
  UI            Sliders, comparison, reset, history, navigation
  Regression    Existing app functionality
  Performance   Large images, memory, latency
  Device        Supported OS versions and device classes
  Failure       Permissions, interruption, corrupt files, low storage
  Visual        Naturalness, color, artifacts and detail

### Acceptance checklist

-   [ ] Existing working functionality retained or changes documented.
-   [ ] Auto Enhance operates on supported input types.
-   [ ] Manual core controls work.
-   [ ] Original/enhanced comparison works.
-   [ ] Reset restores original appearance.
-   [ ] Undo/redo works.
-   [ ] No generative content is introduced.
-   [ ] Portraits retain natural features and texture.
-   [ ] High-resolution exports retain requested dimensions.
-   [ ] Exported images open outside the app.
-   [ ] Failed exports preserve editing progress.
-   [ ] Interrupted exports never report false success.
-   [ ] Processing does not freeze the UI.
-   [ ] Projects survive normal restarts.
-   [ ] Automated tests pass and critical issues are resolved.

------------------------------------------------------------------------

## 11. Privacy and Security

-   Process locally by default.
-   Do not upload photos externally without explicit consent.
-   Request only necessary permissions.
-   Avoid logging image content, GPS data or sensitive metadata.
-   Let users choose whether GPS/EXIF metadata is retained.
-   Store temporary files in appropriate app storage.
-   Clean up temporary files after completion or abandonment.
-   Avoid unnecessary copies of original photos.
-   Clearly distinguish any future cloud or AI feature from local
    processing.

------------------------------------------------------------------------

## 12. Extensibility

Design stable interfaces for future: - Layers and blending. - More
advanced masks and selections. - Custom presets. - Batch editing. - RAW
support. - Advanced color grading. - Expanded project history. -
Accelerated processing.

Each module must have explicit inputs/outputs, validation and tests.
Avoid coupling UI components directly to processing internals.

------------------------------------------------------------------------

## 13. Phased Development Plan

### Phase 1 --- Audit and save defect

Inspect the repository, stack, import/edit/export flows, reproduce the
defect, identify root cause, document architecture and establish
baseline tests.

### Phase 2 --- Core image engine

Implement validated adjustment state, exposure, contrast, highlights,
shadows, white balance, saturation and color handling. Add
nondestructive editing and preview rendering.

### Phase 3 --- Auto Enhance

Implement image analysis, scene-aware recommendations, naturalness
constraints and Enhancement Strength. Validate on representative photos.

### Phase 4 --- Advanced editing

Add curves, HSL, noise reduction, sharpening, geometry, masks and
portrait-specific restrained corrections.

### Phase 5 --- Export and reliability

Complete full-resolution export, destination integration, file
validation, recovery and lifecycle testing.

### Phase 6 --- Quality and release

Run automated and visual regression tests, benchmark supported devices,
fix regressions and prepare release documentation.

Do not proceed to dependent phases while critical earlier failures
remain unresolved.

------------------------------------------------------------------------

## 14. Claude Code Implementation Directive

### Initial repository inspection

Before editing: 1. Inspect repository structure, README,
dependency/build files. 2. Identify framework, supported platforms and
processing libraries. 3. Map current import, analysis, enhancement,
preview and save paths. 4. Run current tests and record existing
failures. 5. Reproduce the save defect and locate its root cause. 6.
Identify the existing design system and reuse it. 7. Create
`IMPLEMENTATION_PLAN.md` with findings, architecture, phases,
dependencies and risks.

Do not rewrite the application or replace its framework without a
demonstrated technical reason.

### Development rules

-   Preserve working behavior.
-   Use modular, maintainable and human-debuggable code.
-   Follow existing language/framework conventions.
-   Separate UI, processing, persistence and export.
-   Use typed interfaces where supported.
-   Avoid needless abstractions, duplicated logic and monolithic files.
-   Justify new dependencies.
-   Do not add paid APIs or cloud services without approval.
-   Do not silently swallow errors.
-   Add privacy-safe diagnostic logging.
-   Document non-obvious algorithms.
-   Keep changes focused and reviewable.

### Workflow

1.  Audit.
2.  Plan.
3.  Establish baseline tests.
4.  Fix save/export defect first.
5.  Implement adjustment state.
6.  Build processing pipeline.
7.  Implement Auto Enhance.
8.  Integrate editor UI.
9.  Add export and project persistence.
10. Add advanced features incrementally.
11. Run relevant tests after each meaningful change.
12. Update docs and handoff.

Do not claim implementation or verification without actually completing
the work and running relevant tests.

### Documentation to maintain

-   `README.md`: setup and project overview.
-   `ARCHITECTURE.md`: module responsibilities and data flow.
-   `REQUIREMENTS.md`: product and technical requirements.
-   `IMPLEMENTATION_PLAN.md`: phases and progress.
-   `TESTING.md`: test commands, coverage and manual verification.
-   `HANDOFF.md`: completed work, changed files, tests, known issues and
    the single next action.

### Git and handoff

-   Inspect `git status` before starting.
-   Never discard or overwrite user changes.
-   Do not commit or push unless explicitly instructed.
-   Keep changes reviewable.
-   Update `HANDOFF.md` at the end of each work session.
-   If interrupted, resume from the documented next action; do not redo
    completed work.

### Definition of done

A feature is done only when implementation, integration, relevant tests
and documentation are complete. The application must preserve originals,
produce naturally enhanced results, provide functional controls, export
readable full-resolution images and remain maintainable.

------------------------------------------------------------------------

## 15. First Implementation Priorities

1.  **Fix saving/export** --- reproduce, resolve and verify the actual
    saved file.
2.  **Stabilize the image pipeline** --- orientation, color handling,
    preview and nondestructive edits.
3.  **Implement natural Auto Enhance** --- adaptive exposure, white
    balance, tone and color.
4.  **Add manual controls** --- user control over core parameters and
    enhancement strength.
5.  **Add advanced tools** --- curves, HSL, detail, masks and geometry.
6.  **Expand later** --- batch editing, RAW and layers after the core is
    stable.

**Key decision:** Begin by auditing the existing Pixels app. The
framework, image-processing libraries and platform-specific
implementation must be selected based on the repository, not assumed in
advance.
