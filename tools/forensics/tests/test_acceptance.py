"""Acceptance tests: an output passes only if it fixes every defect measured in the sample edit.

Run from the pack root:  python -m pytest -q tests
Point OUTPUT_UNDER_TEST at your app's render of samples/original_p3.jpg to test your app:
  OUTPUT_UNDER_TEST=path/to/your_output.jpg python -m pytest -q tests -k app
Thresholds were calibrated on ONE photo; re-calibrate on 20+ varied photos before trusting them.
"""
import os, sys, json, subprocess
import numpy as np
import pytest
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'src'))
import edit_forensics as ef            # noqa: E402
import case_study_checks as cs         # noqa: E402

ORIG = os.path.join(ROOT, 'samples', 'original_p3.jpg')
EDIT = os.path.join(ROOT, 'samples', 'edit_srgb.jpg')
MATTE = os.path.join(ROOT, 'samples', 'subject_matte.png')

LIMITS = dict(
    halo_extent_px=3,            # residual dL* must be < 1 beyond this distance outside the subject
    halo_area_index=15.0,        # sum of positive residual dL* x px over 0-100 px
    subject_highlight_texture_4_8px=0.97,   # edit/original band energy, subject highlights
    crushed_channel_pct=3.0,     # pixels with any channel <= 1/255
    sky_noise_ratio=1.6,         # Immerkaer sigma, output / original
    skin_dE00=4.0, skin_hue_deg=1.5,
    global_fit_dE00=1.0,         # far background must be explainable by one global grade
    mask_coverage_rel=0.5,       # no 100-row band of the subject may deviate >50% from the median lift
    logo_keep=0.9, folds_keep=0.9,  # photo-specific: fraction of original contrast kept
)


def matte():
    W, H = Image.open(ORIG).size
    return np.asarray(Image.open(MATTE).convert('L').resize((W, H)), np.float32) / 255


def measure(path, tmp):
    R, _ = ef.analyze(ORIG, path, matte(), str(tmp))
    C = cs.check(ORIG, path, matte())
    return R, C


def failures(R, C):
    f = []
    h = R['halo']
    if h['extent_px_dL_below_1'] is None or h['extent_px_dL_below_1'] > LIMITS['halo_extent_px']:
        f.append('halo extent %s px' % h['extent_px_dL_below_1'])
    if h['halo_area_index'] > LIMITS['halo_area_index']:
        f.append('halo area %.1f' % h['halo_area_index'])
    tex = R['subject']['texture_by_tone'].get('highlights_L>=70', {}).get('mid_4_8px', 1.0)
    if tex < LIMITS['subject_highlight_texture_4_8px']:
        f.append('subject highlight texture %.3f' % tex)
    crushed = R['clipping']['edited_as_stored']['any_channel_le1_pct']
    if crushed > LIMITS['crushed_channel_pct']:
        f.append('crushed channel %.2f%%' % crushed)
    if 'sky' in R:
        ratio = R['sky']['noise_sigma_L_edit'] / R['sky']['noise_sigma_L_orig']
        if ratio > LIMITS['sky_noise_ratio']:
            f.append('sky noise x%.2f' % ratio)
    sk = R['subject']['skin_tones']
    if sk and (sk['dE00_mean'] > LIMITS['skin_dE00'] or abs(sk['dh_deg']) > LIMITS['skin_hue_deg']):
        f.append('skin dE00 %.2f hue %.2f' % (sk['dE00_mean'], sk['dh_deg']))
    cov = R['mask_coverage']['worst_relative']
    if cov is not None and cov > LIMITS['mask_coverage_rel']:
        f.append('mask coverage %.2f' % cov)
    if R['global_grade_fit']['heldout_dE00_mean'] > LIMITS['global_fit_dE00']:
        f.append('global fit %.2f' % R['global_grade_fit']['heldout_dE00_mean'])
    if C['logo_contrast_out'] < LIMITS['logo_keep'] * C['logo_contrast_orig']:
        f.append('logo %.2f' % C['logo_contrast_out'])
    if C['shirt_fold_rms_out'] < LIMITS['folds_keep'] * C['shirt_fold_rms_orig']:
        f.append('folds %.3f' % C['shirt_fold_rms_out'])
    return f


@pytest.fixture(scope='session')
def reference_output(tmp_path_factory):
    out = tmp_path_factory.mktemp('ref')
    subprocess.run([sys.executable, os.path.join(ROOT, 'src', 'enhance_reference.py'), ORIG, MATTE,
                    str(out / 'reference')], check=True, cwd=os.path.join(ROOT, 'src'))
    return str(out / 'reference_srgb.jpg')


def test_reference_passes(reference_output, tmp_path):
    R, C = measure(reference_output, tmp_path)
    assert failures(R, C) == []


def test_sample_edit_fails_every_major_check(tmp_path):
    """The tests must discriminate: the sample edit fails halo, texture, crush, sky, skin, fit, logo, folds."""
    R, C = measure(EDIT, tmp_path)
    f = ' | '.join(failures(R, C))
    for key in ['halo extent', 'halo area', 'subject highlight texture', 'crushed channel', 'sky noise',
                'skin', 'mask coverage', 'global fit', 'logo', 'folds']:
        assert key in f, key


@pytest.mark.skipif(not os.environ.get('OUTPUT_UNDER_TEST'), reason='set OUTPUT_UNDER_TEST to test your app')
def test_app_output(tmp_path):
    R, C = measure(os.environ['OUTPUT_UNDER_TEST'], tmp_path)
    assert failures(R, C) == [], failures(R, C)


def test_forensics_recovers_known_halo(tmp_path):
    """Self-test of the measuring tool on synthetic edits with a known answer (slow: ~1 min)."""
    lut = os.path.join(tmp_path, 'lut.npy')
    R, _ = ef.analyze(ORIG, EDIT, matte(), str(tmp_path / 'fit'))
    np.save(lut, np.load(str(tmp_path / 'fit' / 'global_lut33.npy')))
    subprocess.run([sys.executable, os.path.join(ROOT, 'src', 'validate_forensics.py'), ORIG, MATTE, lut,
                    str(tmp_path / 'val'), '--sigma', '10', '--lift', '0.6'], check=True, cwd=os.path.join(ROOT, 'src'))
    V = json.load(open(tmp_path / 'val' / 'validation.json'))['results']
    for name in ('synth_halo', 'synth_clean'):
        assert V[name]['max_abs_error_dL'] < 0.25, V[name]
        assert V[name]['measured_extent_px'] == V[name]['true_extent_px'], V[name]


def test_cube_file_matches_lut(tmp_path):
    """The exported .cube must reproduce the fitted LUT when read back in standard red-fastest order."""
    from fx_lut import fit_lut, apply_lut, write_cube
    rng = np.random.default_rng(1)
    src = rng.random((20000, 3)); dst = np.clip(src ** 1.2 * [1.0, 0.95, 1.05], 0, 1)
    lut = fit_lut(src, dst, N=17, lam=0.1)
    p = tmp_path / 't.cube'
    write_cube(lut, str(p))
    vals = [list(map(float, l.split())) for l in open(p) if l[:1].isdigit() or l[:1] == '-']
    N = 17
    back = np.zeros((N, N, N, 3))
    k = 0
    for b in range(N):
        for g in range(N):
            for r in range(N):
                back[r, g, b] = vals[k]; k += 1
    assert np.abs(back - np.clip(lut, 0, 1)).max() < 1e-5
    x = rng.random((1000, 3))
    assert np.abs(apply_lut(back, x) - np.clip(apply_lut(lut, x), 0, 1)).max() < 0.02
