"""Single source of truth for liveness engine tuning (plan.md §17.3)."""

FS_HZ = 50.0  # common resample grid
WINDOW_S = 2.0
HOP_S = 0.5
BAND_LO_HZ = 0.4
BAND_HI_HZ = 8.0
MAX_LAG_S = 0.35
# Lowered from 0.15: in low light the illumination channel is weak/inconclusive (screen
# flash barely changes ambient brightness), so motion needs to pick up more of the slack --
# small natural vibration/hand tremor should count as real evidence, not just deliberate
# obvious shaking.
MOTION_GATE = 0.08  # rad/s RMS
FRAME_W, FRAME_H = 160, 120
TELEMETRY_FPS = 15
CHUNK_MS = 500
NO_EVIDENCE_TIMEOUT_S = 10.0
# Illumination is the PRIMARY continuous signal (see fusion.py's ILLUM_WEIGHT): unlike
# egomotion it needs no deliberate motion from the user at all, which matters for the
# actual threat model here -- a scam/deepfake call target (e.g. an elderly person) cannot
# be expected to keep the phone moving through a calm conversation. Fired every 4-7s
# instead of the plan's original 8-15s so it's available far more often than motion evidence
# realistically is.
CHALLENGE_MIN_GAP_S = 4.0
CHALLENGE_MAX_GAP_S = 7.0
# plan.md §12 targets these thresholds against a properly trained fusion model on a real
# genuine/attack dataset. This project has neither yet (§0's fuse() is a plain average, not
# a trained model) -- these values are instead calibrated against real observed scores from
# live phone sessions (2026-08-24 logged run: genuine motion windows scored S_A up to 0.52,
# r up to 0.70, but rarely sustained above ~0.35 for 3+ consecutive windows -- the plan's
# 0.80/0.60/0.35 targets a trained fusion model this project doesn't have yet, and were
# unreachable in practice even with good real motion). Tighten back toward those targets
# once there's real labelled genuine/attack data to fit fuse() against (see
# eval/negative_control.py for the kind of check that should gate any change).
P_TRUST, P_DEGRADE, P_SUSPECT = 0.35, 0.20, 0.08
# Fast to promote (3 windows ~= 1.5s of good evidence is enough to trust), slow to demote
# (8-12 windows ~= 4-6s of SUSTAINED weak/bad evidence) -- a person calmly talking produces
# occasional noisy, low-confidence motion windows that must not be able to single-handedly
# flip the verdict. Only a real, sustained pattern of bad evidence should raise an alarm.
DWELL_TRUST, DWELL_DEGRADE, DWELL_SUSPECT = 3, 8, 12  # consecutive windows

# Determined empirically per plan.md §5.2, by eval/analyze_session.py's brute-force search
# against a real recording from the Pixel 9a (session 20260824_225100, front camera,
# portrait): mean per-axis r=0.62 on the lag-aligned signal. Re-run the search if the
# camera facing, orientation, or device changes -- this is device/orientation-specific.
AXIS_MAP_FRONT = ((1, -1), (0, 1), (2, -1))  # (device_axis, sign) per camera axis
