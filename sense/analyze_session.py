"""Score a --protocol session CSV: is the eyes-closed alpha increase real and repeatable?

    python -m sense.analyze_session data/sessions/eeg_YYYYmmdd_HHMMSS_protocol.csv

Each eyes-closed block ECn is compared to the mean of its flanking eyes-open
blocks EOn and EO(n+1), which cancels linear drift. Medians are used within
blocks so a few artifact windows that slipped past the gate can't carry a result.

Thresholds are engineering judgment for a 4-electrode consumer headband, not a
published standard. Classic occipital alpha blocking is often 2-5x in power,
but TP9/TP10 sit further from occipital cortex, and some people have
low-amplitude alpha (roughly 5-10% of adults). Hence the WEAK category.
"""

from __future__ import annotations

import csv
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List

import numpy as np

TRANSITION_DROP_S = 5.0  # 2 s window + reaction time + alpha settling after each cue
BLOCK_S = 30.0
MIN_WINDOWS_PER_BLOCK = 10

PASS_RATIO = 1.5   # EC/EO alpha power, every cycle (= +1.8 dB)
WEAK_RATIO = 1.2
PASS_D = 1.0       # Cohen's d on alpha dB, pooled EC vs EO
MAX_REJECT = 0.20
FS_RANGE = (250.0, 262.0)
MAX_GAP_FRAC = 0.001


@dataclass
class Result:
    cycles: List[Dict[str, float]] = field(default_factory=list)
    d_alpha: float = float("nan")
    overall_ratio: float = float("nan")
    reject_rate: float = float("nan")
    fs_median: float = float("nan")
    gap_frac: float = float("nan")
    reconnects: int = 0
    block_counts: Dict[str, int] = field(default_factory=dict)
    verdict: str = "FAIL"
    reasons: List[str] = field(default_factory=list)


def _cohens_d(a: np.ndarray, b: np.ndarray) -> float:
    if len(a) < 2 or len(b) < 2:
        return float("nan")
    sp = np.sqrt(((len(a) - 1) * a.var(ddof=1) + (len(b) - 1) * b.var(ddof=1)) / (len(a) + len(b) - 2))
    return float((a.mean() - b.mean()) / sp) if sp > 0 else float("nan")


def analyze(csv_path: Path) -> Result:
    rows = list(csv.DictReader(open(csv_path, newline="")))
    r = Result()
    if not rows:
        r.reasons.append("empty CSV")
        return r

    block_start: Dict[str, float] = {}
    for row in rows:  # phases are contiguous; first row of a phase ~ its start
        block_start.setdefault(row["phase"], float(row["t_rel"]))

    usable: Dict[str, Dict[str, list]] = {}
    total = rejected = 0
    for row in rows:
        ph = row["phase"]
        if not (ph.startswith("EO") or ph.startswith("EC")):
            continue
        if float(row["t_rel"]) - block_start[ph] < TRANSITION_DROP_S:
            continue
        total += 1
        if int(row["n_good"]) == 0:
            rejected += 1
            continue
        b = usable.setdefault(ph, {"alpha": [], "theta": []})
        b["alpha"].append(float(row["alpha_uv2"]))
        b["theta"].append(float(row["theta_uv2"]))

    r.reject_rate = rejected / total if total else float("nan")
    fs = np.array([float(x["fs_eff"]) for x in rows[5:]], float)  # rate estimator needs ~5 s to fill
    r.fs_median = float(np.nanmedian(fs)) if len(fs) else float("nan")
    last = rows[-1]
    packets = int(last["packets"]) or 1
    r.gap_frac = int(last["seq_gaps"]) / packets
    r.reconnects = int(last["reconnects"])
    r.block_counts = {k: len(v["alpha"]) for k, v in sorted(usable.items())}

    med = {k: {band: float(np.median(v[band])) for band in v} for k, v in usable.items() if v["alpha"]}
    for c in (1, 2, 3):
        ec, eo_a, eo_b = f"EC{c}", f"EO{c}", f"EO{c + 1}"
        if not all(k in med and r.block_counts.get(k, 0) >= MIN_WINDOWS_PER_BLOCK for k in (ec, eo_a, eo_b)):
            r.cycles.append({"cycle": c, "alpha_ratio": float("nan"), "theta_ratio": float("nan")})
            continue
        eo = {b: (med[eo_a][b] + med[eo_b][b]) / 2 for b in ("alpha", "theta")}
        r.cycles.append({
            "cycle": c,
            "alpha_ratio": med[ec]["alpha"] / eo["alpha"],
            "theta_ratio": med[ec]["theta"] / eo["theta"],
        })

    ec_db = np.array([10 * np.log10(a) for k, v in usable.items() if k.startswith("EC") for a in v["alpha"]])
    eo_db = np.array([10 * np.log10(a) for k, v in usable.items() if k.startswith("EO") for a in v["alpha"]])
    r.d_alpha = _cohens_d(ec_db, eo_db)
    if len(ec_db) and len(eo_db):
        r.overall_ratio = float(10 ** ((np.median(ec_db) - np.median(eo_db)) / 10))

    # ---- verdict ---------------------------------------------------------
    ratios = np.array([c["alpha_ratio"] for c in r.cycles])
    thetas = np.array([c["theta_ratio"] for c in r.cycles])
    quality_ok = True
    if np.isnan(ratios).any():
        r.reasons.append(f"a cycle had <{MIN_WINDOWS_PER_BLOCK} usable windows in EC or a flanking EO block")
        quality_ok = False
    if not r.reject_rate <= MAX_REJECT:
        r.reasons.append(f"rejection rate {r.reject_rate:.0%} > {MAX_REJECT:.0%} (fit/contact)")
        quality_ok = False
    if not FS_RANGE[0] <= r.fs_median <= FS_RANGE[1]:
        r.reasons.append(f"effective sample rate {r.fs_median:.1f} Hz outside {FS_RANGE}")
        quality_ok = False
    if r.gap_frac > MAX_GAP_FRAC:
        r.reasons.append(f"packet loss {r.gap_frac:.2%} > {MAX_GAP_FRAC:.1%}")
        quality_ok = False

    if quality_ok and not np.isnan(ratios).any():
        specific = bool((ratios > thetas).all())
        if not specific:
            r.reasons.append("theta rose as much as alpha in some cycle: broadband change, suspect artifact")
        if (ratios >= PASS_RATIO).all() and r.d_alpha >= PASS_D and specific:
            r.verdict = "PASS"
        elif (ratios >= WEAK_RATIO).all() and specific:
            r.verdict = "WEAK"
            r.reasons.append(f"consistent but below {PASS_RATIO}x/d>={PASS_D}: check fit, try --channels AF7,AF8 "
                             "or TP9,TP10,AF7,AF8, or you may be a low-alpha responder")
        else:
            r.reasons.append(f"alpha EC/EO ratio below {WEAK_RATIO}x in at least one cycle")
    return r


def report(r: Result) -> bool:
    print("=== tare-p1 validation ===")
    for c in r.cycles:
        a, t = c["alpha_ratio"], c["theta_ratio"]
        a_txt = f"{a:5.2f}x ({10*np.log10(a):+5.1f} dB)" if a == a else "  n/a"
        t_txt = f"{t:5.2f}x" if t == t else "  n/a"
        print(f"cycle {c['cycle']}: alpha EC/EO = {a_txt}   theta EC/EO = {t_txt}")
    print(f"overall alpha EC/EO (median) = {r.overall_ratio:.2f}x   Cohen's d (alpha dB) = {r.d_alpha:.2f}")
    print(f"usable windows per block: {r.block_counts}")
    print(f"rejected windows: {r.reject_rate:.0%}   fs_eff median: {r.fs_median:.1f} Hz   "
          f"packet loss: {r.gap_frac:.3%}   reconnects: {r.reconnects}")
    print(f"VERDICT: {r.verdict}")
    for why in r.reasons:
        print(f"  - {why}")
    return r.verdict == "PASS"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(0 if report(analyze(Path(sys.argv[1]))) else 1)
