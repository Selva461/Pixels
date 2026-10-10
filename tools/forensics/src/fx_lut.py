"""Fit / apply / export a smooth 3D LUT mapping source RGB -> target RGB.

Least squares on trilinear-interpolated lattice nodes with a second-difference
smoothness prior (so empty regions of the cube are extrapolated linearly, not
left as garbage). Solved per channel with conjugate gradients.
"""
import numpy as np
from scipy import sparse
from scipy.sparse.linalg import cg


def _interp_matrix(src, N):
    x = np.clip(src, 0, 1) * (N - 1)
    i0 = np.floor(x).astype(np.int64).clip(0, N - 2)
    f = x - i0
    n = len(src)
    rows, cols, vals = [], [], []
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = ((f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1])
                     * (f[:, 2] if db else 1 - f[:, 2]))
                idx = ((i0[:, 0] + dr) * N + (i0[:, 1] + dg)) * N + (i0[:, 2] + db)
                rows.append(np.arange(n)); cols.append(idx); vals.append(w)
    return sparse.csr_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(n, N ** 3))


def _second_diff(N):
    D = sparse.diags([np.ones(N - 2), -2 * np.ones(N - 2), np.ones(N - 2)], [0, 1, 2], shape=(N - 2, N))
    I = sparse.identity(N)
    return sparse.vstack([sparse.kron(sparse.kron(D, I), I), sparse.kron(sparse.kron(I, D), I),
                          sparse.kron(sparse.kron(I, I), D)]).tocsr()


def fit_lut(src, dst, N=33, lam=1.0, weights=None):
    """src, dst: (n,3) arrays in 0..1. Returns lut (N,N,N,3) indexed [r,g,b]. lam is relative."""
    A = _interp_matrix(src, N)
    if weights is not None:
        A = sparse.diags(weights) @ A
        dst = dst * weights[:, None]
    R = _second_diff(N)
    scale = A.shape[0] / N ** 3  # keep lam meaning stable across sample counts
    lhs = (A.T @ A + lam * scale * (R.T @ R)).tocsr()
    g = np.linspace(0, 1, N)
    ident = np.stack(np.meshgrid(g, g, g, indexing='ij'), -1).reshape(-1, 3)
    out = np.empty((N ** 3, 3))
    for c in range(3):
        rhs = A.T @ dst[:, c]
        x, info = cg(lhs, rhs, x0=ident[:, c], rtol=1e-8, maxiter=4000)
        out[:, c] = x
    return out.reshape(N, N, N, 3)


def apply_lut(lut, img):
    N = lut.shape[0]
    shp = img.shape
    A = _interp_matrix(img.reshape(-1, 3), N)
    return (A @ lut.reshape(-1, 3)).reshape(shp)


def write_cube(lut, path, title='fitted'):
    """Adobe/Resolve .cube: red index varies fastest."""
    N = lut.shape[0]
    with open(path, 'w') as f:
        f.write('TITLE "%s"\nLUT_3D_SIZE %d\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n' % (title, N))
        for b in range(N):
            for g in range(N):
                for r in range(N):
                    v = np.clip(lut[r, g, b], 0, 1)
                    f.write('%.6f %.6f %.6f\n' % tuple(v))
