# Offline type-check

Compiles the Android app's Kotlin — `app/src/main`, `app/src/test` and `app/src/androidTest` —
and runs the app unit tests **without the Android SDK or Google's Maven repository**, using only
Maven Central. Useful in sandboxes that block `dl.google.com`, where `./gradlew :app:…` cannot run.

```bash
tools/offline-typecheck/run.sh      # prints app compiler errors/warnings; exit code 0 = clean
```

How it works: the real Kotlin and Compose compilers build the app sources against

- Compose Multiplatform desktop 1.8.2 — the same `androidx.compose.*` API as the app's Compose BOM
  (2025.05.00 = Compose 1.8);
- Robolectric's `android-all` (Android 15) for `android.*` classes;
- the engine, as an included build;
- compile-only stand-ins in `stubs/` and `teststubs/` for the few AndroidX APIs that exist only on
  Google Maven (activity, core, lifecycle, exifinterface, `stringResource`, `LocalContext`,
  AndroidX Test); `generate_r.py` writes `R` and `BuildConfig` from `strings.xml`.

Limits: a clean result means the code type-checks against Compose 1.8 and the Android 15 API; it
is not a substitute for the real build. CI (`./gradlew :app:assembleDebug`, lint and device tests)
remains the authority. Nothing here is packaged into the app. If the app starts using another
Google-only API, add its signature to `stubs/` (bodies are `TODO()`; they are never run).
