<img src="docs/brand/pixels-icon-512.png" alt="" width="96" align="right">

# Pixels — Natural Image Enhancer

Android app that takes a poor-looking photo and corrects it so it looks **naturally corrected, not edited**.
It detects what is wrong (exposure, colour cast, flatness, noise, softness) and applies only the minimum
correction, with a recorded reason for every decision. All processing happens on the device.

On top of Auto Enhance it is a full manual editor: presets with an amount slider, crop and
straighten, light and tone curves, colour mixer, grading wheels and calibration, black and white,
effects, detail, lens, defringe and perspective correction, brush/gradient/range masks, spot
healing and cloning, named history, copy/paste of settings, versions, and applying settings to
many photos at once. Photos come from the picker, the camera or "Share to Pixels"; exports are
JPEG, PNG or WebP with optional border and watermark. No generated pixels: every result comes
from the photo's own pixels. **Smart edit** finds the subject, sky and background and gives each
its own sliders; a small bundled model (DeepLab v3, run on the phone) only helps find people.
No permissions, no network.

## Layout

```
engine/                     Pure-JVM Gradle build (no Android SDK needed)
  domain/                   Analysis, planning, processing stages, validation, use case
    src/main/…/core         errors, logging, timing, ids, algorithm version
    src/main/…/domain       analysis/ planning/ processing/ validation/ usecase/ debug/ …
    src/testFixtures        synthetic scenes, degradations, fakes (shared with the harness)
    src/test                unit + golden-scenario tests
  harness/                  Desktop CLI: run the real engine on image files
app/                        Android app (Kotlin + Compose): decoding, EXIF, MediaStore, UI
scripts/                    CI gates: security_gate.py, check_resources.py
tools/offline-typecheck/    Compile-check the app without the Android SDK or Google Maven
docs/brand/                 App icon source (SVG) and the 512 px store icon
```

The app consumes the engine as an included build, so the dependency direction is
`UI → ViewModel → EnhanceImageUseCase → analyzer / planner / processor / validator → platform adapters`.

## Common commands

```bash
./gradlew -p engine test                     # engine unit + golden tests (no Android SDK)
./gradlew -p engine :harness:run --args="--synthetic --out /tmp/out"
./gradlew -p engine :harness:run --args="--strength 0.6 --disable sharpen --until detail photo.jpg"
./gradlew :app:assembleDebug                 # needs the Android SDK (local.properties / ANDROID_HOME)
python3 scripts/security_gate.py             # security gate (also runs in CI)
tools/offline-typecheck/run.sh               # app compile check without Google Maven
```

The debug APK is built by CI on every push (artifact `pixels-debug-apk` on the Actions run).

The harness writes `<name>_enhanced.png`, `<name>_compare.png` (before | after) and `<name>_report.txt`
(the same report as the in-app debug screen).

Documentation:

| | |
|---|---|
| [USER_GUIDE.md](USER_GUIDE.md) | How to use every tool (also in the app: Home > Guide) |
| [FEATURE_SPEC.md](FEATURE_SPEC.md) | Feature specification: 137 features with acceptance tests, status and priority |
| [ROADMAP.md](ROADMAP.md) | The open features split into 22 pull requests, in order |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Product spec; § 16 lists requirements added during development with evidence |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Requirement status matrix |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, data flow, render order, trust boundaries |
| [TESTING.md](TESTING.md) | Commands, coverage, manual device checklist |
| [AUDIT.md](AUDIT.md) | Strict review: findings, fixes, traceability, open items |
| [SECURITY.md](SECURITY.md) | Threat model, controls, reporting a vulnerability |
| [HANDOFF.md](HANDOFF.md) | Current state, known issues, next action, history of changes |
| [CHANGELOG.md](CHANGELOG.md) | What changed, in user terms |
| [CONTRIBUTING.md](CONTRIBUTING.md) | No outside contributions; rules and checks for the owner and agents |
