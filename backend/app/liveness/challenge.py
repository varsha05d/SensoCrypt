"""Illumination challenge -- luma-only variant of plan.md §6.1.

The plan's own code sample assumes RGB face-ROI frames; this pipeline's telemetry frames
are grayscale-only (§0.2's bandwidth choice), so this scores correlation of BRIGHTNESS
against the emitted sequence's luma, not full RGB reflectance. That is a real reduction in
discriminating power (can't tell a red flash from a blue flash of equal brightness apart),
but it keeps the existing grayscale pipeline unchanged and still catches the core attacks
§6.1 targets: a pre-recorded stream cannot contain a stimulus chosen after it was recorded,
and a flat/non-responsive scene (screen replay, static photo) fails the correlation
outright. Sending full-color frames instead of grayscale is the natural next step to
recover the color dimension.
"""

import random
import uuid

import numpy as np

CHALLENGE_STATES = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255), (0, 0, 0)]
STATE_DURATION_MS = 220
CHALLENGE_LEAD_S = 1.0  # time between issuing a challenge and it starting, for network + render latency


def _luma(rgb: tuple[int, int, int]) -> float:
    r, g, b = rgb
    return 0.299 * r + 0.587 * g + 0.114 * b


def generate_challenge(start_at_ns: int, n_states: int = 4) -> dict:
    states = [random.choice(CHALLENGE_STATES) for _ in range(n_states)]
    return {
        "id": uuid.uuid4().hex[:8],
        "states": [{"rgb": list(s), "dur_ms": STATE_DURATION_MS} for s in states],
        "start_at_ns": start_at_ns,
    }


def _expected_luma_at(challenge: dict, t_rel_ns: float) -> float | None:
    t_ms = t_rel_ns / 1e6
    cursor = 0.0
    for state in challenge["states"]:
        if cursor <= t_ms < cursor + state["dur_ms"]:
            return _luma(tuple(state["rgb"]))
        cursor += state["dur_ms"]
    return None


def illumination_score(observed: list[tuple[int, float]], challenge: dict) -> dict:
    """observed: (t_ns, mean_brightness) pairs for frames captured during the challenge window."""
    start = challenge["start_at_ns"]
    pairs = []
    for t_ns, brightness in observed:
        expected = _expected_luma_at(challenge, t_ns - start)
        if expected is not None:
            pairs.append((expected, brightness))
    if len(pairs) < 3:
        return {"verdict": "insufficient_frames", "S_illum": 0.0, "n": len(pairs)}

    exp = np.array([p[0] for p in pairs])
    obs = np.array([p[1] for p in pairs])
    # OBS_STD_FLOOR is a noise floor in 0-255 brightness units, not a near-zero epsilon:
    # in a bright room the screen's flash barely moves the camera-observed brightness
    # against strong ambient light, so a near-flat response is a real, common case -- it
    # must read as "can't tell" (no_variation), not as a fake near-zero correlation that
    # then gets treated as actual (weak, negative-leaning) evidence downstream.
    OBS_STD_FLOOR = 3.0
    if exp.std() < 1e-6 or obs.std() < OBS_STD_FLOOR:
        return {"verdict": "no_variation", "S_illum": 0.0, "n": len(pairs), "obs_std": float(obs.std())}

    r = float(np.corrcoef(exp, obs)[0, 1])
    return {"verdict": "ok", "S_illum": max(0.0, r), "r": r, "n": len(pairs)}
