# Testing

## Commands

```bash
python3 scripts/security_gate.py   # permissions, components, provider paths, backups, risky APIs, secrets, CI
python3 scripts/check_resources.py # every string exists, is used, and gets the right format arguments
./gradlew -p engine build          # engine: compile with warnings as errors + 222 unit/integration/golden/fuzz tests
./gradlew -p engine :harness:run --args="--synthetic --out /tmp/out"   # visual review images
tools/offline-typecheck/run.sh     # compile app main + unit + device tests without Google Maven; runs app unit tests
./gradlew --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug   # needs the Android SDK
./gradlew :app:connectedDebugAndroidTest   # device + Compose UI tests (CI: API 30 emulator)
```

CI (`.github/workflows/android.yml`) runs every line above except the harness and the offline
type-check on every push, and uploads the debug APK (`pixels-debug-apk`), the engine/app test and lint reports
(`build-reports`) and the device test report (`device-test-reports`). Any lint **error** fails the
build; lint warnings are in the report.

## Coverage (222 engine tests · 9 app unit tests · 33 device tests, 15 of them Compose UI)

| Category | Where |
|---|---|
| Unit — metrics, planner rules (cases A–F), strength scaling, stages, tone curve, curves (monotone, LUT, presets), HSL, dehaze, texture, calibration, defringe, geometry, crop maths, local masks (brush, ranges, duplicate/rename), face exposure (incl. backlit), lens/perspective, heal/clone, grading, presets (amount blending, paste groups), history (timeline, jump, labels), codec | `engine/domain/src/test` |
| Property / fuzz — 60 seeded random edits render without crashing, stay opaque and the promised size; 200 seeded edits survive save/load; every change gets a history label | `processing/EditFuzzTest` |
| Integration — use case open/enhance/export/save, As shot, border + size, WebP, watermark ordering, batch (incl. failures), validation, error mapping, tiling seams, scene override | `usecase/`, `processing/` tests |
| Golden — 9 synthetic degradations with measurable expectations | `golden/GoldenScenarioTest` |
| Storage — project round trip, duplicate/rename, corrupt/future files, atomic writes, path safety | `ProjectPersistenceTest`, `ProEditorExtrasTest` |
| App unit — every slider in exactly one panel; panel resets clear only their panel | `app/src/test/.../PanelControlsTest` |
| Contrast (app unit) — every colour pair the app draws meets WCAG 2.2: 4.5:1 text, 3:1 borders, slider rails, thumbs and curve lines, in both schemes; photo labels stay readable over white and black | `app/src/test/.../theme/ContrastTest` |
| Export (device) — save + verify, full resolution above working size, PNG at size, rotate+crop, metadata location removal, WebP, border size and colour, watermark drawn on the export but never on the open photo | `app/src/androidTest/.../SaveFlowTest` |
| Watermark (device) — input never modified, text lands in the chosen corner, long text shrinks, blank text draws nothing | `WatermarkDecoratorTest` |
| Import (device) — `file://` and Pixels' own URIs refused; a shared photo is copied in and opens | `IncomingImagesTest` |
| Settings (device) — every field round-trips; old export keys migrate; damaged/wrongly typed values fall back | `SettingsRepositoryTest` |
| UI (device, Compose) — every tool opens, slider set-progress reaches the edit and finishes one step, top-bar actions, panel Reset enabled only when edited, history jump, batch progress/summary/cancel, home start actions, remove-edit confirmation, settings toggle and delete confirmation | `ui/EditorScreenTest`, `ui/HomeAndSettingsTest` (fake `EditorActions` records calls) |
| Accessibility (device, Compose) — on every screen, editor tool, panel sub-view and dialog, laid out at 320 × 560 dp: every control has a spoken name, every tap target is at least 48 × 48 dp, and at 200 % text nothing is cut off or ellipsised. Failures are listed together | `ui/AccessibilityTest` |
| Security | `scripts/security_gate.py` (self-tested against injected problems) |
| Performance | desktop JVM timings only (2560×1920 noisy scene ≈ 2.3 s total; denoise ≈ 1 s); device benchmark ⬜ |

## Manual verification checklist (on a device)

- Pick a JPEG, a PNG and a portrait-orientation phone photo; check orientation.
- Auto tab: strength 0 / default / 100; "Original (no edits)"; scene chips change the result.
- Press and hold the photo → original; drag the divider; pinch-zoom.
- Adjust: every slider moves the preview; tapping a value resets it; histogram updates.
- Color tab: each band affects only its colours; greys stay grey.
- Curve tab: add/drag/remove points on master and R/G/B; curve never inverts.
- Local tab: add radial and linear masks, drag handles, invert, delete; edits stay inside the mask.
- Presets: each category applies; amount 0/100/200 %; save your own, long-press to delete.
- Masking: paint and erase a brush mask; luminance and colour range (tap to pick); overlay on/off.
- Healing: tap a spot, drag source/target, switch heal/clone, delete.
- Optics/Geometry: distortion and vertical perspective on a building photo; no empty edges.
- Versions: save, apply, delete; copy settings on one photo and paste on another.
- Pick white balance on a grey card or white wall; Auto straighten a tilted horizon; Auto upright a building.
- Red eye on a flash portrait; Show clipping on an overexposed sky; Hue slider; Home > Guide opens offline.
- Portrait/backlit photo: auto lifts the face without darkening it; Face exposure slider works;
  photos without faces are unchanged by it.
- Crop: rotate, flip, straighten, aspect presets, drag frame; result after leaving the tab.
- Undo/redo across slider, look, crop and colour changes.
- Export each format and size; open the file in another gallery app; check dimensions and that
  the original is unchanged; try "Remove location".
- Close with un-exported edits → prompt; reopen from Recent edits after force-stopping the app.
- Settings: change Auto strength and open a new photo; turn haptics off; turn "ask before leaving"
  off; set export defaults and check the export dialog starts from them; Clear temporary files,
  Delete my presets and Delete all edits (gallery photos must remain).
- Take a photo with the default camera app, and cancel once (no empty edit appears).
- Share a photo to Pixels from Google Photos / Files and use "Edit with"; force-stop Pixels and
  reopen the edit from Recent.
- Apply to other photos: pick 3 photos, cancel one run, check new files in Pictures/Pixels and
  Recent edits.
- History: make several changes, tap an earlier step, then make a new change.
- Calibration, Defringe (purple fringe on a backlit branch), As shot vs Auto white balance.
- Export WebP, a border (sized and full), and a watermark in each corner; reopen the editor and
  confirm the watermark is not on the photo.
- Rotate the phone in Home, the editor and Settings: nothing clipped.
- Accessibility (FEATURE_SPEC "How each release is checked"): with TalkBack, do the five main
  tasks (open a photo, brighten it and bring back the sky with Light, add and change a mask, undo
  and compare, save a copy without location); repeat with a keyboard and with Switch Access; set
  the largest font and display size and look for overlapping text (automated tests cover cut-off
  text, not overlap).

