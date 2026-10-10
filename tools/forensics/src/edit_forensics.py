"""edit_forensics.py — measure exactly what an edit did to a photo.

Compares ORIGINAL vs EDITED colorimetrically (each decoded with its own ICC profile)
and separates the edit into:
  1. a global color grade  (smooth 3D LUT fitted on background far from the subject)
  2. local edits           (residual after the global grade: subject relight, halos, masks)
  3. spatial filtering     (sharpening / clarity / smoothing, per scale and region)
  4. output damage         (clipping, crushed channels, gamut loss, noise, banding)

Usage:
  python edit_forensics.py ORIGINAL EDITED --matte subject.png --out results/
  python edit_forensics.py ORIGINAL EDITED --seg-model BiRefNet.onnx --out results/

Writes results/measurements.json (+ residual_map.png, residual_L.npy).
Requires: numpy scipy opencv-python pillow (onnxruntime only for --seg-model).
All numbers are CIELAB (D50, ICC PCS); 1 dL* ~ 1 just-noticeable step in lightness.
"""
import argparse, json, os
import numpy as np, cv2
from scipy import ndimage as ndi
from PIL import Image
from fx_color import load, xyz_to_lab, lch, deltaE2000, SRGB_M_D50
from fx_lut import fit_lut, apply_lut

STD_LUMA_Q = np.array([16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
                       14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
                       18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
                       49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99])


def jpeg_quality(quant):
    """Best-matching IJG quality for the luma table (estimate; encoder may not be IJG)."""
    if not quant or 0 not in quant:
        return None
    q = np.array(quant[0], dtype=float)
    best, err = None, 1e9
    for Q in range(1, 101):
        s = 5000 / Q if Q < 50 else 200 - 2 * Q
        t = np.clip(np.floor((STD_LUMA_Q * s + 50) / 100), 1, 255)
        e = np.abs(t - q).mean()  # Pillow returns tables in natural (row-major) order
        if e < err:
            best, err = Q, e
    return dict(ijg_quality=best, mean_abs_table_error=round(float(err), 3))


def immerkaer_sigma(I):
    """Fast noise std estimate (Immerkaer 1996) on a 2-D array."""
    I = I.astype(np.float64)
    k = np.array([[1, -2, 1], [-2, 4, -2], [1, -2, 1]], float)
    c = cv2.filter2D(I, -1, k)[1:-1, 1:-1]
    H, W = I.shape
    return float(np.sqrt(np.pi / 2) * np.abs(c).sum() / (6 * (W - 2) * (H - 2)))


def colorfulness(srgb01):
    """Hasler & Suesstrunk (2003) colorfulness on sRGB 0..255."""
    R, G, B = [srgb01[..., i] * 255 for i in range(3)]
    rg = R - G; yb = 0.5 * (R + G) - B
    return float(np.sqrt(rg.std() ** 2 + yb.std() ** 2) + 0.3 * np.sqrt(rg.mean() ** 2 + yb.mean() ** 2))


def band(L, s1, s2):
    L = L.astype(np.float32)
    a = cv2.GaussianBlur(L, (0, 0), s1) if s1 > 0 else L
    return a - cv2.GaussianBlur(L, (0, 0), s2)


def detect_horizon(lab):
    """Heuristic sky/land split for outdoor shots: first row per column where b* > 8 (vegetation/earth)."""
    b = cv2.GaussianBlur(lab[..., 2].astype(np.float32), (0, 0), 2)
    hz = np.argmax(b > 8, axis=0).astype(float)
    return ndi.median_filter(hz, size=41)


def r2(x, n=2):
    return None if x is None or (isinstance(x, float) and np.isnan(x)) else round(float(x), n)


def analyze(orig_path, edit_path, matte, out_dir, sky_mask=None, far_px=None):
    os.makedirs(out_dir, exist_ok=True)
    o, e = load(orig_path), load(edit_path)
    if o['enc'].shape != e['enc'].shape:
        raise SystemExit('sizes differ: align/resize the edit to the original first')
    H, W = o['enc'].shape[:2]
    Lo, Le = xyz_to_lab(o['xyz']), xyz_to_lab(e['xyz'])
    Co, ho = lch(Lo); Ce, he = lch(Le)
    yy, xx = np.mgrid[:H, :W]
    R = {}

    # ---------- metadata & alignment ----------
    gLo = Lo[..., 0].astype(np.float32); gLe = Le[..., 0].astype(np.float32)
    hp = lambda x: x - cv2.GaussianBlur(x, (0, 0), 8)
    (sx, sy), resp = cv2.phaseCorrelate(hp(gLo), hp(gLe), cv2.createHanningWindow((W, H), cv2.CV_32F))
    R['meta'] = dict(size=[W, H], original_profile=o['desc'], edited_profile=e['desc'],
                     original_jpeg=jpeg_quality(o['quant']), edited_jpeg=jpeg_quality(e['quant']),
                     alignment_shift_px=[r2(sx, 3), r2(sy, 3)], alignment_response=r2(resp, 3))

    # ---------- regions ----------
    subj = matte > 0.5
    dout = ndi.distance_transform_edt(~subj)
    din = ndi.distance_transform_edt(subj)
    if sky_mask is None:
        hz = detect_horizon(Lo)
        sky = yy < (hz[None, :] - 6)
        if sky.mean() < 0.02:
            sky = np.zeros_like(subj)
    else:
        sky = sky_mask
    sky &= ~subj
    far_px = far_px or max(150, int(0.135 * W))
    border = np.zeros_like(subj); border[4:-4, 4:-4] = True
    far = (dout > far_px) & border
    ground = ~sky
    R['regions'] = dict(subject_fraction=r2(subj.mean(), 4), sky_fraction=r2(sky.mean(), 4),
                        far_background_px=far_px, far_background_fraction=r2(far.mean(), 4))

    # ---------- color management ----------
    lin_srgb_o = o['xyz'] @ np.linalg.inv(SRGB_M_D50).T
    oog = ((lin_srgb_o < -1e-4) | (lin_srgb_o > 1 + 1e-4)).any(-1)
    from fx_color import srgb_eotf
    Ln = xyz_to_lab(srgb_eotf(o['enc']) @ SRGB_M_D50.T)  # what an unmanaged viewer shows
    Cn, _ = lch(Ln)
    R['color_management'] = dict(
        original_pixels_outside_sRGB_gamut_pct=r2(100 * oog.mean()),
        mean_chroma_original_managed=r2(Co.mean()), mean_chroma_original_unmanaged=r2(Cn.mean()),
        mean_chroma_edited=r2(Ce.mean()),
        note='unmanaged = original code values shown as if sRGB (viewer ignores ICC)')

    # ---------- global stats ----------
    def gstats(L, C):
        return dict(L_mean=r2(L[..., 0].mean()), L_std=r2(L[..., 0].std()),
                    L_p1=r2(np.percentile(L[..., 0], 1)), L_p99=r2(np.percentile(L[..., 0], 99)),
                    C_mean=r2(C.mean()), a_mean=r2(L[..., 1].mean()), b_mean=r2(L[..., 2].mean()))
    from fx_color import srgb_oetf
    so = np.clip(srgb_oetf(lin_srgb_o), 0, 1)
    se = np.clip(srgb_oetf(e['xyz'] @ np.linalg.inv(SRGB_M_D50).T), 0, 1)
    R['global'] = dict(original=gstats(Lo, Co), edited=gstats(Le, Ce),
                       colorfulness_original=r2(colorfulness(so)), colorfulness_edited=r2(colorfulness(se)))

    # ---------- clipping ----------
    def clip_stats(enc):
        return dict(any_channel_le1_pct=r2(100 * (enc <= 1 / 255).any(-1).mean()),
                    any_channel_ge254_pct=r2(100 * (enc >= 254 / 255).any(-1).mean()),
                    per_channel_le1_pct=[r2(100 * (enc[..., c] <= 1 / 255).mean()) for c in range(3)],
                    per_channel_ge254_pct=[r2(100 * (enc[..., c] >= 254 / 255).mean()) for c in range(3)])
    R['clipping'] = dict(original_as_stored=clip_stats(o['enc']), edited_as_stored=clip_stats(e['enc']))

    # ---------- global grade (3D LUT on far background) ----------
    rng = np.random.default_rng(0)
    idx = np.flatnonzero(far); rng.shuffle(idx)
    ntr = min(300000, int(0.8 * len(idx)))
    tr, te = idx[:ntr], idx[ntr:ntr + 150000]
    S = o['enc'].reshape(-1, 3); T = e['enc'].reshape(-1, 3)
    lut = fit_lut(S[tr], T[tr], N=33, lam=0.1)
    pred_enc = apply_lut(lut, o['enc'])
    pred_lin = np.stack([e['trc'][c](np.clip(pred_enc[..., c], 0, 1)) for c in range(3)], -1)
    Lp = xyz_to_lab(pred_lin @ e['M'].T)
    dte = deltaE2000(Lp.reshape(-1, 3)[te], Le.reshape(-1, 3)[te])
    R['global_grade_fit'] = dict(heldout_dE00_mean=r2(dte.mean()), heldout_dE00_median=r2(np.median(dte)),
                                 heldout_dE00_p95=r2(np.percentile(dte, 95)),
                                 note='how well ONE global color transform explains the background edit. '
                                      'Validated floor for a purely global edit saved as JPEG q85 4:2:0 is ~0.8 dE00 '
                                      '(validate_forensics.py); clearly higher = spatially varying edits')
    np.save(os.path.join(out_dir, 'global_lut33.npy'), lut)

    # tone curves
    def curve(sel):
        pts = []
        for a in range(0, 100, 5):
            k = sel & (Lo[..., 0] >= a) & (Lo[..., 0] < a + 5)
            if k.sum() >= 300:
                pts.append([a + 2.5, r2(np.median(Lo[..., 0][k])), r2(np.median(Le[..., 0][k])), int(k.sum())])
        return pts
    R['tone_curve'] = dict(columns=['bin_center', 'L_orig_median', 'L_edit_median', 'n'],
                           background=curve(far & ground), sky=curve(sky), subject=curve(din > 6))

    # hue-wise chroma (background ground)
    hues = []
    for h0 in range(0, 360, 20):
        k = far & ground & (Co > 8) & (ho >= h0) & (ho < h0 + 20)
        if k.sum() >= 500:
            dh = ((he - ho + 180) % 360 - 180)[k]
            hues.append(dict(hue=[h0, h0 + 20], n=int(k.sum()), C_orig=r2(np.median(Co[k])), C_edit=r2(np.median(Ce[k])),
                             gain_median=r2(np.median(Ce[k] / Co[k]), 3), dh_deg=r2(np.median(dh)),
                             L_orig=r2(np.median(Lo[..., 0][k])), L_edit=r2(np.median(Le[..., 0][k]))))
    R['hue_chroma_background'] = hues

    # ---------- local edits: residual after global grade ----------
    res = Le - Lp
    dE = deltaE2000(Le, Lp)
    np.save(os.path.join(out_dir, 'residual_L.npy'), res[..., 0].astype(np.float32))
    ring = (dout > 0) & (dout <= 60) & ground
    R['local_residual'] = dict(dE00_far_background=r2(dE[far].mean()), dE00_ring_0_60px=r2(dE[ring].mean()),
                               dE00_subject=r2(dE[subj].mean()),
                               subject_dL_vs_global=r2(np.median(res[..., 0][din > 6])),
                               subject_dC_vs_global=r2(np.median((lch(Le)[0] - lch(Lp)[0])[din > 6])))

    # halo profile
    edges = [0, 2, 4, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 70, 80, 100, 120, 150, 200, 300]
    cx = xx[subj].mean() if subj.any() else W / 2
    def prof(extra):
        out = []
        for a, b in zip(edges[:-1], edges[1:]):
            k = (dout > a) & (dout <= b) & ground & border & extra
            out.append([a, b, int(k.sum()), r2(np.median(res[..., 0][k])) if k.sum() > 50 else None])
        return out
    allp = prof(np.ones_like(subj))
    def extent(p, thr):
        for a, b, n, v in p:
            if v is not None and v < thr:
                return a
        return None
    Cres = lch(Le)[0] - lch(Lp)[0]
    k10 = (dout > 0) & (dout <= 10) & ground
    R['halo'] = dict(columns=['from_px', 'to_px', 'n', 'median_residual_dL'],
                     profile=allp, profile_left=prof(xx < cx), profile_right=prof(xx >= cx),
                     peak_dL_0_2px=allp[0][3],
                     extent_px_dL_below_1=extent(allp, 1.0), extent_px_dL_below_0_5=extent(allp, 0.5),
                     extent_pct_of_width_dL_below_1=r2(100 * (extent(allp, 1.0) or 0) / W),
                     halo_area_index=r2(sum(max(v or 0, 0) * (b - a) for a, b, n, v in allp if b <= 100)),
                     ring_0_10px_dC=r2(np.median(Cres[k10])),
                     note='residual dL* outside the subject after removing the global grade. '
                          'A clean edit is ~0 beyond 2-4 px. halo_area_index = sum(dL*>0 x bin width) over 0-100 px')

    # ---------- spatial filtering: detail energy per scale ----------
    bands = [(0, 1), (1, 2), (2, 4), (4, 8), (8, 16), (16, 32)]
    Bo = {b: band(Lo[..., 0], *b) for b in bands}
    Be = {b: band(Le[..., 0], *b) for b in bands}
    Bp = {b: band(Lp[..., 0], *b) for b in bands}
    def ratios(sel, A, B):
        return [r2(np.sqrt((A[b][sel] ** 2).mean() / max((B[b][sel] ** 2).mean(), 1e-12)), 3) for b in bands]
    regs = {'far_background': far & ground, 'ring_0_30px': (dout > 0) & (dout <= 30) & ground,
            'subject_interior': din > 8, 'sky': sky & border}
    # per-depth strips of the ground (rows), because aggregate RMS is dominated by sharp foreground
    gy = np.where((ground & (dout > far_px)).any(1))[0]
    strips = []
    if len(gy):
        cuts = np.linspace(gy.min(), gy.max() + 1, 7).astype(int)
        for y0, y1 in zip(cuts[:-1], cuts[1:]):
            k = far & ground & (yy >= y0) & (yy < y1)
            if k.sum() > 5000:
                strips.append(dict(rows=[int(y0), int(y1)], n=int(k.sum()),
                                   edit_over_global_prediction=ratios(k, Be, Bp), edit_over_original=ratios(k, Be, Bo),
                                   residual_dL=r2(np.median(res[..., 0][k]))))
    R['detail_energy'] = dict(bands_sigma_px=[list(b) for b in bands], by_row_strip=strips,
                              edit_over_original={k: ratios(v, Be, Bo) for k, v in regs.items() if v.sum() > 1000},
                              edit_over_global_prediction={k: ratios(v, Be, Bp) for k, v in regs.items() if v.sum() > 1000},
                              note='>1 = sharpened/clarity, <1 = smoothed. edit_over_global_prediction removes '
                                   'contrast changes caused by the tone curve alone')

    # ---------- sky ----------
    if sky.sum() > 5000:
        rows = np.where(sky.any(1))[0]
        y0, y1 = rows.min() + 10, max(rows.min() + 20, int(np.percentile(rows, 80)))
        cols = sky[y0:y1].all(0)
        x0, x1 = (np.where(cols)[0].min(), np.where(cols)[0].max()) if cols.any() else (0, W)
        def comb(enc):
            g = np.round(enc[..., 1][sky] * 255).astype(int)
            lo, hi = np.percentile(g, 0.5), np.percentile(g, 99.5)
            hist = np.bincount(g, minlength=256)[int(lo):int(hi) + 1]
            return dict(levels=int(len(hist)), empty_levels=int((hist == 0).sum()))
        R['sky'] = dict(Lab_orig_median=[r2(v) for v in np.median(Lo[sky], 0)],
                        Lab_edit_median=[r2(v) for v in np.median(Le[sky], 0)],
                        C_orig=r2(np.median(Co[sky])), C_edit=r2(np.median(Ce[sky])),
                        noise_sigma_L_orig=r2(immerkaer_sigma(Lo[y0:y1, x0:x1, 0]), 3),
                        noise_sigma_L_edit=r2(immerkaer_sigma(Le[y0:y1, x0:x1, 0]), 3),
                        noise_sigma_L_global_pred=r2(immerkaer_sigma(Lp[y0:y1, x0:x1, 0]), 3),
                        histogram_G_orig=comb(o['enc']), histogram_G_edit=comb(e['enc']))

    # ---------- subject (generic) ----------
    inner = din > 6
    skin = inner & (ho > 35) & (ho < 75) & (Co > 10) & (Lo[..., 0] > 18) & (Lo[..., 0] < 75)
    def rstat(k):
        if k.sum() < 200:
            return None
        dh = ((he - ho + 180) % 360 - 180)[k]
        return dict(n=int(k.sum()), Lab_orig=[r2(v) for v in Lo[k].mean(0)], Lab_edit=[r2(v) for v in Le[k].mean(0)],
                    dL=r2((Le[..., 0] - Lo[..., 0])[k].mean()), dC=r2((Ce - Co)[k].mean()), dh_deg=r2(dh.mean()),
                    dE00_mean=r2(deltaE2000(Lo[k], Le[k]).mean()))
    # mask coverage: lift on dark-to-mid subject pixels (orig L* 10-40) per 100-row band
    tone = inner & (Lo[..., 0] > 10) & (Lo[..., 0] < 40)
    dLs = Le[..., 0] - Lo[..., 0]
    bands_cov = []
    if tone.sum() > 1000:
        ys = np.where(tone.any(1))[0]
        for y0 in range(int(ys.min()), int(ys.max()) + 1, 100):
            k = tone & (yy >= y0) & (yy < y0 + 100)
            if k.sum() >= 1000:
                bands_cov.append([y0, y0 + 100, int(k.sum()), r2(np.median(dLs[k]))])
    med = float(np.median(dLs[tone])) if tone.sum() > 1000 else 0.0
    worst = max((abs(b[3] - med) for b in bands_cov), default=0.0)
    R['mask_coverage'] = dict(columns=['from_row', 'to_row', 'n', 'median_dL_vs_original'], bands=bands_cov,
                              subject_median_dL=r2(med), worst_band_deviation=r2(worst),
                              worst_relative=r2(worst / abs(med), 3) if abs(med) >= 1 else None,
                              note='subject pixels with original L* 10-40; a lift that stops mid-body shows as one '
                                   'band far from the median (relative deviation > 0.5)')
    R['subject'] = dict(skin_tones=rstat(skin), shadows=rstat(inner & (Lo[..., 0] < 30)),
                        midtones=rstat(inner & (Lo[..., 0] >= 30) & (Lo[..., 0] < 70)),
                        highlights=rstat(inner & (Lo[..., 0] >= 70)),
                        texture_by_tone={nm: dict(n=int(k.sum()), fine_0_1px=ratios(k, Be, Bo)[0], mid_4_8px=ratios(k, Be, Bo)[3])
                                         for nm, k in [('shadows_L<30', inner & (Lo[..., 0] < 30)),
                                                       ('mids_30-70', inner & (Lo[..., 0] >= 30) & (Lo[..., 0] < 70)),
                                                       ('highlights_L>=70', inner & (Lo[..., 0] >= 70))] if k.sum() > 1000},
                        note='texture ratios are edit/original RMS of band-pass L*; <1 = detail lost')

    # ---------- residual visualization ----------
    rl = cv2.GaussianBlur(res[..., 0].astype(np.float32), (0, 0), 3)
    v = np.clip((rl + 15) / 30, 0, 1)
    cm = cv2.applyColorMap((v * 255).astype(np.uint8), cv2.COLORMAP_TWILIGHT_SHIFTED)
    cs, _ = cv2.findContours(subj.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cv2.drawContours(cm, cs, -1, (255, 255, 255), 1)
    cv2.imwrite(os.path.join(out_dir, 'residual_map.png'), cm)

    with open(os.path.join(out_dir, 'measurements.json'), 'w') as f:
        json.dump(R, f, indent=1)
    return R, dict(Lo=Lo, Le=Le, Lp=Lp, res=res, dout=dout, din=din, sky=sky, far=far)


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('original'); ap.add_argument('edited')
    ap.add_argument('--matte', help='subject matte PNG (white = subject)')
    ap.add_argument('--seg-model', help='ONNX salient/person model (BiRefNet 1024 or U2Net 320) if no matte')
    ap.add_argument('--seg-size', type=int, default=1024)
    ap.add_argument('--out', default='forensics_out')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    W, H = Image.open(a.original).size
    if a.matte:
        m = np.asarray(Image.open(a.matte).convert('L').resize((W, H), Image.BILINEAR), np.float32) / 255
    elif a.seg_model:
        from fx_segment import run_model
        from fx_color import srgb_oetf
        d = load(a.original)
        m = run_model(a.seg_model, np.clip(srgb_oetf(d['xyz'] @ np.linalg.inv(SRGB_M_D50).T), 0, 1), a.seg_size)
        cv2.imwrite(os.path.join(a.out, 'matte.png'), (m * 255).astype(np.uint8))
    else:
        raise SystemExit('give --matte or --seg-model')
    R, _ = analyze(a.original, a.edited, m, a.out)
    h = R['halo']
    print(json.dumps(dict(halo_peak_dL=h['peak_dL_0_2px'], halo_extent_px=h['extent_px_dL_below_1'],
                          global_fit=R['global_grade_fit'], subject_texture=R['subject']['texture_by_tone']), indent=1))
