# Forensics harness (test oracle)

Measures an original/edited photo pair and decides pass/fail against the defects found in a
real-world edit: halo from blurred masks, flattened texture, crushed colour channels, sky noise,
skin shifts, mask coverage, and how far a single global grade explains the change. Imported from
the "claude-code-pack" (spec: [CLAUDE_CODE_BRIEF.md](CLAUDE_CODE_BRIEF.md), Task 1).

| Path | What it is |
| --- | --- |
| `src/edit_forensics.py` | Measures any original/edited pair |
| `src/validate_forensics.py` | Self-test of the measuring tool on synthetic halos |
| `src/enhance_reference.py` | Reference pipeline that passes every check (the target for Tasks 2–6) |
| `src/export_look_cube.py`, `src/fx_*.py` | Look extraction (Task 7) and building blocks |
| `tests/test_acceptance.py` | Thresholds: the sample edit must fail, the reference must pass |
| `samples/` | `original_p3.jpg` (Display P3), `edit_srgb.jpg` (the measured edit), `subject_matte.png` |

```bash
python3 -m venv .venv && .venv/bin/pip install -r tools/forensics/requirements.txt
cd tools/forensics && ../../.venv/bin/python -m pytest -q tests   # 4 passed, 1 skipped (~4 min)
PYTHON=.venv/bin/python scripts/check_output                      # Pixels' render of the sample, failing checks listed
PYTHON=.venv/bin/python scripts/check_roundtrip                   # Task 2: P3 round trip and crushed channels, no edit
```

`scripts/check_output` renders `samples/original_p3.jpg` with the engine harness exactly as a new
photo opens in the app (Auto + Smart edit, working resolution), exports the sRGB copy as JPEG quality 95
(the app's default export) and runs `test_app_output` on it. `scripts/check_roundtrip` opens the sample
with no edit, exports it in its own profile (PNG, so only the colour path is measured) and compares it
with the original using `src/roundtrip_check.py`.

## Progress on the brief

| Task | State | Measured on the sample |
| --- | --- | --- |
| 1. Measurement harness | done | 4 passed, 1 skipped |
| 2. Colour-managed I/O | done | crushed channels 12.37 % → passes (sRGB copy alone: 0.64 %); P3 round trip ΔE00 mean 0.00, p99 0.00 |
| 3. Edge-aware mattes | partly | halo 6 px / area 18.8 → 6 px / 16.7 (rules subject); with a person mask: 25 px / 117 → 6 px / 30.0; coverage 0.21 passes |

Still failing on the app render after Task 3: halo extent 6 px, halo area 16.7, sky noise ×1.79, skin ΔE00 4.50 /
hue −3.14°, global fit 1.13. Masks are now refined with a colour-guided filter and evaluated on the photo at full size
(`GuidedMatte`), never blurred. What still drives the halo is not feathering: the desktop harness has no people model,
so the rules-based subject covers only the torso and the sky detector takes the grass field for sky; with a person
mask at the phone model's 257 × 257, the brief's own reference pipeline also fails (8 px / 23.6), and Smart edit's
subject lift (+0.84 EV plus shadows) is about 2.4× the reference's +0.35 EV.
Never loosen a threshold in `tests/test_acceptance.py` to get green; they were calibrated on one
photo and must be re-calibrated on 20+ varied pairs before release.
