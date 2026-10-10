"""export_look_cube.py — "copy this look": fit the GLOBAL grade of any before/after pair as a .cube LUT.

Both images are first read through their own ICC profiles and converted to sRGB, so the LUT is
sRGB-in -> sRGB-out and can be applied to any sRGB image (convert P3 photos to sRGB first).
Pass the subject matte to keep local edits (relights, masks) out of the fit: only the global
grade transfers through a LUT; local edits never do.

Usage: python export_look_cube.py BEFORE AFTER out.cube [--matte subject.png] [--size 33]
Prints the held-out fit error (dE00). Under ~1 = the look is essentially global; well above = the
edit also did spatial work the LUT cannot carry.
"""
import argparse
import numpy as np
from PIL import Image
from scipy import ndimage as ndi
from fx_color import load, xyz_to_lab, deltaE2000, SRGB_M_D50, srgb_oetf, srgb_eotf
from fx_lut import fit_lut, apply_lut, write_cube


def to_srgb01(d):
    return np.clip(srgb_oetf(d['xyz'] @ np.linalg.inv(SRGB_M_D50).T), 0, 1)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('before'); ap.add_argument('after'); ap.add_argument('out')
    ap.add_argument('--matte'); ap.add_argument('--size', type=int, default=33)
    a = ap.parse_args()
    b, e = load(a.before), load(a.after)
    S, T = to_srgb01(b), to_srgb01(e)
    H, W = S.shape[:2]
    keep = np.ones((H, W), bool)
    if a.matte:
        m = np.asarray(Image.open(a.matte).convert('L').resize((W, H)), np.float32) / 255 > 0.5
        keep = ndi.distance_transform_edt(~m) > max(150, int(0.135 * W))
    keep[:4, :] = keep[-4:, :] = False; keep[:, :4] = keep[:, -4:] = False
    idx = np.flatnonzero(keep); rng = np.random.default_rng(0); rng.shuffle(idx)
    n = min(300000, int(0.8 * len(idx)))
    tr, te = idx[:n], idx[n:n + 150000]
    lut = fit_lut(S.reshape(-1, 3)[tr], T.reshape(-1, 3)[tr], N=a.size, lam=0.1)
    pred = apply_lut(lut, S.reshape(-1, 3)[te])
    lab_p = xyz_to_lab(srgb_eotf(pred) @ SRGB_M_D50.T)
    lab_t = xyz_to_lab(srgb_eotf(T.reshape(-1, 3)[te]) @ SRGB_M_D50.T)
    err = deltaE2000(lab_p, lab_t)
    write_cube(lut, a.out, title='look from ' + a.after.split('/')[-1])
    print('wrote %s (%d^3). held-out dE00 mean %.2f, median %.2f, p95 %.2f'
          % (a.out, a.size, err.mean(), np.median(err), np.percentile(err, 95)))
