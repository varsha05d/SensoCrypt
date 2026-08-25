"""Per-session live scoring (plan.md §10.3): one instance per telemetry WS connection,
fed chunk-by-chunk, re-scoring a sliding window each time. Reuses exactly the egomotion /
imu / align / score modules validated offline in Phase 3 (eval/analyze_session.py) against
a real recording -- same code path, just fed incrementally instead of all at once.

Also schedules the illumination challenge (§6.1, Channel B) and fuses it with the
egomotion score (Channel A) through the trust state machine (§8) -- Channel A alone only
proves the camera is live, not that the face in frame is real (§0.1); Channel B is what
actually starts answering that question.
"""

import random
from collections import deque

import cv2
import numpy as np

from app.liveness import align, challenge as challenge_mod, egomotion, fusion, imu, score
from app.liveness.constants import (
    AXIS_MAP_FRONT,
    BAND_HI_HZ,
    BAND_LO_HZ,
    CHALLENGE_MAX_GAP_S,
    CHALLENGE_MIN_GAP_S,
    FS_HZ,
    MOTION_GATE,
    NO_EVIDENCE_TIMEOUT_S,
    WINDOW_S,
)

BUFFER_S = WINDOW_S + 1.0
LAG_LOCK_MIN_STRENGTH = 0.3
AXIS_MAP_LOCK_MIN_SCORE = 0.3
AXIS_MAP_MIN_ENERGY = MOTION_GATE * 2  # needs clearly-real motion, not just above the noise floor
CHALLENGE_END_MARGIN_S = 0.3
ILLUM_SCORE_STALE_S = 20.0  # one good/bad reading shouldn't keep swaying trust indefinitely


class LivenessEngine:
    def __init__(self, quick: bool = False):
        self.frames: deque[tuple[int, np.ndarray]] = deque()
        self.gyro: deque[tuple[int, float, float, float]] = deque()
        self.K = egomotion.intrinsics(160, 120)
        self.locked_lag: int | None = None
        self.locked_axis_map: tuple[tuple[int, int], ...] | None = None

        # quick=True: a bounded one-shot verification capture (a few seconds), not an
        # open-ended live session -- fire the illumination challenge almost immediately
        # instead of waiting the normal randomized 8-15s gap, so a short capture window
        # still gets one.
        self.quick = quick
        self._challenge_scheduled_once = False

        self.active_challenge: dict | None = None
        self.next_challenge_at_ns: int | None = None
        self.last_illum_score: dict | None = None
        self.last_illum_score_ns: int | None = None

        self.first_frame_ns: int | None = None
        self.last_motion_ok_ns: int | None = None

        self.trust_fsm = fusion.TrustFSM()

    def _trim(self, latest_ns: int):
        cutoff = latest_ns - int(BUFFER_S * 1e9)
        while self.frames and self.frames[0][0] < cutoff:
            self.frames.popleft()
        while self.gyro and self.gyro[0][0] < cutoff:
            self.gyro.popleft()

    @staticmethod
    def _center_brightness(frame: np.ndarray) -> float:
        h, w = frame.shape
        return float(frame[h // 4 : 3 * h // 4, w // 4 : 3 * w // 4].mean())

    def _score_motion(self) -> dict:
        if len(self.frames) < 6 or len(self.gyro) < 10:
            return {"verdict": "warming_up", "frames": len(self.frames), "gyro": len(self.gyro)}

        mids, omegas = [], []
        frames_list = list(self.frames)
        for i in range(len(frames_list) - 1):
            t0, f0 = frames_list[i]
            t1, f1 = frames_list[i + 1]
            dt = (t1 - t0) / 1e9
            if dt <= 0:
                continue
            omega, _conf = egomotion.omega_from_pair(f0, f1, self.K, dt=dt)
            if omega is None:
                continue
            mids.append((t0 + t1) / 2)
            omegas.append(omega)

        if len(mids) < 3:
            return {"verdict": "no_evidence", "reason": "insufficient optical flow"}

        mids_arr = np.array(mids, dtype=np.int64)
        omegas_arr = np.array(omegas)
        gyro_arr = np.array(self.gyro)
        gyro_ts, gyro_xyz = gyro_arr[:, 0].astype(np.int64), gyro_arr[:, 1:4]

        t0, t1 = max(mids_arr[0], gyro_ts[0]), min(mids_arr[-1], gyro_ts[-1])
        if t1 <= t0:
            return {"verdict": "no_evidence", "reason": "no time overlap"}
        grid = np.arange(t0, t1, int(1e9 / FS_HZ))
        # scipy's sosfiltfilt needs the input longer than its internal pad length (~27
        # samples for this filter order) or it raises -- 80 samples (1.6s) is a safe margin.
        if len(grid) < 80:
            return {"verdict": "warming_up"}

        vis_grid = imu.resample(mids_arr, omegas_arr, grid)
        gyro_grid = imu.resample(gyro_ts, gyro_xyz, grid)
        try:
            vis_bp = imu.bandpass(vis_grid, fs=FS_HZ, lo=BAND_LO_HZ, hi=BAND_HI_HZ)
            gyro_bp = imu.bandpass(gyro_grid, fs=FS_HZ, lo=BAND_LO_HZ, hi=BAND_HI_HZ)
        except ValueError:
            return {"verdict": "warming_up"}

        if self.locked_lag is None:
            lag, strength = align.best_lag(vis_bp, gyro_bp, fs=FS_HZ)
            if strength > LAG_LOCK_MIN_STRENGTH:
                self.locked_lag = lag
        lag = self.locked_lag or 0

        if lag >= 0:
            vis_aligned, gyro_aligned = vis_bp[lag:], gyro_bp[: len(vis_bp) - lag]
        else:
            gyro_aligned, vis_aligned = gyro_bp[-lag:], vis_bp[: len(gyro_bp) + lag]

        n = min(len(vis_aligned), len(gyro_aligned))
        if n < 10:
            return {"verdict": "warming_up"}
        vis_aligned, gyro_aligned = vis_aligned[:n], gyro_aligned[:n]

        # Per-session axis-mapping calibration (plan.md §5.2), not a fixed global constant:
        # a mapping calibrated from one offline recording does not reliably generalise to
        # every way THIS session's user holds and moves the phone -- observed in practice as
        # sustained negative r using a fixed mapping. Same locking pattern as the lag above.
        #
        # Also requires real motion energy in the calibration window, not just enough
        # samples: a low-energy window (someone mostly holding still) produces noise the
        # brute-force search will happily fit an arbitrary-looking "best" mapping to, which
        # then locks in wrong for the rest of the session. Observed in practice as r staying
        # negative for an entire call after locking on a too-quiet window.
        gyro_energy = float(np.sqrt((gyro_aligned**2).sum(axis=1).mean()))
        if self.locked_axis_map is None and n >= int(WINDOW_S * FS_HZ) and gyro_energy >= AXIS_MAP_MIN_ENERGY:
            axis_score, perm, signs = align.find_axis_mapping(vis_aligned, gyro_aligned)
            if axis_score > AXIS_MAP_LOCK_MIN_SCORE:
                self.locked_axis_map = tuple(zip(perm, signs))
        axis_map = self.locked_axis_map or AXIS_MAP_FRONT
        gyro_mapped = np.stack([sign * gyro_aligned[:, axis] for axis, sign in axis_map], axis=1)

        win = min(len(vis_aligned), int(WINDOW_S * FS_HZ))
        result = score.window_score(vis_aligned[-win:], gyro_mapped[-win:], conf=0.8)
        result["lag_ms"] = lag / FS_HZ * 1000
        result["axis_map_locked"] = self.locked_axis_map is not None
        result["lag_locked"] = self.locked_lag is not None
        return result

    def _handle_challenge(self, latest_ts: int) -> dict | None:
        """Schedules, times out, and scores the illumination challenge. Returns a
        new-challenge payload to forward to the client, or None if nothing new was issued
        this tick."""
        if self.trust_fsm.state == "TRUSTED":
            # Verified once -- stop flashing entirely, by product decision. Trade-off,
            # stated plainly: the system will not notice if the feed is swapped out after
            # this point (no periodic re-check). Re-arms automatically the moment trust
            # drops below TRUSTED (that happens on its own via the state machine if the
            # motion/illumination evidence turns bad -- this function just won't schedule
            # a new challenge while TRUSTED holds).
            return None

        if self.active_challenge is None and self.next_challenge_at_ns is None:
            if self.quick and not self._challenge_scheduled_once:
                gap_s = 0.5
            else:
                gap_s = random.uniform(CHALLENGE_MIN_GAP_S, CHALLENGE_MAX_GAP_S)
            self.next_challenge_at_ns = latest_ts + int(gap_s * 1e9)
            self._challenge_scheduled_once = True

        new_challenge = None
        if (
            self.active_challenge is None
            and self.next_challenge_at_ns is not None
            and latest_ts >= self.next_challenge_at_ns
        ):
            start_at = latest_ts + int(challenge_mod.CHALLENGE_LEAD_S * 1e9)
            self.active_challenge = challenge_mod.generate_challenge(start_at)
            self.next_challenge_at_ns = None
            new_challenge = self.active_challenge

        if self.active_challenge is not None:
            total_dur_ns = int(sum(s["dur_ms"] for s in self.active_challenge["states"]) * 1e6)
            window_end = self.active_challenge["start_at_ns"] + total_dur_ns
            if latest_ts >= window_end + int(CHALLENGE_END_MARGIN_S * 1e9):
                observed = [
                    (t, self._center_brightness(f))
                    for t, f in self.frames
                    if self.active_challenge["start_at_ns"] <= t <= window_end
                ]
                self.last_illum_score = challenge_mod.illumination_score(observed, self.active_challenge)
                self.last_illum_score_ns = latest_ts
                self.active_challenge = None

        return new_challenge

    def ingest(self, chunk: dict) -> dict:
        for f in chunk.get("frames", []):
            arr = cv2.imdecode(np.frombuffer(f["jpeg_bytes"], dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
            if arr is not None:
                self.frames.append((f["t_ns"], arr))
        for t_ns, wx, wy, wz in chunk.get("imu", {}).get("gyro", []):
            self.gyro.append((t_ns, wx, wy, wz))

        if not self.frames:
            return {"verdict": "no_data"}
        latest_ts = self.frames[-1][0]
        self._trim(latest_ts)

        result = self._score_motion()
        new_challenge = self._handle_challenge(latest_ts)
        if new_challenge is not None:
            result["new_challenge"] = new_challenge
        if self.last_illum_score is not None:
            result["S_illum"] = self.last_illum_score

        # plan.md §6.2 / §9 L5: a still phone must never register as trusted on nothing --
        # but it also shouldn't be stuck with no way to prove itself. After
        # NO_EVIDENCE_TIMEOUT_S without fresh motion evidence, prompt for a deliberate
        # move instead of just sitting there. This is a pure UX nudge -- the resulting
        # motion is scored through the normal S_A path above, no separate verification.
        if self.first_frame_ns is None:
            self.first_frame_ns = latest_ts
        if result.get("verdict") == "ok":
            self.last_motion_ok_ns = latest_ts
        baseline_ns = self.last_motion_ok_ns if self.last_motion_ok_ns is not None else self.first_frame_ns
        result["motion_prompt"] = (latest_ts - baseline_ns) > int(NO_EVIDENCE_TIMEOUT_S * 1e9)

        illum_fresh = (
            self.last_illum_score_ns is not None
            and (latest_ts - self.last_illum_score_ns) <= int(ILLUM_SCORE_STALE_S * 1e9)
        )
        s_a = result.get("S_A") if result.get("verdict") == "ok" else None
        s_illum = (
            self.last_illum_score.get("S_illum")
            if illum_fresh and self.last_illum_score and self.last_illum_score.get("verdict") == "ok"
            else None
        )
        if s_a is not None or s_illum is not None:
            p = fusion.fuse(s_a, s_illum)
            result["p_trust"] = p
            result["trust_state"] = self.trust_fsm.update(p)
        else:
            result["trust_state"] = self.trust_fsm.state

        return result
