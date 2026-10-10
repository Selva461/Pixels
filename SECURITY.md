# Security

Pixels edits personal photos entirely on the phone. This page says what it protects, how, how
that is checked, and how to report a problem.

## Reporting a vulnerability

Report it privately through GitHub's private vulnerability reporting (repository **Security**
tab > **Report a vulnerability**). If that option is not shown, open a public issue that only asks
the owner (@Selva461) for a private channel and contains no details. Include the app version (on
the About screen), the Android version and steps to reproduce.

## What is protected

| Asset | Where it lives | Promise |
|---|---|---|
| The user's original photos | Their gallery / other apps | Only ever read, never modified or deleted |
| Edits, previews, presets | App-private storage (`filesDir`) | Never leave the device; excluded from backup and device transfer |
| Imported and camera photos | `filesDir/imports`, `filesDir/captures` | Same; deletable from Settings |
| Exported photos | `Pictures/Pixels` (MediaStore) | Written as new files; GPS removed by default |
| Photo metadata (EXIF, GPS) | Inside the photos | Never logged; export keeps, strips location (default) or strips all |

## Threat model

| Threat | Control | Checked by |
|---|---|---|
| Photos or metadata sent off the device | No `INTERNET` permission (no permissions at all); no network code | `scripts/security_gate.py` (manifest + source scan) |
| Another app makes Pixels read its private files ("confused deputy") through a shared `file://` URI or Pixels' own provider URI | Imports accept only `content://` URIs from other apps; Pixels' own FileProvider authority is refused; the decoder refuses non-`content` schemes | `IncomingImagesTest`, code review |
| A hostile or broken sender fills storage | Shared images are type-checked against the supported list and capped at 200 MB (declared size and actual bytes copied) | `IncomingImages.importShared`, `IncomingImagesTest` |
| Malformed images or files crash or exhaust memory | Bounds-first decode, size limits (working ≤2560 px, export ≤24 MP), every failure mapped to an `ErrorCode` and shown as a message | Engine tests, fuzz tests (`EditFuzzTest`) |
| Damaged or tampered project / settings files | Versioned JSON with defaults for unknown or broken values; wrongly typed preferences fall back to defaults; path-safe ids | `ProjectPersistenceTest`, `SettingsRepositoryTest` |
| A swapped or tampered bundled model | The people model (`assets/models/deeplab_v3.tflite`) is pinned by SHA-256 in `assets/models/README.md`; it runs offline in TensorFlow Lite and only returns a mask | `scripts/security_gate.py` (checksum) |
| Exported components abused | Only the launcher activity is exported (it handles MAIN, SEND image/\*, EDIT image/\*); the FileProvider is private and grants per-URI read access only; it serves three folders: `cache/shared`, `files/captures`, `files/imports` | Security gate (manifest + `file_paths.xml`) |
| Personal data in backups | `allowBackup="false"` and data-extraction rules excluding every domain for cloud backup and device transfer | Security gate |
| Sensitive data in logs | One logger (`LogcatLogger`); events carry ids and numbers, never pixels, file names, URIs or GPS | Security gate (no `android.util.Log` elsewhere), code review |
| Partial or false-success exports | MediaStore pending entry → verify → metadata → verify → publish → verify; deleted on any failure or cancel | `SaveFlowTest` on the CI emulator |
| Watermark or export edits leaking into the open photo | Export decorators must not modify their input (contract in `ExportDecorator`); the Android decorator draws on a copy | `WatermarkDecoratorTest`, `SaveFlowTest.watermarkIsDrawnOnTheExportButNeverOnTheOpenPhoto` |
| Secrets or signing keys committed | No keystores, no credentials in the repository | Security gate (pattern scan of tracked files) |
| CI abused to modify the repository | Workflow token is read-only (`permissions: contents: read`), checkout does not keep credentials, no `pull_request_target`, no untrusted event text in scripts | Security gate (workflow checks) |
| Gradle wrapper replaced with a malicious jar | `gradle/actions/setup-gradle` validates `gradle-wrapper.jar` against Gradle's published checksums | CI |

Out of scope: a rooted or compromised phone, other apps with storage-wide access reading the
exported files in `Pictures/Pixels` (they are the user's photos by design), and the security of
the gallery or camera app the user chooses.

## Supply chain

- Dependencies come only from Google Maven and Maven Central (`settings.gradle.kts` forbids
  project repositories). Versions are pinned in `gradle/libs.versions.toml`.
- The engine has two runtime dependencies (kotlinx-coroutines, kotlinx-serialization); there is no
  third-party image or network library.
- CI actions are pinned to major versions from GitHub (`actions/*`, `gradle/actions`) and the
  emulator runner.

### Recommended owner actions (need repository settings or network access this project's tooling did not have)

1. Pin each workflow action to a full commit SHA (with the version in a comment) and enable
   Dependabot version updates for `github-actions` and `gradle`, so pins stay current through
   reviewed pull requests.
2. Enable GitHub secret scanning and push protection, and Dependabot security alerts
   (Settings > Code security).
3. Add Gradle dependency verification (`gradle/verification-metadata.xml`, generated with
   `./gradlew --write-verification-metadata sha256 help` on a machine with Google Maven access).
4. Before publishing: create a release signing key outside the repository, keep it in a password
   manager or Play App Signing, and enable R8 (`isMinifyEnabled = true`) for the release build
   after checking the app with it.
5. Keep the branch ruleset on the default branch (pull request + code-owner review + required
   checks) so outside changes cannot land without review.

## Privacy

The in-app privacy policy (About) describes exactly this behaviour: no account, no analytics, no
network, no data collection. The bundled people model runs on the device and needs no network.
Any future online feature must be opt-in, separate from local processing, and reflected in that
policy and this page before it ships.
