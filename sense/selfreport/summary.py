"""Summarise the notice log: the pre-Tare self-recognition baseline.

    python -m sense.selfreport.summary [path/to/notices.csv]

The number that matters for the tare protocol (p5) is the check-in awareness
rate: when a random check-in catches you in a notable state, how often had you
already noticed it yourself? Collected now, before any sensing or prompting,
it is the baseline that later self-recognition is compared against.

Since protocol_v2 (a "meta" row the app writes once), check-ins are picked on a
feelings wheel (labels like "feel:angry/frustrated/annoyed") and may carry
"reflect" rows: free-text answers to open questions, latest per question wins.
The awareness rate is reported before and after that switch, because a richer
check-in can itself change how much you notice.

Prompts come in two kinds: random (label empty) and body (label "body", sent by
the watch when heart rate is well above resting while still). Check-ins opened
from a notification carry ref = that prompt's id, so each kind gets its own
response and awareness rate: do body-triggered prompts catch more notable states,
and states you hadn't noticed yet?
"""

from __future__ import annotations

import csv
import datetime as dt
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List

from sense.selfreport.server import REPO


FEEL = "feel:"
NOT_NOTABLE = {"none", "unsure", "notnow"}


def family(label: str) -> str:
    """Core feeling for wheel labels ("feel:angry/..." -> "angry"), else the label itself."""
    return label[len(FEEL):].split("/")[0] if label.startswith(FEEL) else label


def answers(rows: List[Dict[str, str]]) -> Dict[str, Dict[str, str]]:
    """ref -> {question id: latest non-empty answer}."""
    out: Dict[str, Dict[str, str]] = defaultdict(dict)
    for r in rows:
        if r["kind"] == "reflect":
            if r["note"]:
                out[r["ref"]][r["label"]] = r["note"]
            else:
                out[r["ref"]].pop(r["label"], None)
    return out


def resolve(rows: List[Dict[str, str]]) -> List[Dict[str, str]]:
    """Events and check-ins with amends applied and voided rows removed, oldest first."""
    voided = {r["ref"] for r in rows if r["kind"] == "void"}
    amends: Dict[str, dict] = defaultdict(dict)
    for r in rows:
        if r["kind"] == "amend":
            if r["intensity"]:
                amends[r["ref"]]["intensity"] = r["intensity"]
            if r["note"]:
                amends[r["ref"]]["note"] = r["note"]
    out = [{**r, **amends.get(r["id"], {})} for r in rows
           if r["kind"] in ("event", "checkin") and r["id"] not in voided]
    return sorted(out, key=lambda r: r["t_client"])


def summarize(rows: List[Dict[str, str]]) -> dict:
    live = resolve(rows)
    events = [r for r in live if r["kind"] == "event"]
    declined = [r for r in live if r["kind"] == "checkin" and r["label"] == "notnow"]
    checkins = [r for r in live if r["kind"] == "checkin" and r["label"] != "notnow"]
    prompts = sum(1 for r in rows if r["kind"] == "prompt")
    days = sorted({r["t_client"][:10] for r in live})
    n_days = 0
    if days:
        n_days = (dt.date.fromisoformat(days[-1]) - dt.date.fromisoformat(days[0])).days + 1

    by_label = Counter(r["label"] for r in events if not r["label"].startswith(FEEL))
    feelings = [r for r in live if r["label"].startswith(FEEL)]
    by_family = Counter(family(r["label"]) for r in feelings)
    by_feeling = Counter(r["label"][len(FEEL):].split("/")[-1] for r in feelings)
    ans = answers(rows)
    reflected = sum(1 for r in live if any(k != "mind" for k in ans.get(r["id"], {})))
    switch = min((r["t_server"] for r in rows if r["kind"] == "meta" and r["label"] == "protocol_v2"), default=None)
    intensity: Dict[str, List[int]] = defaultdict(list)
    for r in events:
        if r.get("intensity"):
            intensity[r["label"]].append(int(r["intensity"]))

    notable = [r for r in checkins if r["label"] not in NOT_NOTABLE]

    def rate(rs):
        yes = sum(1 for r in rs if r.get("aware_before") == "yes")
        n = sum(1 for r in rs if r.get("aware_before") in ("yes", "no"))
        return (yes / n if n else None), n

    aware_rate, _ = rate(notable)
    prompt_kind = {r["id"]: (r["label"] or "random") for r in rows if r["kind"] == "prompt"}
    by_source = {}
    for kind in ("random", "body"):
        sent = sum(1 for k in prompt_kind.values() if k == kind)
        answered = [r for r in checkins if prompt_kind.get(r.get("ref", "")) == kind]
        later = sum(1 for r in declined if prompt_kind.get(r.get("ref", "")) == kind)
        nb = [r for r in answered if r["label"] not in NOT_NOTABLE]
        by_source[kind] = {"sent": sent, "answered": len(answered), "not_now": later, "notable": len(nb),
                           "awareness": rate(nb)}
    before = rate([r for r in notable if switch and r["t_client"] < switch]) if switch else (None, 0)
    after = rate([r for r in notable if switch and r["t_client"] >= switch]) if switch else (None, 0)
    return {
        "first_day": days[0] if days else None, "last_day": days[-1] if days else None, "n_days": n_days,
        "events": len(events), "events_per_day": len(events) / n_days if n_days else 0.0,
        "by_label": dict(by_label.most_common()),
        "mean_intensity": {k: sum(v) / len(v) for k, v in intensity.items()},
        "prompts": prompts, "checkins": len(checkins), "not_now": len(declined),
        "response_rate": len(checkins) / prompts if prompts else None,
        "checkin_notable": len(notable),
        "awareness_rate": aware_rate,
        "unsure": sum(1 for r in checkins if r["label"] == "unsure"),
        "protocol_v2": switch, "awareness_before_v2": before, "awareness_after_v2": after,
        "feelings": len(feelings), "by_family": dict(by_family.most_common()),
        "top_feelings": dict(by_feeling.most_common(8)), "reflected": reflected,
        "by_source": by_source,
    }


def report(s: dict) -> None:
    if not s["n_days"]:
        print("No entries yet.")
        return
    print(f"=== notice log: {s['first_day']} to {s['last_day']} ({s['n_days']} days) ===")
    print(f"taps: {s['events']}  ({s['events_per_day']:.1f}/day)")
    for label, n in s["by_label"].items():
        mi = s["mean_intensity"].get(label)
        print(f"  {label:<10} {n:4d}" + (f"   mean strength {mi:.1f}/5" if mi else ""))
    print(f"check-ins: {s['checkins']} answered" +
          (f" of {s['prompts']} prompts ({s['response_rate']:.0%})" if s["prompts"] else "")
          + (f", {s['not_now']} 'not now'" if s["not_now"] else ""))
    if s["awareness_rate"] is not None:
        print(f"  notable state at check-in: {s['checkin_notable']}  "
              f"already noticed it yourself: {s['awareness_rate']:.0%}   <- baseline self-recognition")
    else:
        print("  no answered check-ins with a notable state yet")
    if s["unsure"]:
        print(f"  couldn't name it: {s['unsure']}")
    if s["protocol_v2"]:
        def fmt(rn):
            r, n = rn
            return f"{r:.0%} of {n}" if r is not None else "n/a"
        print(f"  awareness before wheel ({s['protocol_v2'][:10]}): {fmt(s['awareness_before_v2'])}"
              f"   after: {fmt(s['awareness_after_v2'])}")
    body = s["by_source"]["body"]
    if body["sent"]:
        for kind in ("random", "body"):
            b = s["by_source"][kind]
            r, n = b["awareness"]
            print(f"  {kind:>6} prompts: {b['sent']} sent, {b['answered']} answered, {b['not_now']} not now, {b['notable']} notable"
                  + (f", already noticed {r:.0%} of {n}" if r is not None else ""))
    if s["feelings"]:
        fam = ", ".join(f"{k} {v}" for k, v in s["by_family"].items())
        top = ", ".join(f"{k.replace('_', ' ')} {v}" for k, v in s["top_feelings"].items())
        print(f"named feelings: {s['feelings']}  ({fam})")
        print(f"  most named: {top}")
        print(f"  put into words (answered a question): {s['reflected']}")


if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "data" / "selfreport" / "notices.csv"
    with open(path, newline="", encoding="utf-8") as f:
        report(summarize(list(csv.DictReader(f))))
