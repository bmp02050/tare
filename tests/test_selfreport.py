import csv
import datetime as dt
import json
import random
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from sense.selfreport.server import Store, make_handler, plan_day
from sense.selfreport.summary import summarize


@pytest.fixture
def server(tmp_path):
    def start(key=None):
        store = Store(tmp_path)
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(store, tmp_path, key))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        servers.append(httpd)
        return f"http://127.0.0.1:{httpd.server_address[1]}", store
    servers = []
    yield start
    for s in servers:
        s.shutdown()
        s.server_close()


def post(base, body, key=None):
    req = urllib.request.Request(base + "/api/log", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **({"X-Tare-Key": key} if key else {})})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


T = "2026-11-07T14:03:00-05:00"


def test_event_amend_void_roundtrip(server):
    base, store = server()
    assert post(base, {"id": "evt000001", "kind": "event", "t_client": T, "label": "looping"})[0] == 200
    assert post(base, {"id": "amd000001", "kind": "amend", "t_client": T, "ref": "evt000001", "intensity": "4",
                       "note": "work\nemail"})[0] == 200
    assert post(base, {"id": "evt000002", "kind": "event", "t_client": T, "label": "clear"})[0] == 200
    assert post(base, {"id": "void00001", "kind": "void", "t_client": T, "ref": "evt000002"})[0] == 200
    recent = json.load(urllib.request.urlopen(base + "/api/recent"))
    assert [r["id"] for r in recent] == ["evt000001"]
    assert recent[0]["intensity"] == "4" and recent[0]["note"] == "work email"


def test_duplicate_ids_ignored(server):
    base, store = server()
    body = {"id": "evt000001", "kind": "event", "t_client": T, "label": "wired"}
    assert post(base, body)[1]["duplicate"] is False
    assert post(base, body)[1]["duplicate"] is True
    assert len(store.rows()) == 1


@pytest.mark.parametrize("bad", [
    {"kind": "event", "t_client": T, "label": "looping"},                       # no id
    {"id": "evt000001", "kind": "nope", "t_client": T, "label": "looping"},     # bad kind
    {"id": "evt000001", "kind": "event", "t_client": T, "label": "made_up"},    # unknown label
    {"id": "evt000001", "kind": "event", "t_client": "yesterday", "label": "looping"},
    {"id": "evt000001", "kind": "amend", "t_client": T, "intensity": "9", "ref": "evt000002"},
    {"id": "evt000001", "kind": "checkin", "t_client": T, "label": "looping", "aware_before": "maybe"},
])
def test_validation_rejects(server, bad):
    base, store = server()
    assert post(base, bad)[0] == 400
    assert store.rows() == []


def test_key_required_off_loopback(server):
    base, _ = server(key="s3cret-key")
    assert post(base, {"id": "evt000001", "kind": "event", "t_client": T, "label": "looping"})[0] == 401
    assert post(base, {"id": "evt000001", "kind": "event", "t_client": T, "label": "looping"}, key="s3cret-key")[0] == 200
    with pytest.raises(urllib.error.HTTPError):
        urllib.request.urlopen(base + "/export.csv")
    assert urllib.request.urlopen(base + "/export.csv?k=s3cret-key").status == 200


def test_csv_survives_restart(server, tmp_path):
    base, _ = server()
    post(base, {"id": "evt000001", "kind": "event", "t_client": T, "label": "looping"})
    store2 = Store(tmp_path)
    assert "evt000001" in store2.seen
    with open(tmp_path / "notices.csv", newline="", encoding="utf-8") as f:
        assert next(csv.reader(f))[0] == "id"


def test_plan_day_spacing():
    cfg = {"per_day": 3, "start": "09:00", "end": "21:00", "min_gap_min": 90}
    for seed in range(50):
        ts = plan_day(dt.date(2026, 11, 7), cfg, random.Random(seed))
        assert len(ts) == 3 and all(9 <= t.hour < 21 for t in ts)
        assert all((b - a).total_seconds() >= 90 * 60 for a, b in zip(ts, ts[1:]))


def test_summary_awareness_rate():
    def row(i, kind, label="", aware="", ref="", intensity=""):
        return {"id": f"id{i:06d}", "kind": kind, "t_client": f"2026-11-0{1 + i % 3}T10:00:00-05:00",
                "t_server": "", "label": label, "intensity": intensity, "aware_before": aware, "note": "", "ref": ref}
    rows = [row(0, "prompt"), row(1, "prompt"), row(2, "prompt"), row(3, "prompt"),
            row(4, "checkin", "looping", "yes"), row(5, "checkin", "wired", "no"),
            row(6, "checkin", "worrying", "no"), row(7, "checkin", "none"),
            row(8, "event", "looping"), row(9, "amend", ref="id000008", intensity="3"),
            row(10, "event", "reactive"), row(11, "void", ref="id000010")]
    s = summarize(rows)
    assert s["events"] == 1 and s["by_label"] == {"looping": 1} and s["mean_intensity"] == {"looping": 3.0}
    assert s["checkins"] == 4 and s["response_rate"] == 1.0
    assert s["awareness_rate"] == pytest.approx(1 / 3)


def test_android_labels_match_server_labels():
    """The Android app bundles a copy of labels.json; keep the two in sync."""
    from pathlib import Path
    root = Path(__file__).resolve().parents[1]
    server = json.loads((root / "sense/selfreport/labels.json").read_text(encoding="utf-8"))
    app = json.loads((root / "android/app/src/main/assets/labels.json").read_text(encoding="utf-8"))
    assert app == server, "copy sense/selfreport/labels.json to android/app/src/main/assets/"
    for name in ("feelings.json",):
        a = (root / "sense/selfreport" / name).read_bytes()
        b = (root / "android/app/src/main/assets" / name).read_bytes()
        assert a == b, f"copy sense/selfreport/{name} to android/app/src/main/assets/"


def test_feelings_wheel_shape():
    from pathlib import Path
    root = Path(__file__).resolve().parents[1]
    d = json.loads((root / "sense/selfreport/feelings.json").read_text(encoding="utf-8"))
    leaves = [l for c in d["wheel"] for m in c["children"] for l in m["children"]]
    assert len(d["wheel"]) == 7 and len(leaves) == 82
    assert all(len(m["children"]) == 2 for c in d["wheel"] for m in c["children"])
    ids = [q["id"] for q in d["questions"]]
    assert len(ids) == len(set(ids)) and "mind" not in ids
    assert sum(q.get("always", False) for q in d["questions"]) == 1


def test_summary_wheel_and_reflections():
    from sense.selfreport.summary import summarize
    def row(i, kind, t, label="", aware="", note="", ref=""):
        return {"id": i, "kind": kind, "t_client": t, "t_server": t, "label": label, "intensity": "",
                "aware_before": aware, "note": note, "ref": ref}
    rows = [
        row("a1", "checkin", "2026-11-10T10:00:00-06:00", "looping", "no"),
        row("a2", "checkin", "2026-11-11T10:00:00-06:00", "worrying", "yes"),
        row("m1", "meta", "2026-11-16T09:00:00-06:00", "protocol_v2"),
        row("b1", "checkin", "2026-11-16T10:00:00-06:00", "feel:angry/frustrated/annoyed", "yes"),
        row("r1", "reflect", "2026-11-16T10:01:00-06:00", "trigger", note="meeting ran over", ref="b1"),
        row("r2", "reflect", "2026-11-16T10:02:00-06:00", "mind", note="looping", ref="b1"),
        row("b2", "checkin", "2026-11-16T12:00:00-06:00", "unsure"),
        row("b3", "event", "2026-11-16T13:00:00-06:00", "feel:happy/playful/silly"),
        row("r3", "reflect", "2026-11-16T13:01:00-06:00", "mind", note="", ref="b3"),
    ]
    s = summarize(rows)
    assert s["by_family"] == {"angry": 1, "happy": 1}
    assert s["top_feelings"] == {"annoyed": 1, "silly": 1}
    assert s["reflected"] == 1            # b1 answered a question; b3 only touched "mind"
    assert s["unsure"] == 1 and s["checkin_notable"] == 3
    assert s["awareness_before_v2"] == (0.5, 2) and s["awareness_after_v2"] == (1.0, 1)
    assert s["by_label"] == {}            # wheel events don't pollute the quick-tap label counts


def test_summary_body_vs_random_prompts():
    from sense.selfreport.summary import summarize
    def row(i, kind, label="", aware="", ref=""):
        return {"id": i, "kind": kind, "t_client": "2026-11-17T10:00:00-05:00", "t_server": "2026-11-17T10:00:00-05:00",
                "label": label, "intensity": "", "aware_before": aware, "note": "", "ref": ref}
    rows = [row("p1", "prompt"), row("p2", "prompt", "body"), row("p3", "prompt", "body"),
            row("c1", "checkin", "feel:sad/lonely/isolated", "yes", ref="p1"),
            row("c2", "checkin", "feel:fearful/anxious/worried", "no", ref="p2"),
            row("c3", "checkin", "none", ref="p3"),
            row("p4", "prompt", "body"), row("c4", "checkin", "notnow", ref="p4")]
    full = summarize(rows)
    s = full["by_source"]
    assert s["random"] == {"sent": 1, "answered": 1, "not_now": 0, "notable": 1, "awareness": (1.0, 1)}
    assert s["body"] == {"sent": 3, "answered": 2, "not_now": 1, "notable": 1, "awareness": (0.0, 1)}
    assert full["checkins"] == 3 and full["not_now"] == 1   # "not now" isn't an answered check-in
