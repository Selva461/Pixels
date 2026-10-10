# Brief for Claude Code: halo-free, color-managed photo enhancement

You are improving a photo enhancement app. This pack holds a measured teardown of a real
before/after edit, a reference pipeline that fixes every measured defect, the measuring tool,
and acceptance tests. Your job: bring the app's pipeline up to the standard the tests define.

Work ONE task at a time, in order (Task 1 → Task 7). After each task: run the tests, report what
changed and the test output, then stop and wait for the go-ahead.

## 0. Ground rules

- App already exists → port the techniques into its language and stack. Keep the Python tools in
  this pack as the test oracle: render with the app, measure with `src/edit_forensics.py`.
- Starting fresh → Python 3.10+, numpy, scipy ≥ 1.12, opencv-python, pillow, onnxruntime.
- Never loosen a threshold in `tests/test_acceptance.py` to make a test pass. If a threshold looks
  wrong, explain why, propose a value, and wait for approval.
- Before adding any ML model, read its LICENSE file. Do not ship non-commercial-licensed weights.
- All image math runs in float. Quantize to 8 bits once, at export.

## 1. What the sample edit did (measured)

`samples/edit_srgb.jpg` vs `samples/original_p3.jpg`, both 2212 × 1659 px, pixel-aligned,
compared in CIELAB after decoding each through its own ICC profile.
Full numbers: `results/forensics_edit/measurements.json`.

| Operation | Measured effect |
| --- | --- |
| Global exposure/contrast | Background L* −3 to −11 (deepest shadows least, mids most); mean 63.1 → 55.2; p99 93.1 → 90.4 |
| Sky | L* 88.8 → 81.8; chroma 8.2 → 13.3; noise σ 0.057 → 0.173 L* |
| Hue-selective color | Yellow-greens ×1.07 chroma, 2.4° warmer; vivid greens ×0.75–0.77 |
| Clarity on landscape | +9% to +21% local contrast at 2–4 px; bottom 13% of frame −6% |
| Subject relight | +4.4 L* in subject shadows/mids; ≈ +10 L* above the background grade |
| Halo (mask spill) | +8.8 L* at the edge, < 1 L* only beyond 25 px; fits a 1.2-stop lift through a Gaussian feather σ ≈ 14 px |
| Mask coverage | Dark subject pixels +5 to +7 L* vs original down the body, then −4.8 to −5.6 below the shins (grass occlusion) |
| Texture loss | Logo contrast 11.9 → 2.4 L*; shirt fold shading −35%; plain-shirt texture −34% to −43% |
| Export | sRGB; blue channel = 0 on 11.2% of pixels (original 2.2%) |

The original is Display P3; 11.2% of its pixels are outside sRGB.

## 2. Root causes → required fixes

| Defect | Root cause | Required fix |
| --- | --- | --- |
| Halo | Mask blurred outward; lift lands on background pixels | Refine every mask with an edge-aware filter guided by the image (`guided_filter_color`, r ≈ 6 px, ε = 1e-4 at 3.7 MP). Never blur a mask outward. |
| Mask stops at the shins | Mask likely lost the legs where grass covers them (inferred) | Check matte coverage per row band; refine uncertain bands; never let a lift end mid-object |
| Flattened fabric/logo | Smoothing or tone compression on the subject's full signal | Tone moves on an edge-aware BASE layer only; add detail back with gain ≥ 1; no smoothing on the subject |
| Crushed blue channel | Saturated P3 greens hard-clipped to sRGB | Float pipeline; soft gamut compression in Oklab at constant L and hue; keep a P3 export |
| Sky noise ×3 | Sharpening on a flat region | Detail gain exactly 1.0 on sky and flat areas |
| Ambiguous color | Profile handling | Read ICC on load; linear float working space; embed ICC on export |

## 3. Target pipeline (as implemented in `src/enhance_reference.py`)

1. **Decode.** Parse the ICC profile (`fx_color.load`), convert to XYZ (D50), then to linear
   RGB with sRGB primaries, unclipped (negative/over-1 values keep P3 colors).
2. **Mattes.** Subject: any person/salient matte (BiRefNet ONNX used here), refined by
   `guided_filter_color(guide=image, r=6, eps=1e-4)`. Sky: top region with b* < 4 and L* > 60,
   refined the same way (r=16, eps=1e-3), multiplied by (1 − subject).
3. **Base/detail.** `s = log2(Y / 0.18)`; `base = guided_filter(s, s, r=28, eps=0.25)`; `detail = s − base`.
4. **Region exposure on the base.**
   `ev = −0.45·(1−A)(1−S) + 0.35·A·(1 − smoothstep(−0.3, 1.5, base))`; `b2 = base + ev`.
5. **Protected contrast.**
   `b2 += 0.15·(b2 + 0.3)·(1 − smoothstep(0.6, 1.6, b2))·smoothstep(−3.0, −1.5, b2)`;
   above 2.1 stops: `2.1 + x / (1 + x/0.8)` with `x = b2 − 2.1`.
6. **Detail back.** `s2 = b2 + (1 + k)·detail`, `k = 0.30` landscape, `0.12` subject, `0` sky.
   Apply as a gain: `RGB' = RGB · (Y'/Y)` with `Y' = 0.18 · 2^s2`.
7. **Color in Oklab.** Skin weight `exp(−((h − 42°)/18°)²)·clip(C/0.03, 0, 1)`.
   Vibrance `v = 0.30·(1 − 0.85·skin)·(1 − min(C/0.20, 1))`; chroma gain `1 + v + 0.55·S`;
   sky `L −= 0.06·S`; warmth `b += 0.004·(1−S)(1 − skin·A)·clip(L/0.5, 0, 1)`.
8. **Export.** Soft gamut compression (knee 0.85 for sRGB, 0.9 for P3), TPDF dither ±1 LSB,
   JPEG q95 4:4:4, ICC embedded. Write P3 (source profile) and an sRGB copy.

All parameters live in `DEFAULTS` in `enhance_reference.py`. Pixel radii assume ~3.7 MP;
scale them with `sqrt(megapixels / 3.7)`.

## 4. Acceptance tests (`tests/test_acceptance.py`)

| Check | Sample edit | Reference | Must be |
| --- | --- | --- | --- |
| Halo extent (dL* ≥ 1 outside subject) | 25 px | 2 px | ≤ 3 px |
| Halo area index (0–100 px) | 114.0 | 8.7 | ≤ 15 |
| Subject highlight texture, 4–8 px | 0.953 | 0.992 | ≥ 0.97 |
| Pixels with a channel ≤ 1 | 11.23% | 1.64% | ≤ 3% |
| Sky noise vs original | ×3.04 | ×1.46 | ≤ ×1.6 |
| Skin ΔE00 / hue shift | 5.44 / −1.4° | 3.68 / +0.4° | ≤ 4.0 / ≤ 1.5° |
| Mask coverage (worst band vs median lift) | 1.89 | 0.18 | ≤ 0.5 |
| Global-grade fit, far background | 1.54 ΔE00 | 0.58 | ≤ 1.0 |
| Logo / fold contrast kept | 20% / 65% | 105% / 114% | ≥ 90% |

Thresholds are calibrated on ONE photo. Before trusting them, collect 20+ varied before/after
pairs and re-calibrate; report the distribution, do not hand-pick.

Commands:
```
python -m pytest -q tests                                   # 4 pass, 1 skipped
OUTPUT_UNDER_TEST=path/to/app_render.jpg python -m pytest -q tests -k app
```

## 5. Tasks — one at a time, in order

**Task 1 — Measurement harness.** Copy `src/` and `tests/` into the repo (e.g. `tools/forensics/`),
add the dependencies, run the tests. Add `scripts/check_output` that renders
`samples/original_p3.jpg` through the app and runs the app test on the result.
Done when: 4 pass, 1 skipped, and `check_output` prints the app's failing checks.

**Task 2 — Color-managed I/O.** Load through the ICC profile into a float linear working space;
export P3 with the source profile plus an sRGB copy using soft gamut compression and dither.
Done when: crushed-channel ≤ 3% on the sample, and a no-edit round trip to P3 has mean ΔE00 < 0.1
and p99 < 1.5 (the reference: 0.05 and 1.08 — the soft gamut knee moves near-boundary colors a
little by design).

**Task 3 — Edge-aware mattes.** Replace every mask blur/feather with guided-filter refinement.
Done when: halo extent ≤ 3 px, halo area ≤ 15, and mask coverage ≤ 0.5 (no 100-row band of
subject pixels with original L* 10–40 strays more than 50% from the subject's median lift; the
sample edit scores 1.89 because its lift flips from +5.6 to −4.8 L* at the shins).

**Task 4 — Base/detail tone pipeline.** Implement stages 3–6. Done when: logo and fold contrast
≥ 90%, subject highlight texture ≥ 0.97, sky noise ≤ ×1.6.

**Task 5 — Oklab color stage.** Implement stage 7 with skin protection. Done when: skin ΔE00
≤ 4.0 and hue shift ≤ 1.5°, and all earlier checks still pass.

**Task 6 — Quality gate.** After each render, run the checks; on a failure, lower the parameter
that drives it (halo → matte radius/lift, texture → clarity, crush → vibrance, sky noise → detail
gain on sky, skin → vibrance on skin) by 25% and re-render, at most 3 times; then ship the best
passing render or the original with a message. Done when: a deliberately bad parameter set gets
corrected automatically in a test.

**Task 7 — Look extraction.** Wrap `src/export_look_cube.py`: from a before/after pair, fit the
global grade (33³ LUT, subject excluded), report the held-out ΔE00, apply it to other photos,
export `.cube`. Warn the user when the fit error is above 1.2 (the look includes local work a LUT
cannot carry). Done when: the sample pair exports a cube with held-out ΔE00 ≈ 1.55 and a synthetic
purely-global pair fits below 1.0.

## 6. Hard rules

Do:
- Decode with the ICC profile; process in float; quantize once with dither.
- Put every tone move on an edge-aware base layer; add detail back.
- Refine masks with the image as guide; scale radii with resolution.
- Measure every change with the harness; keep the sample edit failing and the reference passing.

Don't:
- Blur a mask to soften it.
- Sharpen or add clarity on sky or other flat regions.
- Smooth the subject to "clean it up".
- Hard-clip out-of-gamut colors.
- Change thresholds or delete tests to get green.

## 7. Known limits

- Sample files are re-encoded 2212-px copies (JPEG q≈85, EXIF stripped); pixel distances scale
  with resolution (σ ≈ 14 px here is 0.63% of the width).
- The subject matte came from BiRefNet-lite; the full model needs more than 8 GB RAM on CPU.
- Whether the editing app read the P3 tag cannot be told from pixels (both readings fit equally).
- Thresholds come from one photo; Task 1 must be followed by a calibration set before release.
