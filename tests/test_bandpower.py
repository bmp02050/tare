import csv

import numpy as np

from sense.analyze_session import analyze
from sense.bandpower import aggregate, band_powers, channel_quality
from sense.stream_eeg import PROTOCOL

FS = 256
RNG = np.random.default_rng(1)


def _sig(freq, amp, n=2 * FS):
    t = np.arange(n) / FS
    x = 725 + amp * np.sin(2 * np.pi * freq * t)[:, None] + RNG.normal(0, 1, (n, 4))
    return x


def test_alpha_vs_theta_separation():
    p = band_powers(_sig(10, 20), FS)
    assert (p["alpha"] > 50 * p["theta"]).all()
    p = band_powers(_sig(6, 20), FS)
    assert (p["theta"] > 50 * p["alpha"]).all()


def test_power_scale_matches_parseval():
    # sine of amplitude A has power A^2/2
    p = band_powers(_sig(10, 20) - 725, FS)
    assert np.allclose(p["alpha"], 200, rtol=0.15)


def test_dc_offset_ignored():
    a = band_powers(_sig(10, 20), FS)["alpha"]
    b = band_powers(_sig(10, 20) + 300, FS)["alpha"]
    assert np.allclose(a, b, rtol=0.1)


def test_quality_gate():
    x = _sig(10, 5)
    x[:, 0] = 0.0                 # railed
    x[100:110, 1] += 300          # blink
    x[:, 2] = 725.0               # flat
    assert channel_quality(x, 1450.0) == ["rail", "ptp", "flat", ""]
    agg, good = aggregate(band_powers(x, FS), channel_quality(x, 1450.0), use=[0, 3])
    assert good == [3] and agg["alpha"] > 0


def _write_protocol_csv(path, ec_gain):
    t, rows = 0.0, []
    for phase, dur in PROTOCOL:
        for _ in range(dur):
            alpha = 10.0 * (ec_gain if phase.startswith("EC") else 1.0) * RNG.lognormal(0, 0.2)
            rows.append({"t_rel": t, "phase": phase, "fs_eff": 256.0, "n_good": 2, "alpha_uv2": alpha,
                         "theta_uv2": 8.0 * RNG.lognormal(0, 0.2), "packets": 4000, "seq_gaps": 0,
                         "reconnects": 0})
            t += 1.0
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def test_analyze_pass_and_fail(tmp_path):
    _write_protocol_csv(tmp_path / "pass.csv", ec_gain=3.0)
    _write_protocol_csv(tmp_path / "fail.csv", ec_gain=1.0)
    assert analyze(tmp_path / "pass.csv").verdict == "PASS"
    assert analyze(tmp_path / "fail.csv").verdict == "FAIL"
