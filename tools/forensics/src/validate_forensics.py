"""validate_forensics.py — prove the forensics tool measures what it claims.

Builds synthetic edits with KNOWN ground truth from a real photo:
  synth_halo.jpg   = known global LUT + subject lift through a Gaussian-feathered mask (sigma px)
  synth_clean.jpg  = same global LUT + same lift through an edge-aware refined matte
Both are JPEG q85 4:2:0 (same damage as typical phone/app exports). Then runs
edit_forensics.analyze() and compares the recovered halo profile to the true one.

Usage: python validate_forensics.py ORIGINAL MATTE LUT33.npy OUTDIR [--sigma 10] [--lift 0.6]
"""
import argparse, json, os
import numpy as np, cv2
from PIL import Image, ImageCms
from fx_color import load, xyz_to_lab, SRGB_M_D50, srgb_eotf, srgb_oetf
from fx_lut import apply_lut
from fx_ops import guided_filter_color
from scipy import ndimage as ndi
import edit_forensics as ef


def true_profile(Lab_out, Lab_global, dout, ground, edges):
    res = Lab_out[..., 0] - Lab_global[..., 0]
    out = []
    for a, b in zip(edges[:-1], edges[1:]):
        k = (dout > a) & (dout <= b) & ground
        out.append(round(float(np.median(res[k])), 2) if k.sum() > 50 else None)
    return out


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('original'); ap.add_argument('matte'); ap.add_argument('lut'); ap.add_argument('out')
    ap.add_argument('--sigma', type=float, default=10); ap.add_argument('--lift', type=float, default=0.6)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    o = load(a.original)
    H, W = o['enc'].shape[:2]
    m = np.asarray(Image.open(a.matte).convert('L').resize((W, H)), np.float32) / 255
    binm = (m > 0.5).astype(np.float32)
    lut = np.load(a.lut)
    G = np.clip(apply_lut(lut, o['enc']), 0, 1)              # known global grade, sRGB encoded
    linG = srgb_eotf(G)
    guide = G.astype(np.float32)
    variants = {
        'synth_halo': cv2.GaussianBlur(binm, (0, 0), a.sigma),
        'synth_clean': np.clip(guided_filter_color(guide, m, 6, 1e-4), 0, 1),
    }
    srgb_icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    dout = ndi.distance_transform_edt(binm < 0.5)
    yy = np.mgrid[:H, :W][0]
    Lab_o = xyz_to_lab(o['xyz'])
    hz = ef.detect_horizon(Lab_o)
    ground = ~(yy < hz[None, :] - 6)
    edges = [0, 2, 4, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 70, 80, 100, 120, 150, 200, 300]
    Lab_global = xyz_to_lab(linG @ SRGB_M_D50.T)
    report = {}
    for name, A in variants.items():
        lin = np.clip(linG * np.exp2(a.lift * A)[..., None], 0, 1)
        Lab_true = xyz_to_lab(lin @ SRGB_M_D50.T)
        enc = (np.clip(srgb_oetf(lin), 0, 1) * 255 + 0.5).astype(np.uint8)
        path = os.path.join(a.out, name + '.jpg')
        Image.fromarray(enc).save(path, quality=85, subsampling=2, icc_profile=srgb_icc)
        R, _ = ef.analyze(a.original, path, m, os.path.join(a.out, name))
        meas = [v for _, _, _, v in R['halo']['profile']]
        tru = true_profile(Lab_true, Lab_global, dout, ground, edges)
        err = [abs(x - y) for x, y in zip(meas, tru) if x is not None and y is not None]
        report[name] = dict(true_profile=tru, measured_profile=meas, max_abs_error_dL=round(max(err), 2),
                            mean_abs_error_dL=round(float(np.mean(err)), 2),
                            measured_extent_px=R['halo']['extent_px_dL_below_1'],
                            true_extent_px=next((e0 for e0, v in zip(edges[:-1], tru) if v is not None and v < 1.0), None),
                            global_fit_dE00_mean=R['global_grade_fit']['heldout_dE00_mean'])
        print(name, json.dumps({k: v for k, v in report[name].items() if 'profile' not in k}))
    json.dump(dict(edges=edges, sigma=a.sigma, lift_stops=a.lift, results=report),
              open(os.path.join(a.out, 'validation.json'), 'w'), indent=1)
