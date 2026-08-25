"""Lag estimation between the visual and IMU streams (plan.md §5.4).

Camera pipeline delay (~30-80ms) and sensor delay (~5-20ms) differ and are unknown --
estimate once over the first ~5s, then lock it. Letting the lag float freely each window is
itself an attack surface: an adversary with an imperfect fake gains free slack.
"""

from itertools import permutations, product

import numpy as np


def best_lag(a, b, fs=50.0, max_lag_s=0.35):
    """Cross-correlate motion magnitudes to find lag of b relative to a, in samples."""
    ma = np.linalg.norm(a, axis=1)
    mb = np.linalg.norm(b, axis=1)
    ma = (ma - ma.mean()) / (ma.std() + 1e-9)
    mb = (mb - mb.mean()) / (mb.std() + 1e-9)
    n = int(max_lag_s * fs)
    xc = np.correlate(ma, mb, mode="full") / len(ma)
    center = len(ma) - 1
    window = xc[center - n : center + n + 1]
    return int(np.argmax(window)) - n, float(window.max())


def find_axis_mapping(vis, gyro) -> tuple[float, tuple[int, int, int], tuple[int, int, int]]:
    """Brute-force the 3x3 signed permutation (plan.md §5.2) that maximises mean per-axis
    correlation between two already lag-aligned (N,3) angular-velocity arrays.

    A single hardcoded mapping calibrated from one offline recording (as eval/analyze_session.py
    produces) does not reliably generalise across every way a session's user actually holds and
    moves the phone -- observed in practice as sustained NEGATIVE r on live sessions using a
    fixed mapping from a different recording. Calibrating per-session, the same way the lag
    is locked once per session below, is the robust fix.
    """
    best_score, best_perm, best_signs = -2.0, (0, 1, 2), (1, 1, 1)
    for perm in permutations(range(3)):
        for signs in product((1, -1), repeat=3):
            mapped = np.stack([signs[i] * gyro[:, perm[i]] for i in range(3)], axis=1)
            rs = []
            for i in range(3):
                a, b = vis[:, i], mapped[:, i]
                if a.std() < 1e-6 or b.std() < 1e-6:
                    continue
                rs.append(np.corrcoef(a, b)[0, 1])
            s = float(np.mean(rs)) if rs else -2.0
            if s > best_score:
                best_score, best_perm, best_signs = s, perm, signs
    return best_score, best_perm, best_signs
