"""Tare notice log: one-tap self-report server. Standard library only.

    python -m sense.selfreport.server                         # http://127.0.0.1:8765
    python -m sense.selfreport.server --data ~/storage/shared/tare   # Termux: CSV visible to Android
    python -m sense.selfreport.server --host 0.0.0.0          # LAN (PC/Pi): requires the printed key

Data: <data>/notices.csv, append-only. Rows are never edited in place:
  event    you tapped a button          (label, optional intensity/note via amend)
  checkin  answered a random check-in   (label or "none", aware_before yes/no)
  amend    adds intensity/note to an earlier row (ref = that row's id)
  void     undoes an earlier row        (ref = that row's id)
  prompt   a random check-in notification was sent (Termux only)
summary.py resolves amend/void into a clean view.

Random check-ins use termux-notification (Termux:API). Elsewhere they are skipped.
To customise buttons, copy labels.json to <data>/labels.json and edit it there.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import ipaddress
import json
import os
import random
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, List, Optional
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
FIELDS = ["id", "kind", "t_client", "t_server", "label", "intensity", "aware_before", "note", "ref"]
KINDS = {"event", "checkin", "amend", "void"}
ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,40}$")
MAX_NOTE = 500


def now_iso() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


class Store:
    """Append-only CSV with id de-duplication (offline retries can resend a row)."""

    def __init__(self, data_dir: Path):
        self.dir = data_dir
        self.dir.mkdir(parents=True, exist_ok=True)
        self.path = self.dir / "notices.csv"
        self.lock = threading.Lock()
        self.seen: set = set()
        if self.path.exists():
            with open(self.path, newline="", encoding="utf-8") as f:
                self.seen = {r["id"] for r in csv.DictReader(f)}
        else:
            with open(self.path, "w", newline="", encoding="utf-8") as f:
                csv.writer(f).writerow(FIELDS)

    def append(self, row: Dict[str, str]) -> bool:
        with self.lock:
            if row["id"] in self.seen:
                return False
            with open(self.path, "a", newline="", encoding="utf-8") as f:
                csv.DictWriter(f, FIELDS).writerow(row)
                f.flush()
                os.fsync(f.fileno())
            self.seen.add(row["id"])
            return True

    def rows(self) -> List[Dict[str, str]]:
        with self.lock, open(self.path, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))


def load_labels(data_dir: Path) -> dict:
    custom = data_dir / "labels.json"
    return json.loads((custom if custom.exists() else HERE / "labels.json").read_text(encoding="utf-8"))


def validate(body: dict, label_ids: set) -> Dict[str, str]:
    """Turn a client JSON body into a CSV row, or raise ValueError."""
    kind = body.get("kind")
    if kind not in KINDS:
        raise ValueError("bad kind")
    rid = str(body.get("id", ""))
    if not ID_RE.match(rid):
        raise ValueError("bad id")
    t_client = str(body.get("t_client", ""))[:40]
    try:
        dt.datetime.fromisoformat(t_client.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError("bad t_client")
    label = str(body.get("label", ""))
    if kind == "event" and label not in label_ids | {"other"}:
        raise ValueError("bad label")
    if kind == "checkin" and label not in label_ids | {"other", "none"}:
        raise ValueError("bad label")
    if kind in ("amend", "void"):
        label = ""
    intensity = str(body.get("intensity", "") or "")
    if intensity and intensity not in {"1", "2", "3", "4", "5"}:
        raise ValueError("bad intensity")
    aware = str(body.get("aware_before", "") or "")
    if aware not in {"", "yes", "no"}:
        raise ValueError("bad aware_before")
    note = " ".join(str(body.get("note", "") or "").split())[:MAX_NOTE]
    ref = str(body.get("ref", "") or "")
    if kind in ("amend", "void") and not ID_RE.match(ref):
        raise ValueError("bad ref")
    return {"id": rid, "kind": kind, "t_client": t_client, "t_server": now_iso(), "label": label,
            "intensity": intensity, "aware_before": aware, "note": note, "ref": ref}


def make_handler(store: Store, data_dir: Path, key: Optional[str]):
    class Handler(BaseHTTPRequestHandler):
        server_version = "tare-notice/1"

        def log_message(self, fmt, *args):  # keep the console quiet; data is in the CSV
            pass

        def _authed(self) -> bool:
            if key is None:
                return True
            q = parse_qs(urlparse(self.path).query)
            given = self.headers.get("X-Tare-Key") or (q.get("k") or [""])[0]
            return secrets.compare_digest(given, key)

        def _send(self, status: int, body: bytes, ctype: str, extra: Optional[dict] = None):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, obj):
            self._send(status, json.dumps(obj).encode(), "application/json")

        def do_GET(self):
            if not self._authed():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "key required"})
            path = urlparse(self.path).path
            if path in ("/", "/index.html"):
                return self._send(200, (HERE / "page.html").read_bytes(), "text/html; charset=utf-8")
            if path == "/api/config":
                return self._json(200, load_labels(data_dir))
            if path == "/api/recent":
                return self._json(200, recent(store.rows(), 10))
            if path == "/export.csv":
                return self._send(200, store.path.read_bytes(), "text/csv; charset=utf-8",
                                  {"Content-Disposition": 'attachment; filename="notices.csv"'})
            return self._json(404, {"error": "not found"})

        def do_POST(self):
            if not self._authed():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "key required"})
            if urlparse(self.path).path != "/api/log":
                return self._json(404, {"error": "not found"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
                if n > 4096:
                    raise ValueError("too large")
                body = json.loads(self.rfile.read(n) or b"{}")
                label_ids = {lab["id"] for lab in load_labels(data_dir)["labels"]}
                row = validate(body, label_ids)
            except (ValueError, json.JSONDecodeError) as e:
                return self._json(400, {"error": str(e)})
            created = store.append(row)
            return self._json(200, {"ok": True, "duplicate": not created})

    return Handler


def recent(rows: List[Dict[str, str]], n: int) -> List[dict]:
    """Last n event/checkin rows with amends applied and voided rows removed."""
    voided = {r["ref"] for r in rows if r["kind"] == "void"}
    amends: Dict[str, dict] = {}
    for r in rows:
        if r["kind"] == "amend":
            a = amends.setdefault(r["ref"], {})
            if r["intensity"]:
                a["intensity"] = r["intensity"]
            if r["note"]:
                a["note"] = r["note"]
    out = []
    for r in reversed(rows):
        if r["kind"] in ("event", "checkin") and r["id"] not in voided:
            out.append({**{k: r[k] for k in ("id", "kind", "t_client", "label", "intensity", "aware_before", "note")},
                        **amends.get(r["id"], {})})
            if len(out) >= n:
                break
    return out


# ---------------------------------------------------------------------------
# Random check-ins (Termux:API only)
# ---------------------------------------------------------------------------
def plan_day(day: dt.date, cfg: dict, rng: random.Random) -> List[dt.datetime]:
    """Pick per_day random times in [start, end) at least min_gap_min apart."""
    tz = dt.datetime.now().astimezone().tzinfo
    h0, m0 = map(int, cfg["start"].split(":"))
    h1, m1 = map(int, cfg["end"].split(":"))
    start = dt.datetime.combine(day, dt.time(h0, m0), tz)
    end = dt.datetime.combine(day, dt.time(h1, m1), tz)
    span = (end - start).total_seconds()
    gap = cfg["min_gap_min"] * 60
    for _ in range(1000):
        offs = sorted(rng.uniform(0, span) for _ in range(cfg["per_day"]))
        if all(b - a >= gap for a, b in zip(offs, offs[1:])):
            return [start + dt.timedelta(seconds=o) for o in offs]
    return [start + dt.timedelta(seconds=span * (i + 0.5) / cfg["per_day"]) for i in range(cfg["per_day"])]


def checkin_loop(store: Store, data_dir: Path, url: str, stop: threading.Event):
    notifier = shutil.which("termux-notification")
    if not notifier:
        print("check-ins: termux-notification not found; random check-ins disabled "
              "(install Termux:API app + `pkg install termux-api`)", flush=True)
        return
    rng = random.Random()
    day, pending = None, []
    while not stop.is_set():
        now = dt.datetime.now().astimezone()
        cfg = load_labels(data_dir).get("checkins", {})
        if cfg.get("per_day", 0) <= 0:
            stop.wait(300)
            continue
        if now.date() != day:
            day = now.date()
            pending = [t for t in plan_day(day, cfg, rng) if t > now]
            print("check-ins today: " + ", ".join(t.strftime("%H:%M") for t in pending), flush=True)
        if pending and now >= pending[0]:
            pending.pop(0)
            pid = "p" + secrets.token_hex(8)
            try:
                subprocess.run([notifier, "--id", "tare-checkin", "--title", "Tare check-in",
                                "--content", "How are you right now? (tap)", "--priority", "high",
                                "--sound", "--action", f"termux-open-url {url}#checkin"],
                               timeout=15, check=False)
                store.append({"id": pid, "kind": "prompt", "t_client": "", "t_server": now_iso(),
                              "label": "", "intensity": "", "aware_before": "", "note": "", "ref": ""})
            except Exception as e:
                print(f"check-in notification failed: {e}", flush=True)
        stop.wait(20)


class ExclusiveServer(ThreadingHTTPServer):
    """Refuse to share a port. http.server enables SO_REUSEADDR, which on Windows
    lets a second process bind a port that's already in use instead of failing."""

    allow_reuse_address = False

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):  # Windows
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--data", default=str(REPO / "data" / "selfreport"), help="directory for notices.csv")
    p.add_argument("--no-checkins", action="store_true")
    args = p.parse_args(argv)

    data_dir = Path(os.path.expanduser(args.data))
    store = Store(data_dir)
    key = None
    try:
        loopback = ipaddress.ip_address(args.host).is_loopback
    except ValueError:
        loopback = args.host == "localhost"
    if not loopback:
        key_file = data_dir / "key.txt"
        if not key_file.exists():
            key_file.write_text(secrets.token_urlsafe(16), encoding="utf-8")
        key = key_file.read_text(encoding="utf-8").strip()

    try:
        httpd = ExclusiveServer((args.host, args.port), make_handler(store, data_dir, key))
    except OSError as e:
        print(f"cannot listen on {args.host}:{args.port} ({e}). Port in use? Try --port 8766", flush=True)
        return 1
    local_url = f"http://127.0.0.1:{args.port}/"
    print(f"notice log: {store.path}", flush=True)
    if key:
        print(f"LAN mode: open http://<this-machine-ip>:{args.port}/?k={key}  (key also in {data_dir / 'key.txt'})",
              flush=True)
    else:
        print(f"open {local_url}", flush=True)
    stop = threading.Event()
    if not args.no_checkins:
        threading.Thread(target=checkin_loop, args=(store, data_dir, local_url, stop), daemon=True).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
