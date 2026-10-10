"""case_study_checks.py — photo-specific checks for samples/original_p3.jpg (2212 x 1659 px).

Coordinates below are for THIS sample only (shirt logo, plain shirt patch, face/arms split).
They exist to prove the generic metrics agree with what a person sees. For other photos,
use edit_forensics.py (generic) and add your own patches.

Usage: python case_study_checks.py ORIGINAL OUTPUT [OUTPUT ...] --matte MATTE [--json out.json]
"""
import argparse, json
import numpy as np, cv2
from PIL import Image
from scipy import ndimage as ndi
from fx_color import load, xyz_to_lab, lch

LOGO = (1126, 1202, 758, 778)        # x0, x1, y0, y1 of the chest logo strip
LOGO_BG = (1090, 1125, 740, 800)     # plain shirt left of the logo
SHIRT_FOLD = (1080, 1230, 820, 1060)  # plain shirt patch for fold shading (x0, x1, y0, y1)
SHIRT_TEX = (1105, 1205, 845, 1035)   # tighter plain patch for texture-by-scale


def band(L, s1, s2):
    L = L.astype(np.float32)
    a = cv2.GaussianBlur(L, (0, 0), s1) if s1 > 0 else L
    return a - cv2.GaussianBlur(L, (0, 0), s2)


def regions(Lo, matte):
    H, W = Lo.shape[:2]
    yy = np.mgrid[:H, :W][0]
    din = ndi.distance_transform_edt(matte > 0.5)
    inner = din > 6
    C, h = lch(Lo)
    L = Lo[..., 0]
    skin = inner & (h > 35) & (h < 75) & (C > 10) & (L > 18) & (L < 70)
    return dict(face=skin & (yy < 700), arms=skin & (yy >= 700),
                shirt=inner & (L > 68) & (C < 25) & (yy > 620) & (yy < 1100),
                pants=inner & (yy > 1090) & (L < 30), hair=inner & (yy < 760) & (L < 22) & ~skin)


def check(orig, out, matte):
    Lo = xyz_to_lab(load(orig)['xyz'])
    d = load(out)
    Le = xyz_to_lab(d['xyz'])
    r = {}
    x0, x1, y0, y1 = LOGO
    bx0, bx1, by0, by1 = LOGO_BG
    sub_o = Lo[y0:y1, x0:x1, 0]
    lens = Lo[y0:y1, x0:x1, 2] < 8                          # sunglasses lens (bluish) excluded
    text = (sub_o < np.median(Lo[by0:by1, bx0:bx1, 0]) - 6) & ~lens
    ring = cv2.dilate(text.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool) & ~text & ~lens
    for nm, L in (('orig', Lo), ('out', Le)):
        s = L[y0:y1, x0:x1, 0]
        r[f'logo_contrast_{nm}'] = round(float(np.median(s[ring]) - np.median(s[text])), 2)
    sx0, sx1, sy0, sy1 = SHIRT_FOLD
    for nm, L in (('orig', Lo), ('out', Le)):
        s = L[sy0:sy1, sx0:sx1, 0]
        r[f'shirt_fold_rms_{nm}'] = round(float(band(s, 3, 20)[25:-25, 25:-25].std()), 3)
        r[f'shirt_L_{nm}'] = round(float(s.mean()), 2)
    tx0, tx1, ty0, ty1 = SHIRT_TEX
    k = np.zeros(Lo.shape[:2], bool); k[ty0:ty1, tx0:tx1] = True
    scales = [(0, 1), (1, 2), (2, 4), (4, 8), (3, 20)]
    r['shirt_texture_ratio_by_scale'] = {f'{a}-{b}px': round(float(np.sqrt((band(Le[..., 0], a, b)[k] ** 2).mean()
                                         / (band(Lo[..., 0], a, b)[k] ** 2).mean())), 3) for a, b in scales}
    Co, ho = lch(Lo); Ce, he = lch(Le)
    for nm, k in regions(Lo, matte).items():
        r[f'{nm}_dL'] = round(float((Le[..., 0] - Lo[..., 0])[k].mean()), 2)
        r[f'{nm}_dC'] = round(float((Ce - Co)[k].mean()), 2)
    return r


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('original'); ap.add_argument('outputs', nargs='+')
    ap.add_argument('--matte', required=True); ap.add_argument('--json')
    a = ap.parse_args()
    W, H = Image.open(a.original).size
    m = np.asarray(Image.open(a.matte).convert('L').resize((W, H)), np.float32) / 255
    res = {o: check(a.original, o, m) for o in a.outputs}
    for o, v in res.items():
        print(o, json.dumps(v))
    if a.json:
        json.dump(res, open(a.json, 'w'), indent=1)
