"""Frames -> visual angular velocity (plan.md §5.2).

Rotation-induced optical flow is explained by a homography H = K R K^-1, independent of
scene depth -- this is why gyro<->video correlation is the robust, primary signal, and why
translation (which does depend on depth) is only used as a secondary event-level feature.
"""

import cv2
import numpy as np

FEATURE_PARAMS = dict(maxCorners=300, qualityLevel=0.01, minDistance=7, blockSize=7)
LK_PARAMS = dict(
    winSize=(21, 21),
    maxLevel=3,
    criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01),
)


def intrinsics(w: int, h: int, hfov_deg: float = 65.0) -> np.ndarray:
    """Approximate K for a phone front camera. Refine per-device with a calibration run."""
    f = (w / 2.0) / np.tan(np.radians(hfov_deg) / 2.0)
    return np.array([[f, 0, w / 2.0], [0, f, h / 2.0], [0, 0, 1.0]], dtype=np.float64)


def omega_from_pair(prev_gray, gray, K, face_box=None, dt=1 / 15.0):
    """Return (omega_xyz rad/s, confidence 0..1) for one consecutive frame pair.

    face_box is masked OUT: the face is the region an attacker can forge, so egomotion is
    estimated from the background only -- a security requirement (plan.md §5.2), not an
    optimisation. Channel B (§7) judges the face separately.
    """
    mask = np.full(prev_gray.shape, 255, np.uint8)
    if face_box is not None:
        x, y, bw, bh = face_box
        pad = int(0.15 * max(bw, bh))
        mask[max(0, y - pad) : y + bh + pad, max(0, x - pad) : x + bw + pad] = 0

    p0 = cv2.goodFeaturesToTrack(prev_gray, mask=mask, **FEATURE_PARAMS)
    if p0 is None or len(p0) < 25:
        return None, 0.0

    p1, st, _ = cv2.calcOpticalFlowPyrLK(prev_gray, gray, p0, None, **LK_PARAMS)
    p0b, st_b, _ = cv2.calcOpticalFlowPyrLK(gray, prev_gray, p1, None, **LK_PARAMS)
    fb_err = np.linalg.norm(p0 - p0b, axis=2).ravel()  # forward-backward check
    good = (st.ravel() == 1) & (st_b.ravel() == 1) & (fb_err < 1.0)
    if good.sum() < 20:
        return None, 0.0

    src, dst = p0[good].reshape(-1, 2), p1[good].reshape(-1, 2)
    H, inliers = cv2.findHomography(src, dst, cv2.RANSAC, ransacReprojThreshold=2.0)
    if H is None:
        return None, 0.0
    inlier_ratio = float(inliers.mean())

    # H ~ K R K^-1 -> R = K^-1 H K, then project to SO(3)
    R = np.linalg.inv(K) @ H @ K
    U, _, Vt = np.linalg.svd(R)
    R = U @ Vt
    if np.linalg.det(R) < 0:
        R = U @ np.diag([1, 1, -1]) @ Vt

    rvec, _ = cv2.Rodrigues(R)  # axis-angle, radians
    omega_cam = rvec.ravel() / dt  # rad/s in CAMERA frame
    conf = inlier_ratio * min(1.0, good.sum() / 60.0)
    return omega_cam, conf
