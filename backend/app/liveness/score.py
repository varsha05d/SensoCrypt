"""Egomotion coherence scoring (plan.md §5.5, Channel A).

Two design points defended in the plan's own viva-prep section:
  - The motion gate: below MOTION_GATE there is no evidence either way. Reporting a
    confident score over noise (a naive implementation's default) is wrong; absence of
    evidence must never register as evidence of liveness (§9 L5).
  - Gain matching: correlation is scale-invariant, so a replay at half amplitude would
    still score r~=1 without this term.
"""

import numpy as np

from app.liveness.constants import MOTION_GATE


def window_score(w_vis, w_dev, conf):
    """w_vis, w_dev: (N,3) aligned, bandpassed angular velocities at 50 Hz."""
    energy = float(np.sqrt((w_dev**2).sum(axis=1).mean()))
    if energy < MOTION_GATE:
        return {"r": None, "energy": energy, "verdict": "no_evidence"}

    r = []
    for i in range(3):
        a, b = w_vis[:, i], w_dev[:, i]
        if a.std() < 1e-6 or b.std() < 1e-6:
            continue
        r.append(float(np.corrcoef(a, b)[0, 1]))
    if not r:
        return {"r": None, "energy": energy, "verdict": "no_evidence"}

    axis_energy = np.abs(w_dev).mean(axis=0)[: len(r)]
    w = axis_energy / (axis_energy.sum() + 1e-9)
    r_w = float(np.dot(w, r))  # energy-weighted mean correlation

    # magnitude coherence: does the AMOUNT of motion match, not just its shape?
    gain = float(np.linalg.norm(w_vis) / (np.linalg.norm(w_dev) + 1e-9))
    gain_pen = float(np.exp(-(abs(np.log(max(gain, 1e-3))) ** 2) / 0.5))

    return {
        "r": r_w,
        "gain": gain,
        "energy": energy,
        "S_A": max(0.0, r_w) * gain_pen * conf,
        "verdict": "ok",
    }
