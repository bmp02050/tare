"""tare-p1: stream Muse S Athena EEG and show alpha/theta band power in real time.

Usage (from repo root):
    python -m sense.stream_eeg                    # live plot + stdout
    python -m sense.stream_eeg --protocol         # guided eyes-open/closed validation, auto-analysed
    python -m sense.stream_eeg --no-plot          # headless (stdout + CSV only)
    python -m sense.stream_eeg --simulate --protocol   # no hardware: synthetic EEG, full pipeline

Every run writes a per-second CSV to data/sessions/ and (live only) the raw BLE
payloads to data/raw/ in Amused's MUSB format, so a session can be re-decoded later.

Plot choice: matplotlib in-process, refreshed at 1 Hz. The data rate the plot
sees is a few numbers per second, so a browser stack (server + websocket + JS)
adds moving parts with no gain at tare-p1. The cost: matplotlib needs a
display, so on a headless box use --no-plot. When the phone becomes the hub
(tare-p2+), the UI moves to Android anyway.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import logging
import math
import platform
import shutil
import subprocess
import sys
import threading
import time
from collections import deque
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np

from sense.bandpower import BANDS, aggregate, band_powers, channel_quality
from sense.muse_athena import (
    EEG_CHANNELS,
    EEG_FS,
    EEG_FULL_SCALE_UV,
    EEGBuffer,
    MuseAthenaStream,
    SimulatedStream,
)

REPO = Path(__file__).resolve().parents[1]
log = logging.getLogger("tare.sense")

# ---------------------------------------------------------------------------
# Validation protocol: every eyes-closed block is flanked by eyes-open blocks,
# so slow drift (electrode settling, drowsiness) can't masquerade as an EC effect.
# ---------------------------------------------------------------------------
BLOCK_S = 30
PROTOCOL: List[Tuple[str, int]] = [("SETTLE", BLOCK_S)]
for _c in range(1, 4):
    PROTOCOL += [(f"EO{_c}", BLOCK_S), (f"EC{_c}", BLOCK_S)]
PROTOCOL += [("EO4", BLOCK_S)]
PROTOCOL_S = sum(d for _, d in PROTOCOL)


def phase_at(t: float) -> Optional[str]:
    acc = 0.0
    for name, dur in PROTOCOL:
        acc += dur
        if t < acc:
            return name
    return None


def cue(phase: str) -> None:
    """Audible cue that works with eyes closed. Speech if available, else beeps."""
    if phase.startswith("EC"):
        words, beeps = "close your eyes", 1
    elif phase.startswith("EO"):
        words, beeps = "open your eyes", 2
    else:
        words, beeps = "settle. eyes open, relax your jaw", 3
    print(f"\n>>> {phase}: {words.upper()} <<<\n", flush=True)
    system = platform.system()
    speaker = shutil.which("spd-say") or (shutil.which("say") if system == "Darwin" else None)
    try:
        if speaker:
            subprocess.Popen([speaker, words], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        if system == "Windows":
            import winsound

            def _beep():
                for _ in range(beeps):
                    winsound.Beep(880, 200)
                    time.sleep(0.15)

            threading.Thread(target=_beep, daemon=True).start()
            return
    except Exception:
        pass
    sys.stdout.write("\a" * beeps)
    sys.stdout.flush()


# ---------------------------------------------------------------------------
# Per-second processing
# ---------------------------------------------------------------------------
class Processor:
    def __init__(self, args, buffer: EEGBuffer, source, t0: float, csv_path: Path):
        self.args = args
        self.buffer = buffer
        self.source = source
        self.t0 = t0
        self.win_n = int(args.window * EEG_FS)
        self.use = [EEG_CHANNELS.index(c) for c in args.channels]
        self.rate_hist: deque = deque(maxlen=6)  # (t, total) for 5 s effective-rate estimate
        self.hist = {k: deque(maxlen=args.plot_seconds) for k in ("t", "alpha", "theta", "ratio", "phase")}
        self.last_phase: Optional[str] = None
        self.done = False
        self.n_rows = 0
        self.n_rejected = 0

        csv_path.parent.mkdir(parents=True, exist_ok=True)
        self.csv_f = open(csv_path, "w", newline="")
        self.csv = csv.writer(self.csv_f)
        cols = ["t_wall", "t_rel", "phase", "fs_eff", "n_good", "alpha_uv2", "theta_uv2", "alpha_theta"]
        for ch in EEG_CHANNELS:
            cols += [f"{ch}_alpha", f"{ch}_theta", f"{ch}_q"]
        cols += ["packets", "seq_gaps", "unknown_tags", "reconnects"]
        self.csv.writerow(cols)

    def close(self) -> None:
        self.csv_f.close()

    def tick(self) -> None:
        now = time.monotonic()
        t_rel = now - self.t0

        phase = phase_at(t_rel) if self.args.protocol else "LIVE"
        if self.args.protocol and phase is None:
            self.done = True
            return
        if phase != self.last_phase and self.args.protocol:
            cue(phase)
        self.last_phase = phase

        self.rate_hist.append((now, self.buffer.total))
        (ta, na), (tb, nb) = self.rate_hist[0], self.rate_hist[-1]
        fs_eff = (nb - na) / (tb - ta) if tb > ta else float("nan")

        st = self.source.stats
        x = self.buffer.latest(self.win_n)
        if x is None:
            print(f"t={t_rel:5.0f}s  waiting for data... connected={self.source.connected} "
                  f"packets={st.packets}", flush=True)
            return

        powers = band_powers(x, EEG_FS)
        quality = channel_quality(x, EEG_FULL_SCALE_UV, max_ptp_uv=self.args.max_ptp)
        agg, good = aggregate(powers, quality, self.use)
        alpha, theta = agg["alpha"], agg["theta"]
        ratio = alpha / theta if good else float("nan")
        self.n_rows += 1
        self.n_rejected += 0 if good else 1

        qtxt = " ".join(f"{EEG_CHANNELS[c]}:{quality[c] or 'ok'}" for c in range(4))
        if good:
            body = (f"alpha={10*math.log10(alpha):6.2f}dB  theta={10*math.log10(theta):6.2f}dB  "
                    f"a/t={ratio:5.2f}")
        else:
            body = "REJECTED (no selected channel passed the gate)".ljust(48)
        print(f"t={t_rel:5.0f}s {phase:>6}  fs={fs_eff:6.1f}Hz  {body}  [{qtxt}]  "
              f"gaps={st.seq_gaps}", flush=True)

        row = [dt.datetime.now().isoformat(timespec="milliseconds"), f"{t_rel:.3f}", phase,
               f"{fs_eff:.2f}", len(good), f"{alpha:.4f}", f"{theta:.4f}", f"{ratio:.4f}"]
        for c in range(4):
            row += [f"{powers['alpha'][c]:.4f}", f"{powers['theta'][c]:.4f}", quality[c] or "ok"]
        row += [st.packets, st.seq_gaps, sum(st.unknown_tags.values()), self.source.reconnects]
        self.csv.writerow(row)
        self.csv_f.flush()

        h = self.hist
        h["t"].append(t_rel)
        h["alpha"].append(10 * math.log10(alpha) if good else np.nan)
        h["theta"].append(10 * math.log10(theta) if good else np.nan)
        h["ratio"].append(ratio)
        h["phase"].append(phase)


def run_headless(proc: Processor) -> None:
    next_t = time.monotonic() + 1.0
    while not proc.done:
        time.sleep(max(0.0, next_t - time.monotonic()))
        next_t += 1.0
        proc.tick()


def run_plot(proc: Processor) -> None:
    import matplotlib.pyplot as plt
    from matplotlib.animation import FuncAnimation

    fig, (ax_p, ax_r) = plt.subplots(2, 1, sharex=True, figsize=(10, 6))
    fig.canvas.manager.set_window_title("tare-p1 - Muse S Athena")
    (l_a,) = ax_p.plot([], [], label="alpha 8-12 Hz", color="tab:blue")
    (l_t,) = ax_p.plot([], [], label="theta 4-8 Hz", color="tab:orange")
    (l_r,) = ax_r.plot([], [], color="tab:green")
    ax_p.set_ylabel("band power (dB re 1 uV^2)")
    ax_p.legend(loc="upper left")
    ax_r.set_ylabel("alpha / theta")
    ax_r.set_xlabel("time (s)")
    ax_p.grid(alpha=0.3)
    ax_r.grid(alpha=0.3)
    spans: list = []

    def update(_frame):
        proc.tick()
        if proc.done:
            plt.close(fig)
            return
        h = proc.hist
        if not h["t"]:
            return
        t = np.fromiter(h["t"], float)
        l_a.set_data(t, np.fromiter(h["alpha"], float))
        l_t.set_data(t, np.fromiter(h["theta"], float))
        l_r.set_data(t, np.fromiter(h["ratio"], float))
        for s in spans:
            s.remove()
        spans.clear()
        ph = list(h["phase"])
        i = 0
        while i < len(ph):  # shade eyes-closed blocks
            if ph[i].startswith("EC"):
                j = i
                while j + 1 < len(ph) and ph[j + 1] == ph[i]:
                    j += 1
                for ax in (ax_p, ax_r):
                    spans.append(ax.axvspan(t[i] - 0.5, t[j] + 0.5, color="0.85", zorder=0))
                i = j
            i += 1
        ax_p.set_xlim(max(0.0, t[-1] - proc.args.plot_seconds), max(10.0, t[-1] + 1))
        for ax in (ax_p, ax_r):
            ax.relim()
            ax.autoscale_view(scalex=False)
        st = proc.source.stats
        fig.suptitle(f"{proc.last_phase}   packets={st.packets}  gaps={st.seq_gaps}  "
                     f"fw={proc.source.device_info.get('fw', '?')}  "
                     f"batt={proc.source.device_info.get('bp', '?')}%", fontsize=10)

    anim = FuncAnimation(fig, update, interval=1000, cache_frame_data=False)  # noqa: F841 (keep ref)
    plt.show()


# ---------------------------------------------------------------------------
def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--address", help="BLE address (Linux/Win: MAC; macOS: UUID). Default: first 'Muse*' device")
    p.add_argument("--preset", default="p1034", choices=["p21", "p1034", "p1035"],
                   help="Athena preset; p1034 is what Amused validated (default)")
    p.add_argument("--channels", default="TP9,TP10",
                   help="channels averaged for band power (default TP9,TP10: strongest eyes-closed alpha on Muse)")
    p.add_argument("--window", type=float, default=2.0,
                   help="analysis window in seconds, updated every 1 s (default 2.0; 1.0 = single-segment periodogram)")
    p.add_argument("--max-ptp", type=float, default=200.0, help="artifact gate: max peak-to-peak uV per window")
    p.add_argument("--protocol", action="store_true", help=f"run the {PROTOCOL_S}s eyes-open/closed validation")
    p.add_argument("--simulate", action="store_true", help="synthetic EEG; no hardware or Bluetooth needed")
    p.add_argument("--no-plot", action="store_true")
    p.add_argument("--no-raw", action="store_true", help="don't save raw BLE payloads")
    p.add_argument("--plot-seconds", type=int, default=300)
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)
    args.channels = [c.strip().upper() for c in args.channels.split(",")]
    bad = [c for c in args.channels if c not in EEG_CHANNELS]
    if bad:
        p.error(f"unknown channel(s) {bad}; choose from {EEG_CHANNELS}")
    if not 1.0 <= args.window <= 10.0:
        p.error("--window must be 1-10 s")
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s", datefmt="%H:%M:%S")
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    tag = "sim" if args.simulate else "eeg"
    csv_path = REPO / "data" / "sessions" / f"{tag}_{stamp}{'_protocol' if args.protocol else ''}.csv"

    buffer = EEGBuffer(seconds=30)
    raw = None
    t0_box = [time.monotonic()]  # reset when the first sample arrives

    if args.simulate:
        source = SimulatedStream(buffer, eyes_closed=lambda: (phase_at(time.monotonic() - t0_box[0]) or "").startswith("EC"))
    else:
        on_raw = None
        if not args.no_raw:
            from muse_raw_stream import MuseRawStream  # Amused's MUSB format

            raw_path = REPO / "data" / "raw" / f"muse_{stamp}.bin"
            raw_path.parent.mkdir(parents=True, exist_ok=True)
            raw = MuseRawStream(str(raw_path))
            raw.open_write()
            on_raw = lambda payload, _t: raw.write_packet(payload, dt.datetime.now())  # noqa: E731
            log.info("raw payloads -> %s", raw_path)
        source = MuseAthenaStream(buffer, address=args.address, preset=args.preset, on_raw=on_raw)

    source.start()
    print("waiting for first EEG samples (Ctrl+C to quit)...", flush=True)
    try:
        while buffer.total == 0:
            time.sleep(0.1)
        # Protocol clock starts only once data flows, so connect time doesn't eat the settle block.
        t0_box[0] = time.monotonic()
        proc = Processor(args, buffer, source, t0_box[0], csv_path)
        log.info("session CSV -> %s", csv_path)
        try:
            if args.no_plot:
                run_headless(proc)
            else:
                run_plot(proc)
        finally:
            proc.close()
    except KeyboardInterrupt:
        print("\ninterrupted")
    finally:
        source.stop()
        if raw is not None:
            raw.close()

    st = source.stats
    print(f"\nsession: packets={st.packets} eeg_samples={st.eeg_samples} seq_gaps={st.seq_gaps} "
          f"unknown_tags={dict((hex(k), v) for k, v in st.unknown_tags.items())} "
          f"reconnects={source.reconnects} fw={source.device_info.get('fw')}")
    if csv_path.exists():
        print(f"CSV: {csv_path}")
        if args.protocol:
            from sense.analyze_session import analyze, report

            print()
            return 0 if report(analyze(csv_path)) else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
