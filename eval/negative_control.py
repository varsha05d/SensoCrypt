#!/usr/bin/env python3
"""plan.md §17.4 negative controls: test_shuffled_imu_scores_below_0_2 and
test_time_reversed_video_scores_below_0_2. If these DON'T score low, the pipeline is
picking up something spurious (e.g. a systematic timing artifact) rather than genuine
motion correlation -- this is what actually proves analyze_session.py's r=0.57 average is
real signal, not a fluke of this particular recording's timing.
"""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from app.liveness import align, imu, score  # noqa: E402
from app.liveness.constants import BAND_HI_HZ, BAND_LO_HZ, FS_HZ, HOP_S, WINDOW_S  # noqa: E402
from analyze_session import (  # noqa: E402
    compute_omega_vis,
    find_axis_mapping,
    load_csv,
    load_frames,
    resample_irregular,
)


def score_windows(vis_aligned, gyro_mapped, conf):
    win = int(WINDOW_S * FS_HZ)
    hop = int(HOP_S * FS_HZ)
    results = []
    for start in range(0, len(vis_aligned) - win, hop):
        r = score.window_score(vis_aligned[start : start + win], gyro_mapped[start : start + win], conf=conf)
        results.append(r)
    ok = [r for r in results if r["verdict"] == "ok"]
    return np.mean([r["S_A"] for r in ok]) if ok else 0.0


def main(session_dir: str):
    d = Path(session_dir)
    frame_ts, frames = load_frames(d / "frames.bin")
    gyro_ts, gyro_xyz = load_csv(d / "gyro.csv")

    vis_ts, omega_vis, conf = compute_omega_vis(frame_ts, frames)
    t_start, t_end = max(vis_ts[0], gyro_ts[0]), min(vis_ts[-1], gyro_ts[-1])
    grid = np.arange(t_start, t_end, int(1e9 / FS_HZ))

    vis_grid = resample_irregular(vis_ts, omega_vis, grid)
    gyro_grid = resample_irregular(gyro_ts, gyro_xyz, grid)
    vis_bp = imu.bandpass(vis_grid, fs=FS_HZ, lo=BAND_LO_HZ, hi=BAND_HI_HZ)
    gyro_bp = imu.bandpass(gyro_grid, fs=FS_HZ, lo=BAND_LO_HZ, hi=BAND_HI_HZ)

    lag, _ = align.best_lag(vis_bp, gyro_bp, fs=FS_HZ)
    if lag >= 0:
        vis_aligned, gyro_aligned = vis_bp[lag:], gyro_bp[: len(vis_bp) - lag]
    else:
        gyro_aligned, vis_aligned = gyro_bp[-lag:], vis_bp[: len(gyro_bp) + lag]

    _, perm, signs = find_axis_mapping(vis_aligned, gyro_aligned)
    gyro_mapped = np.stack([signs[i] * gyro_aligned[:, perm[i]] for i in range(3)], axis=1)
    conf_mean = float(conf.mean())

    genuine_score = score_windows(vis_aligned, gyro_mapped, conf_mean)

    rng = np.random.default_rng(42)
    shuffled = gyro_mapped.copy()
    rng.shuffle(shuffled)
    shuffled_score = score_windows(vis_aligned, shuffled, conf_mean)

    reversed_score = score_windows(vis_aligned, gyro_mapped[::-1], conf_mean)

    print(f"genuine (aligned, correct axes):  S_A = {genuine_score:.3f}")
    print(f"shuffled IMU (negative control):  S_A = {shuffled_score:.3f}  (want < 0.2)")
    print(f"time-reversed IMU (negative ctrl): S_A = {reversed_score:.3f}  (want < 0.2)")
    print()
    print(f"shuffled test:  {'PASS' if shuffled_score < 0.2 else 'FAIL'}")
    print(f"reversed test:  {'PASS' if reversed_score < 0.2 else 'FAIL'}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "recordings/20260824_225100")
