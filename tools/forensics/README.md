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
| 3. Edge-aware mattes | partly | with the phone-resolution person mask: halo 25 px / 117 → 4 px / 30.2 (limit 3 px / 15); mask coverage 0.16 passes |
| 4. Base/detail tone | done | logo 14 % → 93 %, folds 62 % → 126 %, subject highlight texture 0.952 → 0.982, sky noise ×1.74 → ×1.54 |

`scripts/check_output` now hands the harness a stand-in for the phone's people model: the sample's subject matte
squashed to DeepLab's 257 × 257 (`PEOPLE=rules` uses the rules-based subject instead). With a person found, Smart edit
really edits the subject, so subject checks that the rules path hid now show.

Still failing after Task 4: halo extent 4 px, halo area 30.0 (Task 3, see below) and skin ΔE00 7.25 / hue −3.20°
(Task 5). Global fit (0.73), crushed channels (0.42 %) and mask coverage (0.07) pass.

Task 4: masked edits (Smart edit's subject, sky and background) run on a base/detail split of log luminance
(`BaseDetail`: self-guided filter, r = 28 px at 3.7 MP, ε = 0.25 stops², fitted at reduced size). Exposure, white
balance, the tone curve and saturation act on the base; the detail goes back as `(1 + k)·detail` with k from the mask's
clarity, so it is exactly 1 where there is no clarity. A lift fades out on objects whose brightest channel is already
near white (keyed on a coarse average, so a shirt's folds all move together), and the base's brightest channel rolls
off on a soft shoulder instead of clipping. Smart edit no longer puts clarity on the sky, and the masked tone curve is
interpolated rather than looked up in 1/1024 steps (those steps showed as sky noise). The global stages are unchanged:
on the sample Auto only applies exposure and white balance, which are plain gains and keep detail as it is.

Task 3: masks are refined with a colour-guided filter and evaluated on the photo at full size (`GuidedMatte`), never
blurred, and the sky detector no longer takes golden grass under a blue sky for a sunset cloud (sky 70 % → 31 %). The
halo left is the mask's resolution, not feathering: fed the same 257 × 257 mask, the brief's own reference pipeline also
fails (8 px / 23.6); it passes only with a full-resolution matte.

Never loosen a threshold in `tests/test_acceptance.py` to get green; they were calibrated on one
photo and must be re-calibrated on 20+ varied pairs before release.
