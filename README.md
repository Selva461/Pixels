# Pixels — Natural Image Enhancer

Android app that takes a poor-looking photo and corrects it so it looks **naturally corrected, not edited**.
It detects what is wrong (exposure, colour cast, flatness, noise, softness) and applies only the minimum
correction, with a recorded reason for every decision. All processing happens on the device.

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
```

The app consumes the engine as an included build, so the dependency direction is
`UI → ViewModel → EnhanceImageUseCase → analyzer / planner / processor / validator → platform adapters`.

## Common commands

```bash
./gradlew -p engine test                     # engine unit + golden tests (no Android SDK)
./gradlew -p engine :harness:run --args="--synthetic --out /tmp/out"
./gradlew -p engine :harness:run --args="--strength 0.6 --disable sharpen --until detail photo.jpg"
./gradlew :app:assembleDebug                 # needs the Android SDK (local.properties / ANDROID_HOME)
```

The harness writes `<name>_enhanced.png`, `<name>_compare.png` (before | after) and `<name>_report.txt`
(the same report as the in-app debug screen).

Documentation: [REQUIREMENTS.md](REQUIREMENTS.md) (product spec) · [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
(status matrix) · [ARCHITECTURE.md](ARCHITECTURE.md) · [TESTING.md](TESTING.md) · [HANDOFF.md](HANDOFF.md)
(current state, known issues, next action).
