# Contributing

**This repository does not accept code contributions from outside the project.** It is public so
people can read it; pull requests from anyone other than the owner will be closed without review.
Bug reports are welcome as issues. Security problems: see [SECURITY.md](SECURITY.md) (please do not
post details publicly).

The rest of this page is for the owner and the coding agents working on the project.

## Ground rules

- **No AI features.** The owner chose a non-generative editor: every output pixel comes from the
  photo's own pixels. No subject/sky detection models, generative fill or AI denoise.
- **Originals are read-only.** Exports are new files; projects store edits, never pixels.
- **On-device only.** The app requests no permissions; adding one (especially `INTERNET`) needs an
  owner decision and updates to SECURITY.md, the privacy policy and `scripts/security_gate.py`.
- **Single sources of truth:** `EditState.toRequest()` maps every edit field to a render request;
  `PanelControls` decides which sliders each panel shows, resets and marks as edited; user-visible
  text lives in `strings.xml` (plurals for counts).

## Before you push

```bash
python3 scripts/security_gate.py         # permissions, components, provider paths, secrets, CI
python3 scripts/check_resources.py       # every string exists and gets the right arguments
./gradlew -p engine build                # engine: compile (warnings are errors) + 222 tests
tools/offline-typecheck/run.sh           # app compile check without Google Maven (sandboxes)
./gradlew --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug   # needs the Android SDK
./gradlew :app:connectedDebugAndroidTest # device + Compose UI tests (emulator or phone)
```

CI runs all of these on every push (`.github/workflows/android.yml`) and uploads the debug APK and
the test and lint reports.

## Conventions

- Kotlin official style; imports sorted, no unused imports; no wildcard imports.
- Engine code is pure Kotlin/JVM and unit-tested; no Android classes in `engine/`.
- New edit fields: add to `EditState`, `toRequest()`, `ProjectCodec` (optional, defaulted),
  `PresetMath.paste` / `SettingsGroup` if copyable, `EditDiff` labels, and the fuzz generator in
  `EditFuzzTest`.
- New UI: strings in resources; content descriptions on icon-only buttons; destructive actions
  confirm; both portrait and landscape must fit.
- Errors: map to an `ErrorCode` and show `ErrorMessages`; never crash on bad input or storage.
- Commits explain why; update HANDOFF.md (status, known issues, next action) and CHANGELOG.md.

## Repository rules

The default branch is protected (pull request, code-owner review by @Selva461, required checks).
Pushes to existing branches are also refused, so each push goes to a new branch and the pull
request is opened from the final one.
