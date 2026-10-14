"""Colour round trip: an unedited export must show the same colours as the original.

  python roundtrip_check.py original.jpg exported.png [--mean 0.1] [--p99 1.5]

Both files are decoded through their own ICC profiles into CIELAB (D50) and compared with CIEDE2000.
Exit code 1 when the mean or the 99th percentile exceeds its limit (Task 2 of CLAUDE_CODE_BRIEF.md).
"""
import argparse, json, sys
import numpy as np
from fx_color import load, xyz_to_lab, deltaE2000


def roundtrip(original, exported):
    o, e = load(original), load(exported)
    if o['enc'].shape != e['enc'].shape:
        raise SystemExit('size differs: %s vs %s' % (o['size'], e['size']))
    dE = deltaE2000(xyz_to_lab(o['xyz']), xyz_to_lab(e['xyz']))
    return dict(original_profile=o['desc'], export_profile=e['desc'], dE00_mean=round(float(dE.mean()), 3),
                dE00_p99=round(float(np.percentile(dE, 99)), 3), dE00_max=round(float(dE.max()), 3))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('original'); ap.add_argument('exported')
    ap.add_argument('--mean', type=float, default=0.1); ap.add_argument('--p99', type=float, default=1.5)
    a = ap.parse_args()
    r = roundtrip(a.original, a.exported)
    print(json.dumps(r))
    sys.exit(0 if r['dE00_mean'] < a.mean and r['dE00_p99'] < a.p99 else 1)
