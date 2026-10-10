# Audit

_2026-10-08 · covers the whole repository on branch `claude/premium-editor-5` and its successors_

A strict review of development, QA, security, UI and documentation for the "pro editor" release.
Every finding below was either fixed in this round (with the check that now guards it) or is
listed as open with the owner decision it needs.

## Method

- **Line-by-line review** of every file changed since the last release (engine, app, tests, CI).
- **Compilation without Google Maven.** The development container cannot reach Google's Maven
  repository, so the Android app cannot be built there. The app's main code, unit tests and
  instrumented tests were therefore compiled with the real Kotlin and Compose compilers against
  Compose Multiplatform 1.8.2 (desktop, the same Compose 1.8 API) and Robolectric's `android-all`
  (Android 15 framework classes), with small compile-only stand-ins for the AndroidX activity,
  core and lifecycle APIs the app uses: zero errors, zero warnings. See
  `tools/offline-typecheck/`. CI then builds the real APK.
- **Engine build with compiler warnings as errors**, 222 unit, integration, golden and seeded
  fuzz tests.
- **Device tests** on an API 30 emulator in CI: 18 instrumented tests (export, import, settings,
  watermark) and 10 Compose UI tests.
- **Automated gates** added in this round: `scripts/security_gate.py`, `scripts/check_resources.py`
  and Android lint (errors fail the build). Each gate was tested against deliberately broken input.

## Findings fixed in this round

Severity: **High** = wrong output or crash in normal use · **Medium** = wrong behaviour in a
reachable case or a security weakness · **Low** = edge case, polish or hardening.

| ID | Area | Sev. | Finding | Fix | Guarded by |
|---|---|---|---|---|---|
| A-01 | Export | High | The watermark was drawn into the render buffer in place. For a photo with no edits that buffer can be the open session's own image, so the watermark would appear in every later preview and export. | The Android decorator draws the text on a small bitmap and blends it into a copy; the `ExportDecorator` contract now forbids modifying the input. | `WatermarkDecoratorTest`, `SaveFlowTest.watermarkIsDrawnOnTheExportButNeverOnTheOpenPhoto` |
| A-02 | Settings | High | A preference stored with an unexpected type (damaged or restored file) threw `ClassCastException` while the app started, every time. | Every typed read falls back to its default. | `SettingsRepositoryTest.damagedOrWronglyTypedValuesFallBackToDefaults` |
| A-03 | Presets | Medium | Preset Amount 0 % replaced the user's own Light and Colour edits instead of returning to them. | `PresetMath.apply` blends from the edit before the preset; 0 % returns it exactly. | `ProEditorExtrasTest` |
| A-04 | Editing | Medium | Rotating or flipping produced `-0.0` values that are not `equals` to `0.0`: duplicate history steps, "No change" labels and project files that did not round-trip. | `negate()` normalises signed zero in geometry. | `EditFuzzTest` (200 seeded save/load round trips) |
| A-05 | Projects | Medium | Colour-grading Blending and Balance were dropped on save when every wheel was neutral. | Grading is stored whenever it differs from the default. | `EditFuzzTest`, `ProEditorExtrasTest` |
| A-06 | App | Medium | The view model built render requests field by field and missed new edit fields (calibration, white-balance mode), so those edits did not render. | One mapping, `EditState.toRequest()`, used by the app, batch export and tests. | `ProEditorExtrasTest` |
| A-07 | Import | Medium | "Share to Pixels" contained only the app's own exception type; any other exception from a third-party provider would crash. Opening a shared photo also cancelled its own job. | Every failure ends on the error screen; the import has its own job. | `IncomingImagesTest`, review |
| A-08 | Storage | Medium | File errors in background saves (autosave, thumbnails, Recent list) could crash the app. | Storage work runs through `attempt {}`, which contains failures but never swallows cancellation. | Review, compile check |
| A-09 | Security | Medium | CI ran with the default token permissions and kept credentials in the checkout. | `permissions: contents: read`, `persist-credentials: false`; the security gate rejects workflows without them or using `pull_request_target`. | `security_gate.py` |
| A-10 | Security | Low | The decoder accepted any URI scheme. | Only `content://`; shared imports also refuse Pixels' own provider and cap size at 200 MB. | `IncomingImagesTest` |
| A-11 | Calibration | Low | Calibration saturation barely changed the photo (row normalisation cancelled it). | Columns are scaled so white stays white and saturation takes effect. | `ProEditorExtrasTest` |
| A-12 | Export | Low | Unusual source containers made the EXIF copy throw runtime exceptions and fail the export. | Metadata stays best-effort; the published file is still verified by decoding it. | Review; `SaveFlowTest` covers the normal metadata path |
| A-13 | Performance | Low | History labels were recomputed on every slider frame. | Cached; recomputed only when history changes. | Review |
| A-14 | UI | Low | White-balance tap-to-pick kept stale coordinates if the window was resized. | Gesture keyed on the fitted frame. | Review |
| A-15 | Accessibility | Low | Disabled sliders still accepted the accessibility "set progress" action. | Disabled sliders expose `disabled()` instead. | Review |
| A-16 | UI | Low | Home was clipped on phones held sideways. | Two-column landscape layout (the editor already had one). | Review |
| A-17 | Copy | Low | "1 photos", "1 spots"; "JPEG quality" shown for WebP. | Plural resources; "Quality". | `check_resources.py` |
| A-18 | Settings | Low | The storage figure counted only imported photos and did not refresh after clean-up. | Measures all app files; refreshed after every clean-up action. | Review; `HomeAndSettingsTest` covers the display |

## Security review

Threat model and controls: [SECURITY.md](SECURITY.md). Results:

- **Permissions:** none requested (no internet, storage, camera or media permissions). ✅
- **Components:** only the launcher activity is exported (MAIN, SEND and EDIT for images); the
  FileProvider is private, grants per-URI access and serves three folders. ✅
- **Data at rest:** app-private storage only; backup and device transfer exclude every domain. ✅
- **Inputs from other apps:** scheme, authority, MIME type and size validated before copying. ✅
- **Logging:** one logger; ids and numbers only. ✅
- **Secrets:** none in the repository (gate scans every tracked file). ✅
- **CI:** read-only token, no persisted credentials, wrapper validation. ✅
- **Open:** actions pinned by major version, not commit SHA; no Gradle dependency verification;
  release signing and R8 not configured (no release build exists yet). See SECURITY.md.

## UI and accessibility review

- Every slider exposes its label, value text, range and a set-progress action, so TalkBack users
  can read and change it; double-tap reset and drag remain for touch.
- Tool strip entries are tabs with a selected state; icon-only buttons have descriptions; toggles
  are whole-row; destructive actions (remove an edit, delete presets, delete all edits) confirm first.
- Counts use plural resources; every string is in `strings.xml` and checked against its arguments.
- Layouts: portrait and landscape for Home and the editor; the photo never shrinks to a sliver.
- **Open:** not yet checked on a phone with TalkBack, at 200 % font size, or for colour contrast
  with a measuring tool.

## Requirements traceability

The full matrix is in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md); requirements added during
development, with acceptance criteria and evidence, are in REQUIREMENTS.md § 16. The acceptance
checklist from REQUIREMENTS.md § 10:

| Acceptance item | Status | Evidence |
|---|---|---|
| Existing functionality retained or changes documented | ✅ | CHANGELOG.md, HANDOFF.md |
| Auto Enhance works on supported input types | ✅ | Engine tests, `SaveFlowTest` (JPEG/PNG); HEIC device test ⬜ |
| Manual core controls work | ✅ | Engine stage tests, `EditorScreenTest` |
| Original/enhanced comparison works | ✅ | Implemented; manual check on a device ⬜ |
| Reset restores original appearance | ✅ | "Original (no edits)", panel resets: `PanelControlsTest`, `EditorScreenTest` |
| Undo/redo works | ✅ | `EditHistory` tests, history jump UI test |
| No generative content | ✅ | No AI or generative code by design (user decision); every pixel comes from the photo |
| Portraits retain natural features | ✅ | Face exposure only (no reshaping); limits in `NaturalLimits` |
| High-resolution exports keep requested dimensions | ✅ | `SaveFlowTest` (full size above working size, sized PNG, border) |
| Exported images open outside the app | ✅ | Decoded back from MediaStore in `SaveFlowTest` |
| Failed exports preserve editing progress | ✅ | Export never changes the edit; autosave is separate |
| Interrupted exports never report false success | ✅ | Verified publish; deleted on cancel/failure |
| Processing does not freeze the UI | ✅ | Background dispatchers, debounced previews; device benchmark ⬜ |
| Projects survive restarts | ✅ | `ProjectPersistenceTest`; force-stop check on a device ⬜ |
| Automated tests pass, critical issues resolved | ✅ | CI (build, lint, unit, device and UI tests) |

## Open items (owner decisions or real devices needed)

1. **Target SDK.** The app targets API 35. Google Play raises its target-API requirement every
   August; check whether API 36 is now required before publishing, then test on Android 16
   (predictive back is already supported through `BackHandler`).
2. **Release build:** signing key, R8, store listing and privacy-policy review.
3. **Real-device pass:** performance on a mid-range phone, TalkBack, 200 % font, several camera
   and gallery apps for camera capture and sharing, HEIC input.
4. **Real-photo visual regression set** (the golden tests use synthetic scenes).
5. **Supply chain:** SHA-pinned actions, Dependabot, Gradle dependency verification.
6. **Lint warnings:** errors fail CI; warnings are in the uploaded `build-reports` artifact for triage.
7. **Not implemented by design or yet:** AI masks and generative tools (user chose no AI); HEIC
   and colour-profile export; hue shifts beyond ±30° (by design).
8. **Repository rule:** pushes to existing branches are refused, so each push needs a new branch;
   pull requests are opened from the final branch.
