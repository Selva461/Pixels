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
```

`scripts/check_output` renders `samples/original_p3.jpg` with the engine harness exactly as a new
photo opens in the app (Auto + Smart edit, working resolution) and runs `test_app_output` on it.
Never loosen a threshold in `tests/test_acceptance.py` to get green; they were calibrated on one
photo and must be re-calibrated on 20+ varied pairs before release.
