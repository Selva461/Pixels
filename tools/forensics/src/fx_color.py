"""Color-managed loading for photo-edit forensics.

Decodes an image using ITS OWN embedded ICC profile (matrix/TRC profiles such as
sRGB, Display P3, Adobe RGB) into CIE XYZ (D50 PCS) and CIELAB, in float.
Never compare raw RGB numbers of two files with different profiles.
"""
import io, struct
import numpy as np
from PIL import Image

D50 = np.array([0.96422, 1.0, 0.82521])

# sRGB colorants adapted to D50 (ICC PCS), as written by standard sRGB profiles
SRGB_M_D50 = np.array([[0.436065674, 0.385147095, 0.143066406],
                       [0.222488403, 0.716873169, 0.060607910],
                       [0.013916016, 0.097076416, 0.714096069]])


def _s15f16(b):
    return struct.unpack('>i', b)[0] / 65536.0


def _parse_trc(d):
    typ = d[:4]
    if typ == b'curv':
        n = struct.unpack('>I', d[8:12])[0]
        if n == 0:
            return lambda x: x
        if n == 1:
            g = struct.unpack('>H', d[12:14])[0] / 256.0
            return lambda x: np.power(np.clip(x, 0, None), g)
        tbl = np.array(struct.unpack('>%dH' % n, d[12:12 + 2 * n]), dtype=np.float64) / 65535.0
        xs = np.linspace(0, 1, n)
        return lambda x: np.interp(x, xs, tbl)
    if typ == b'para':
        ft = struct.unpack('>H', d[8:10])[0]
        np_ = {0: 1, 1: 3, 2: 4, 3: 5, 4: 7}[ft]
        p = [_s15f16(d[12 + 4 * k:16 + 4 * k]) for k in range(np_)]
        g = p[0]
        if ft == 0:
            return lambda x: np.power(np.clip(x, 0, None), g)
        a, b = p[1], p[2]
        if ft == 1:
            return lambda x: np.where(x >= -b / a, np.power(np.clip(a * x + b, 0, None), g), 0.0)
        if ft == 2:
            c = p[3]
            return lambda x: np.where(x >= -b / a, np.power(np.clip(a * x + b, 0, None), g) + c, c)
        if ft == 3:
            c, dd = p[3], p[4]
            return lambda x: np.where(x >= dd, np.power(np.clip(a * x + b, 0, None), g), c * x)
        c, dd, e, f = p[3], p[4], p[5], p[6]
        return lambda x: np.where(x >= dd, np.power(np.clip(a * x + b, 0, None), g) + e, c * x + f)
    raise ValueError('unsupported TRC type %r' % typ)


def parse_icc(raw):
    """Return dict(desc, M (RGB->XYZ_D50), trc=[f_r,f_g,f_b]) for a matrix/TRC RGB profile."""
    n = struct.unpack('>I', raw[128:132])[0]
    tags = {}
    for i in range(n):
        sig, off, size = struct.unpack('>4sII', raw[132 + 12 * i:144 + 12 * i])
        tags[sig.decode('latin1')] = raw[off:off + size]
    for t in ('rXYZ', 'gXYZ', 'bXYZ', 'rTRC', 'gTRC', 'bTRC'):
        if t not in tags:
            raise ValueError('not a matrix/TRC profile (missing %s); use a CMM such as LittleCMS' % t)
    cols = []
    for t in ('rXYZ', 'gXYZ', 'bXYZ'):
        d = tags[t]
        cols.append([_s15f16(d[8 + 4 * k:12 + 4 * k]) for k in range(3)])
    M = np.array(cols).T  # columns = colorants
    desc = ''
    if 'desc' in tags:
        d = tags['desc']
        if d[:4] == b'mluc':
            cnt = struct.unpack('>I', d[8:12])[0]
            if cnt:
                ln, off = struct.unpack('>II', d[20:28])
                desc = d[off:off + ln].decode('utf-16-be', 'ignore')
        elif d[:4] == b'desc':
            ln = struct.unpack('>I', d[8:12])[0]
            desc = d[12:12 + ln].split(b'\0')[0].decode('latin1', 'ignore')
    return dict(desc=desc, M=M, trc=[_parse_trc(tags[t]) for t in ('rTRC', 'gTRC', 'bTRC')])


def srgb_eotf(v):
    v = np.asarray(v, dtype=np.float64)
    return np.where(v <= 0.04045, v / 12.92, np.power((np.clip(v, 0, None) + 0.055) / 1.055, 2.4))


def srgb_oetf(l):
    l = np.asarray(l, dtype=np.float64)
    return np.where(l <= 0.0031308, 12.92 * l, 1.055 * np.power(np.clip(l, 0, None), 1 / 2.4) - 0.055)


def load(path):
    """Load image -> dict(enc (H,W,3 float 0..1 as stored), lin, xyz_d50, profile_desc, M)."""
    im = Image.open(path)
    im.load()
    icc = im.info.get('icc_profile')
    rgb = np.asarray(im.convert('RGB'), dtype=np.float64) / 255.0
    if icc:
        prof = parse_icc(icc)
    else:  # untagged -> assume sRGB (the web default)
        prof = dict(desc='(untagged, assumed sRGB)', M=SRGB_M_D50, trc=[srgb_eotf] * 3)
    lin = np.stack([prof['trc'][c](rgb[..., c]) for c in range(3)], axis=-1)
    xyz = lin @ prof['M'].T
    return dict(enc=rgb, lin=lin, xyz=xyz, desc=prof['desc'], M=prof['M'], trc=prof['trc'], size=im.size,
                icc=icc, quant=getattr(im, 'quantization', None), fmt=im.format)


def xyz_to_lab(xyz, white=D50):
    t = xyz / white
    e = 216 / 24389
    k = 24389 / 27
    f = np.where(t > e, np.cbrt(t), (k * t + 16) / 116)
    L = 116 * f[..., 1] - 16
    a = 500 * (f[..., 0] - f[..., 1])
    b = 200 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], axis=-1)


def lab_to_xyz(lab, white=D50):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16) / 116
    fx = fy + a / 500
    fz = fy - b / 200
    e = 216 / 24389
    k = 24389 / 27
    xr = np.where(fx ** 3 > e, fx ** 3, (116 * fx - 16) / k)
    yr = np.where(L > k * e, fy ** 3, L / k)
    zr = np.where(fz ** 3 > e, fz ** 3, (116 * fz - 16) / k)
    return np.stack([xr, yr, zr], axis=-1) * white


def xyz_to_srgb_lin(xyz):
    return xyz @ np.linalg.inv(SRGB_M_D50).T


def to_srgb_display(d):
    """Colorimetric conversion of a loaded image to sRGB-encoded floats (unclipped)."""
    return srgb_oetf(xyz_to_srgb_lin(d['xyz']))


def lch(lab):
    C = np.hypot(lab[..., 1], lab[..., 2])
    h = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    return C, h


def deltaE2000(lab1, lab2):
    """CIEDE2000 (Sharma et al. 2005 formulation), vectorized."""
    L1, a1, b1 = lab1[..., 0], lab1[..., 1], lab1[..., 2]
    L2, a2, b2 = lab2[..., 0], lab2[..., 1], lab2[..., 2]
    C1 = np.hypot(a1, b1); C2 = np.hypot(a2, b2)
    Cb = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cb ** 7 / (Cb ** 7 + 25 ** 7)))
    a1p = (1 + G) * a1; a2p = (1 + G) * a2
    C1p = np.hypot(a1p, b1); C2p = np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    dLp = L2 - L1
    dCp = C2p - C1p
    dhp = h2p - h1p
    dhp = np.where(dhp > 180, dhp - 360, dhp)
    dhp = np.where(dhp < -180, dhp + 360, dhp)
    dhp = np.where(C1p * C2p == 0, 0, dhp)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dhp) / 2)
    Lbp = (L1 + L2) / 2
    Cbp = (C1p + C2p) / 2
    hsum = h1p + h2p
    hbp = np.where(np.abs(h1p - h2p) > 180, np.where(hsum < 360, (hsum + 360) / 2, (hsum - 360) / 2), hsum / 2)
    hbp = np.where(C1p * C2p == 0, hsum, hbp)
    T = (1 - 0.17 * np.cos(np.radians(hbp - 30)) + 0.24 * np.cos(np.radians(2 * hbp))
         + 0.32 * np.cos(np.radians(3 * hbp + 6)) - 0.20 * np.cos(np.radians(4 * hbp - 63)))
    dtheta = 30 * np.exp(-((hbp - 275) / 25) ** 2)
    Rc = 2 * np.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7))
    Sl = 1 + 0.015 * (Lbp - 50) ** 2 / np.sqrt(20 + (Lbp - 50) ** 2)
    Sc = 1 + 0.045 * Cbp
    Sh = 1 + 0.015 * Cbp * T
    Rt = -np.sin(np.radians(2 * dtheta)) * Rc
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))
