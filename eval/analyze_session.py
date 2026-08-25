#!/usr/bin/env python3
"""Offline Phase 3 validation (plan.md §11 Phase 3, §17.5): load a recorded session,
compute the visual angular velocity from consecutive frame pairs, resample the gyro onto
the same grid, calibrate the camera<->sensor axis mapping empirically (§5.2 -- "determine
the signs empirically once"), then run the windowed correlation score (§5.5) and report it.

Usage: python analyze_session.py <session_dir>
"""

import struct
import sys
from itertools import permutations, product
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from app.liveness import align, egomotion, imu, score  # noqa: E402
from app.liveness.constants import BAND_HI_HZ, BAND_LO_HZ, FS_HZ, HOP_S, WINDOW_S  # noqa: E402

FRAME_W, FRAME_H = 160, 120
FRAME_BYTES = FRAME_W * FRAME_H
RECORD_SIZE = 8 + FRAME_BYTES


def load_frames(path: Path):
    data = path.read_bytes()
    n = len(data) // RECORD_SIZE
    timestamps = np.zeros(n, dtype=np.int64)
    frames = np.zeros((n, FRAME_H, FRAME_W), dtype=np.uint8)
    for i in range(n):
        off = i * RECORD_SIZE
        timestamps[i] = struct.unpack(">q", data[off : off + 8])[0]
        frames[i] = np.frombuffer(data[off + 8 : off + RECORD_SIZE], dtype=np.uint8).reshape(FRAME_H, FRAME_W)
    return timestamps, frames


def load_csv(path: Path):
    arr = np.loadtxt(path, delimiter=",", skiprows=1)
    return arr[:, 0].astype(np.int64), arr[:, 1:4]


def compute_omega_vis(timestamps, frames):
    """Returns (mid_t_ns, omega_cam (N,3), conf (N,)) for each consecutive frame pair."""
    K = egomotion.intrinsics(FRAME_W, FRAME_H)
    mids, omegas, confs = [], [], []
    for i in range(len(frames) - 1):
        dt = (timestamps[i + 1] - timestamps[i]) / 1e9
        if dt <= 0:
            continue
        omega, conf = egomotion.omega_from_pair(frames[i], frames[i + 1], K, dt=dt)
        if omega is None:
            continue
        mids.append((timestamps[i] + timestamps[i + 1]) / 2)
        omegas.append(omega)
        confs.append(conf)
    return np.array(mids, dtype=np.int64), np.array(omegas), np.array(confs)


def resample_irregular(t_ns, values, t_grid_ns):
    return imu.resample(t_ns, values, t_grid_ns)


def find_axis_mapping(omega_vis_aligned, gyro_aligned):
    """Brute-force the 3x3 signed permutation that maximises per-axis correlation
    (plan.md §5.2). Lag alignment happens before this via magnitude cross-correlation,
    which is invariant to axis permutation/sign -- safe to do before we know the mapping."""
    best_score, best_perm, best_signs = -2.0, (0, 1, 2), (1, 1, 1)
    for perm in permutations(range(3)):
        for signs in product((1, -1), repeat=3):
            mapped = np.stack([signs[i] * gyro_aligned[:, perm[i]] for i in range(3)], axis=1)
            rs = []
            for i in range(3):
                a, b = omega_vis_aligned[:, i], mapped[:, i]
                if a.std() < 1e-6 or b.std() < 1e-6:
                    continue
                rs.append(np.corrcoef(a, b)[0, 1])
            s = float(np.mean(rs)) if rs else -2.0
            if s > best_score:
                best_score, best_perm, best_signs = s, perm, signs
    return best_score, best_perm, best_signs


def main(session_dir: str):
    d = Path(session_dir)
    print(f"Loading session: {d}")

    frame_ts, frames = load_frames(d / "frames.bin")
    gyro_ts, gyro_xyz = load_csv(d / "gyro.csv")
    print(f"  {len(frames)} frames, {len(gyro_ts)} gyro samples")

    print("Computing visual angular velocity from frame pairs (this takes a bit)...")
    vis_ts, omega_vis, conf = compute_omega_vis(frame_ts, frames)
    valid_pairs = len(omega_vis)
    total_pairs = len(frames) - 1
    print(f"  {valid_pairs}/{total_pairs} frame pairs yielded usable optical flow (conf mean={conf.mean():.2f})")
    if valid_pairs < 10:
        print("Too few valid pairs to analyze -- likely low-texture scene or too much motion blur.")
        return

    t_start = max(vis_ts[0], gyro_ts[0])
    t_end = min(vis_ts[-1], gyro_ts[-1])
    grid = np.arange(t_start, t_end, int(1e9 / FS_HZ))
    print(f"  common time range: {(t_end - t_start) / 1e9:.1f}s, {len(grid)} samples @ {FS_HZ}Hz")

    vis_grid = resample_irregular(vis_ts, omega_vis, grid)
    gyro_grid = resample_irregular(gyro_ts, gyro_xyz, grid)

    vis_bp = imu.bandpass(vis_grid, fs=FS_HZ, lo=BAND_LO_HZ, hi=BAND_HI_HZ)
    gyro_bp = imu.bandpass(gyro_grid, fs=FS_HZ, lo=BAND_LO_HZ, hi=BAND_HI_HZ)

    lag_samples, lag_strength = align.best_lag(vis_bp, gyro_bp, fs=FS_HZ)
    print(f"  estimated lag: {lag_samples / FS_HZ * 1000:.1f}ms (xcorr strength={lag_strength:.2f})")

    if lag_samples >= 0:
        vis_aligned = vis_bp[lag_samples:]
        gyro_aligned = gyro_bp[: len(vis_aligned)]
    else:
        gyro_aligned = gyro_bp[-lag_samples:]
        vis_aligned = vis_bp[: len(gyro_aligned)]

    print("Searching for the camera<->sensor axis mapping (48 candidates)...")
    axis_score, perm, signs = find_axis_mapping(vis_aligned, gyro_aligned)
    print(f"  best mapping: perm={perm} signs={signs}  (mean per-axis r={axis_score:.3f})")
    print(f"  -> hardcode as AXIS_MAP_FRONT = {tuple(zip(perm, signs))}")

    gyro_mapped = np.stack([signs[i] * gyro_aligned[:, perm[i]] for i in range(3)], axis=1)

    win_samples = int(WINDOW_S * FS_HZ)
    hop_samples = int(HOP_S * FS_HZ)
    results = []
    for start in range(0, len(vis_aligned) - win_samples, hop_samples):
        w_vis = vis_aligned[start : start + win_samples]
        w_dev = gyro_mapped[start : start + win_samples]
        r = score.window_score(w_vis, w_dev, conf=float(conf.mean()))
        results.append(r)

    ok = [r for r in results if r["verdict"] == "ok"]
    no_ev = [r for r in results if r["verdict"] == "no_evidence"]
    print(f"\n{len(results)} windows: {len(ok)} scored, {len(no_ev)} no_evidence (stillness)")
    if ok:
        s_a = [r["S_A"] for r in ok]
        r_vals = [r["r"] for r in ok]
        print(f"  S_A: mean={np.mean(s_a):.3f} min={np.min(s_a):.3f} max={np.max(s_a):.3f}")
        print(f"  r:   mean={np.mean(r_vals):.3f} min={np.min(r_vals):.3f} max={np.max(r_vals):.3f}")
        print(f"\n{'PASS' if np.mean(s_a) > 0.5 else 'NEEDS TUNING'}: genuine session S_A should trend high.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python analyze_session.py <session_dir>")
        sys.exit(1)
    main(sys.argv[1])
