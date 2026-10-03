# Testing

## Commands

```bash
./gradlew -p engine test          # engine unit, integration and golden tests (no Android SDK needed)
./gradlew -p engine build         # same + compile harness
./gradlew -p engine :harness:run --args="--synthetic --out /tmp/out"   # visual review images
./gradlew :app:assembleDebug      # Android build (needs the Android SDK; runs in CI)
./gradlew :app:connectedDebugAndroidTest   # device tests (CI runs them on an API 30 emulator)
```

CI: `.github/workflows/android.yml` runs engine tests, builds the debug APK (artifact
`pixels-debug-apk`) and runs the instrumented tests on an emulator for every push.

## Coverage (149 engine tests, 6 instrumented tests)

| Category | Where |
|---|---|
| Unit — metrics, planner rules (cases A–F), strength scaling, stages, tone curve, HSL, dehaze, geometry, crop maths, history, codec | `engine/domain/src/test` |
| Integration — use case open/enhance/export/save, validation, error mapping, tiling seams, scene override | `usecase/`, `processing/` tests |
| Golden — 9 synthetic degradations with measurable expectations | `golden/GoldenScenarioTest` |
| Storage — project round trip, corrupt/future files, atomic writes, path safety | `ProjectPersistenceTest` |
| Export (device) — save + verify, full-resolution above working size, PNG at size, rotate+crop, metadata location removal | `app/src/androidTest/.../SaveFlowTest` |
| UI | ⬜ no Compose UI tests yet |
| Performance | desktop JVM timings only (2560×1920 noisy scene ≈ 2.3 s total; denoise ≈ 1 s) |

## Manual verification checklist (on a device)

- Pick a JPEG, a PNG and a portrait-orientation phone photo; check orientation.
- Auto tab: strength 0 / default / 100; "Original (no edits)"; scene chips change the result.
- Press and hold the photo → original; drag the divider; pinch-zoom.
- Adjust: every slider moves the preview; tapping a value resets it; histogram updates.
- Color tab: each band affects only its colours; greys stay grey.
- Crop: rotate, flip, straighten, aspect presets, drag frame; result after leaving the tab.
- Undo/redo across slider, look, crop and colour changes.
- Export each format and size; open the file in another gallery app; check dimensions and that
  the original is unchanged; try "Remove location".
- Close with un-exported edits → prompt; reopen from Recent edits after force-stopping the app.
