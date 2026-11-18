"""Band power and per-window data quality gate for 4-channel Muse EEG.

Method: Welch PSD (Hann, 1 s segments, 50% overlap, linear detrend), then sum
PSD bins inside each band. Chosen over bandpass + Hilbert envelope because:

* One FFT gives every band plus out-of-band reference power (delta for blinks,
  >30 Hz for muscle), which the quality gate and tare-p2 feature extraction
  both need. Hilbert needs one filter per band.
* A selective 4-8 / 8-12 Hz bandpass needs ~0.5-1 s of filter taps. In a 1-2 s
  window the filter transient eats most of the data.
* Band power per window is what tare-p2 stores in SQLite; Welch output maps
  onto it directly.

Bands are half-open [lo, hi) so theta and alpha both get 4 bins at 1 Hz resolution.
"""

from __future__ import annotations

from typing import Dict, List, Sequence, Tuple

import numpy as np
from scipy.signal import detrend, welch

BANDS: Dict[str, Tuple[float, float]] = {"theta": (4.0, 8.0), "alpha": (8.0, 12.0)}


def band_powers(
    x: np.ndarray, fs: float, bands: Dict[str, Tuple[float, float]] = BANDS
) -> Dict[str, np.ndarray]:
    """Absolute band power (uV^2) per channel. ``x`` is (n_samples, n_channels)."""
    nperseg = min(int(fs), x.shape[0])  # 1 s segments -> 1 Hz bins
    f, pxx = welch(
        x, fs=fs, window="hann", nperseg=nperseg, noverlap=nperseg // 2, detrend="linear", axis=0
    )
    df = f[1] - f[0]
    return {name: pxx[(f >= lo) & (f < hi)].sum(axis=0) * df for name, (lo, hi) in bands.items()}


def channel_quality(
    x: np.ndarray,
    full_scale_uv: float,
    max_ptp_uv: float = 200.0,
    min_std_uv: float = 0.5,
) -> List[str]:
    """Per-channel reject reason ('' = good) for one window of raw (n, C) uV samples.

    * ``rail``: ADC saturated (0 or full scale): electrode off skin.
    * ``ptp``: peak-to-peak after detrend > max_ptp_uv: blink, jaw clench, movement.
    * ``flat``: std < min_std_uv: dead channel or stuck decoder.
    """
    railed = (x <= 1e-3).any(axis=0) | (x >= full_scale_uv - 1e-3).any(axis=0)
    xd = detrend(x, axis=0, type="linear")
    ptp = np.ptp(xd, axis=0)
    std = xd.std(axis=0)
    out = []
    for c in range(x.shape[1]):
        out.append("rail" if railed[c] else "ptp" if ptp[c] > max_ptp_uv else "flat" if std[c] < min_std_uv else "")
    return out


def aggregate(
    powers: Dict[str, np.ndarray], quality: Sequence[str], use: Sequence[int]
) -> Tuple[Dict[str, float], List[int]]:
    """Mean band power over the selected channels that passed the gate.

    Returns (band -> uV^2 or NaN if no good channel, list of good channel indices).
    """
    good = [c for c in use if quality[c] == ""]
    if not good:
        return {b: float("nan") for b in powers}, good
    return {b: float(np.mean(p[good])) for b, p in powers.items()}, good
