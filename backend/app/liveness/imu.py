"""Raw gyro resampling and bandpass filtering (plan.md §5.3).

Gyro is used raw -- never integrated. Integration accumulates bias and forces comparing
drifting orientations; comparing angular velocities directly is the numerically stable
choice.
"""

import numpy as np
from scipy.signal import butter, sosfiltfilt


def resample(t_ns, v, t_grid_ns):
    t = np.asarray(t_ns, float)
    return np.stack([np.interp(t_grid_ns, t, v[:, i]) for i in range(v.shape[1])], axis=1)


def bandpass(x, fs=50.0, lo=0.4, hi=8.0):
    """Human hand/head motion lives in ~0.4-8 Hz. Low cut removes gyro bias drift and slow
    scene change; high cut removes sensor noise and optical-flow jitter."""
    sos = butter(4, [lo / (fs / 2), hi / (fs / 2)], btype="band", output="sos")
    return sosfiltfilt(sos, x, axis=0)
