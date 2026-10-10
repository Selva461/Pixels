"""Edge-aware building blocks: guided filter, Oklab, gamut compression, dithered encode."""
import numpy as np, cv2

# ---------- guided filter (He, Sun, Tang: ECCV 2010 / TPAMI 2013) ----------
def _box(x, r):
    return cv2.boxFilter(x, -1, (2 * r + 1, 2 * r + 1), normalize=True, borderType=cv2.BORDER_REFLECT)


def guided_filter(I, p, r, eps):
    """Gray guide I (H,W) float32, input p (H,W) float32. Edge-preserving smoothing of p that follows edges of I."""
    I = I.astype(np.float32); p = p.astype(np.float32)
    mI, mp = _box(I, r), _box(p, r)
    cov = _box(I * p, r) - mI * mp
    var = _box(I * I, r) - mI * mI
    a = cov / (var + eps)
    b = mp - a * mI
    return _box(a, r) * I + _box(b, r)


def guided_filter_color(I, p, r, eps):
    """Color guide I (H,W,3) float32 — better edges for mattes (hair, low-contrast boundaries)."""
    I = I.astype(np.float32); p = p.astype(np.float32)
    H, W = p.shape
    mI = [_box(I[..., c], r) for c in range(3)]
    mp = _box(p, r)
    cov = [_box(I[..., c] * p, r) - mI[c] * mp for c in range(3)]
    var = np.empty((H, W, 3, 3), np.float32)
    for i in range(3):
        for j in range(i, 3):
            v = _box(I[..., i] * I[..., j], r) - mI[i] * mI[j]
            var[..., i, j] = v; var[..., j, i] = v
    var += eps * np.eye(3, dtype=np.float32)
    a = np.linalg.solve(var, np.stack(cov, -1)[..., None])[..., 0]  # (H,W,3)
    b = mp - sum(a[..., c] * mI[c] for c in range(3))
    return sum(_box(a[..., c], r) * I[..., c] for c in range(3)) + _box(b, r)


# ---------- Oklab (Ottosson 2020), from/to LINEAR sRGB (unclipped allowed) ----------
_M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                [0.2119034982, 0.6806995451, 0.1073969566],
                [0.0883024619, 0.2817188376, 0.6299787005]])
_M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                [1.9779984951, -2.4285922050, 0.4505937099],
                [0.0259040371, 0.7827717662, -0.8086757660]])
_M1i = np.linalg.inv(_M1); _M2i = np.linalg.inv(_M2)


def lin_srgb_to_oklab(rgb):
    lms = rgb @ _M1.T
    return np.cbrt(lms) @ _M2.T


def oklab_to_lin_srgb(lab):
    lms_ = lab @ _M2i.T
    return (lms_ ** 3) @ _M1i.T


# ---------- soft gamut compression in Oklab (constant L and hue) ----------
def chroma_limit(L, h_cos, h_sin, to_target_lin, iters=16, cmax=0.5):
    """Largest Oklab chroma at (L,hue) that stays inside [0,1]^3 of the target RGB (binary search)."""
    lo = np.zeros_like(L); hi = np.full_like(L, cmax)
    for _ in range(iters):
        mid = (lo + hi) / 2
        lab = np.stack([L, mid * h_cos, mid * h_sin], -1)
        rgb = to_target_lin(oklab_to_lin_srgb(lab))
        ok = np.all((rgb >= -1e-6) & (rgb <= 1 + 1e-6), axis=-1)
        lo = np.where(ok, mid, lo); hi = np.where(ok, hi, mid)
    return lo


def soft_gamut_compress(lab, to_target_lin, knee=0.85):
    """Compress chroma smoothly above `knee` x the gamut boundary, never clip channels."""
    L = np.clip(lab[..., 0], 0, 1)
    C = np.hypot(lab[..., 1], lab[..., 2])
    hc = np.where(C > 1e-9, lab[..., 1] / np.maximum(C, 1e-9), 1.0)
    hs = np.where(C > 1e-9, lab[..., 2] / np.maximum(C, 1e-9), 0.0)
    Cm = chroma_limit(L, hc, hs, to_target_lin)
    r = C / np.maximum(Cm, 1e-9)
    # rational knee: identity below knee, asymptotic to 1.0 above
    over = np.maximum(r - knee, 0)
    r2 = np.where(r > knee, knee + (1 - knee) * over / (over + (1 - knee)), r)
    C2 = r2 * Cm
    return np.stack([L, C2 * hc, C2 * hs], -1)


# ---------- encode with dithering ----------
def encode_8bit(enc01, seed=0):
    """Quantize 0..1 floats to uint8 with TPDF dither (+-1 LSB) to avoid banding in smooth gradients."""
    rng = np.random.default_rng(seed)
    d = (rng.random(enc01.shape) - rng.random(enc01.shape))  # triangular, in LSB
    return np.clip(np.round(enc01 * 255 + d), 0, 255).astype(np.uint8)
