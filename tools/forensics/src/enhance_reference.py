"""enhance_reference.py — halo-free, color-managed reference enhancement.

Proof-of-technique pipeline that fixes every defect measured in the reference edit:
  * decodes with the file's own ICC profile (Display P3 stays P3), processes in float
  * subject lift through an edge-aware refined matte (no feathered spill -> no halo)
  * shadow-weighted subject lift (protects highlights, keeps logo/fold texture)
  * edge-aware clarity (guided-filter base/detail), never applied to the sky
  * vibrance in Oklab with skin-hue protection, sky deepening through a refined sky matte
  * soft gamut compression (never clips a channel), TPDF dither on 8-bit encode
  * writes Display P3 (with the source ICC) and an sRGB copy for unmanaged platforms

Usage:
  python enhance_reference.py original.jpg subject_matte.png out_prefix [--horizon horizon.npy]
The subject matte is any 0..255 grayscale person/salient-object matte (BiRefNet, etc.).
"""
import argparse, io, json
import numpy as np, cv2
from PIL import Image, ImageCms
from fx_color import load, SRGB_M_D50, srgb_oetf, srgb_eotf, xyz_to_lab
from fx_ops import (guided_filter, guided_filter_color, lin_srgb_to_oklab, oklab_to_lin_srgb,
                    soft_gamut_compress, encode_8bit)

DEFAULTS = dict(
    ev_background=-0.45,     # stops, ground (sky handled by its own matte)
    ev_subject=+0.35,        # stops, subject shadows/midtones (through the refined matte)
    subject_highlight_protect=1.0,   # 0..1, subject lift fades to 0 above ~+1.5 stops (bright fabric untouched)
    contrast=1.15,           # midtone slope of the BASE layer around the pivot, in log2 space
    pivot_stops=-0.3,        # pivot for contrast, in stops relative to 18% grey
    hl_protect_from=0.6,     # contrast fades out between these base levels (stops) ...
    hl_protect_to=1.6,       # ... so highlights are not pushed up
    toe_from=-3.0,           # contrast fades in between these base levels (stops) ...
    toe_to=-1.5,             # ... so deep shadows (hair, black pants) are not crushed
    shoulder_start=2.1,      # stops above grey; only speculars get compressed
    clarity_bg=0.30,         # extra detail gain on ground (1 + k)
    clarity_subject=0.12,    # small, keeps texture without crunch
    clarity_radius=28,       # px at ~3.7 MP; scale with image size
    vibrance=0.30,
    skin_protect=0.85,       # 0..1 fraction of vibrance removed on skin hues
    skin_chroma=0.0,         # extra skin chroma (keep ~0: the brightening already adds Lab chroma)
    sky_darken=0.06,         # Oklab L
    sky_chroma=0.55,         # +55% chroma in sky
    warmth=0.004,            # Oklab b+ shift on ground, skin-protected (golden-hour feel)
    matte_r=6, matte_eps=1e-4,
)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def refine_matte(guide_rgb, coarse, r, eps):
    """Edge-aware matte refinement: color guided filter, then clip. Follows true edges, no outward feather."""
    a = guided_filter_color(guide_rgb, coarse.astype(np.float32), r, eps)
    return np.clip(a, 0, 1)


def sky_matte(lab, guide_rgb, horizon=None):
    H, W = lab.shape[:2]
    yy = np.mgrid[:H, :W][0]
    if horizon is None:
        b = cv2.GaussianBlur(lab[..., 2].astype(np.float32), (0, 0), 2)
        land = b > 8
        horizon = np.argmax(land, axis=0).astype(float)
        from scipy import ndimage as ndi
        horizon = ndi.median_filter(horizon, size=41)
    coarse = (yy < horizon[None, :] - 2).astype(np.float32)
    # bluish + bright pixels only
    coarse *= ((lab[..., 2] < 4) & (lab[..., 0] > 60)).astype(np.float32)
    return refine_matte(guide_rgb, coarse, 16, 1e-3), horizon


def enhance(path, matte_path, p=DEFAULTS, horizon=None):
    d = load(path)
    lin = d['xyz'] @ np.linalg.inv(SRGB_M_D50).T         # linear sRGB primaries, unclipped (P3 colors kept)
    H, W = lin.shape[:2]
    guide = np.clip(srgb_oetf(np.clip(lin, 0, None)), 0, 1).astype(np.float32)
    lab = xyz_to_lab(d['xyz'])

    coarse = np.asarray(Image.open(matte_path).convert('L').resize((W, H), Image.BILINEAR), np.float32) / 255
    A = refine_matte(guide, coarse, p['matte_r'], p['matte_eps'])
    S, horizon = sky_matte(lab, guide, horizon)
    S = S * (1 - A)

    # ---- luminance in stops ----
    Y = np.clip(lin @ np.array([0.2126, 0.7152, 0.0722]), 1e-5, None)
    s = np.log2(Y / 0.18)

    # Base/detail split with an edge-aware filter (Durand & Dorsey 2002 idea, guided filter instead of bilateral).
    # Every tonal move (exposure, contrast, highlight shoulder) is applied to the BASE only, then the detail
    # layer is added back with gain >= 1. This is what keeps the shirt folds and the logo legible.
    base = guided_filter(s.astype(np.float32), s.astype(np.float32), p['clarity_radius'], 0.25)
    detail = s - base

    # region exposure through edge-aware mattes: subject lift fades out in highlights (the shirt stays put),
    # background goes down a little so the subject separates without any halo
    w_shadow = 1 - p['subject_highlight_protect'] * smoothstep(-0.3, 1.5, base)
    ev = p['ev_background'] * (1 - A) * (1 - S) + p['ev_subject'] * A * w_shadow
    b2 = base + ev

    # midtone contrast with protected endpoints: highlights and deep shadows are not stretched,
    # so nothing gets pushed into clipping (the defect that bleaches bright fabric)
    w = (1 - smoothstep(p['hl_protect_from'], p['hl_protect_to'], b2)) * smoothstep(p['toe_from'], p['toe_to'], b2)
    b2 = b2 + (p['contrast'] - 1) * (b2 - p['pivot_stops']) * w
    x = b2 - p['shoulder_start']                                   # last-resort shoulder for speculars
    b2 = np.where(x > 0, p['shoulder_start'] + x / (1 + x / 0.8), b2)

    # detail gain: clarity on ground, gentle on subject, exactly 1.0 on sky (never amplify sky noise)
    k = p['clarity_bg'] * (1 - A) * (1 - S) + p['clarity_subject'] * A
    s2 = b2 + (1 + k) * detail

    Y2 = 0.18 * np.exp2(s2)
    lin2 = lin * (Y2 / Y)[..., None]

    # ---- color in Oklab ----
    ok = lin_srgb_to_oklab(lin2)
    C = np.hypot(ok[..., 1], ok[..., 2])
    h = np.degrees(np.arctan2(ok[..., 2], ok[..., 1])) % 360
    skin = np.exp(-((((h - 42 + 180) % 360) - 180) / 18) ** 2) * np.clip(C / 0.03, 0, 1)
    v = p['vibrance'] * (1 - p['skin_protect'] * skin) * (1 - np.clip(C / 0.20, 0, 1))
    gain = 1 + v + p['sky_chroma'] * S + p['skin_chroma'] * skin * A
    ok[..., 1] *= gain; ok[..., 2] *= gain
    ok[..., 0] -= p['sky_darken'] * S
    ok[..., 2] += p['warmth'] * (1 - S) * (1 - skin * A) * np.clip(ok[..., 0] / 0.5, 0, 1)

    # ---- outputs ----
    M_src = d['M']
    to_src_lin = lambda rgb: (rgb @ SRGB_M_D50.T) @ np.linalg.inv(M_src).T
    to_srgb_lin = lambda rgb: rgb
    out = {}
    ok_src = soft_gamut_compress(ok, to_src_lin, knee=0.9)
    out['native'] = srgb_oetf(np.clip(to_src_lin(oklab_to_lin_srgb(ok_src)), 0, 1))  # P3 file uses sRGB TRC
    ok_s = soft_gamut_compress(ok, to_srgb_lin, knee=0.85)
    out['srgb'] = srgb_oetf(np.clip(oklab_to_lin_srgb(ok_s), 0, 1))
    return out, d, A, S


def save_jpeg(enc01, path, icc, q=95):
    Image.fromarray(encode_8bit(enc01)).save(path, quality=q, subsampling=0, icc_profile=icc)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('original'); ap.add_argument('matte'); ap.add_argument('out_prefix')
    ap.add_argument('--horizon', default=None)
    ap.add_argument('--params', default=None, help='JSON file overriding DEFAULTS')
    a = ap.parse_args()
    p = dict(DEFAULTS)
    if a.params:
        p.update(json.load(open(a.params)))
    hz = np.load(a.horizon) if a.horizon else None
    out, d, A, S = enhance(a.original, a.matte, p, hz)
    src_icc = Image.open(a.original).info.get('icc_profile')
    srgb_icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    save_jpeg(out['native'], a.out_prefix + '_p3.jpg', src_icc)
    save_jpeg(out['srgb'], a.out_prefix + '_srgb.jpg', srgb_icc)
    cv2.imwrite(a.out_prefix + '_matte_refined.png', (A * 255).astype(np.uint8))
    cv2.imwrite(a.out_prefix + '_sky_matte.png', (S * 255).astype(np.uint8))
    json.dump(p, open(a.out_prefix + '_params.json', 'w'), indent=2)
    print('wrote', a.out_prefix + '_p3.jpg', a.out_prefix + '_srgb.jpg')
