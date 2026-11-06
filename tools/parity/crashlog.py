#!/usr/bin/env python3
"""Crash / ANR evidence for the parity harness.

The driver saves the FULL crash buffer, the FULL events buffer and the FULL main buffer (logcat
-v epoch), plus /data/anr traces, as artifacts; this module only derives findings from them and never
replaces them. A finding is a whole logcat ENTRY BLOCK - consecutive lines from the same pid and tag,
e.g. "FATAL EXCEPTION", "Process: <pkg>, PID: <pid>", the exception and every stack line - attributed
to a package when ANY line of the block names the package, or when the block's pid is known to belong
to the package (from "Process: <pkg>, PID: n", am_crash / am_anr events, or a native ">>> pkg <<<"
tombstone header). So a crash cannot be lost because the line naming the package sits next to, rather
than on, the exception line. Each finding carries its device epoch time and is attributed to the
setup or leg window it fell in.
"""
import re

LINE = re.compile(r"^\s*(\d+\.\d+)\s+(?:(\d+)\s+)?(\d+)\s+(\d+)\s+([VDIWEFA])\s+([^:]*?)\s*:\s?(.*)$")
# -v epoch: "<secs.frac> <uid?> <pid> <tid> <prio> <tag>: <msg>"


def parse(text):
    out = []
    for raw in (text or "").splitlines():
        m = LINE.match(raw)
        if not m:
            if raw.startswith("--------- beginning of"):
                continue
            if out:  # continuation without a header: belongs to the previous entry
                out[-1]["msg"] += "\n" + raw
                out[-1]["raw"] += "\n" + raw
            continue
        out.append({"t": float(m.group(1)), "pid": int(m.group(3)), "tag": m.group(6), "prio": m.group(5),
                    "msg": m.group(7), "raw": raw})
    return out


def blocks(entries):
    bl = []
    for e in entries:
        if bl and bl[-1][-1]["pid"] == e["pid"] and bl[-1][-1]["tag"] == e["tag"] and e["t"] - bl[-1][-1]["t"] < 2.0:
            bl[-1].append(e)
        else:
            bl.append([e])
    return bl


def pkg_pids(pkg, entries):
    q = re.escape(pkg)
    pids = set()
    pats = [re.compile(r"Process: %s(?::\S+)?, PID: (\d+)" % q),
            re.compile(r"pid: (\d+), tid: \d+, name: .*>>> %s(?::\S+)? <<<" % q),
            re.compile(r"\[\d+,(\d+),%s(?::\S+)?," % q),            # am_crash / am_anr: [user,pid,pkg,...]
            re.compile(r"Start proc (\d+):%s(?::\S+)?/" % q)]
    for e in entries:
        for pat in pats:
            for m in pat.finditer(e["msg"]):
                pids.add(int(m.group(1)))
    return pids


KIND = (("ANR", re.compile(r"\bANR in\b|am_anr")), ("native", re.compile(r">>> .* <<<|Fatal signal|tombstone")),
        ("crash", re.compile(r"FATAL EXCEPTION|am_crash|Process: .*PID")))


def findings(pkg, crash_text, events_text, main_text=""):
    """Every crash/ANR block for pkg across the three buffers, each with its full text."""
    res = []
    all_entries = parse(crash_text) + parse(events_text) + parse(main_text)
    pids = pkg_pids(pkg, all_entries)
    for name, text in (("crash", crash_text), ("events", events_text), ("main", main_text)):
        for b in blocks(parse(text)):
            body = "\n".join(e["raw"] for e in b)
            if name == "events" and not any(e["tag"] in ("am_crash", "am_anr") for e in b):
                continue
            if name == "main" and not re.search(r"ANR in %s\b|FATAL EXCEPTION" % re.escape(pkg), body):
                continue
            named = pkg in body
            by_pid = name != "main" and b[0]["pid"] in pids
            if not (named or by_pid):
                continue
            kind = next((k for k, pat in KIND if pat.search(body)), "other")
            res.append({"buffer": name, "t": b[0]["t"], "pid": b[0]["pid"], "tag": b[0]["tag"], "kind": kind,
                        "named_in_block": named, "lines": len(b), "text": body})
    res.sort(key=lambda f: f["t"])
    return res


def attribute(fs, windows):
    """windows: list of (label, t_start, t_end) in device epoch secs. Adds f['window'] to each finding."""
    for f in fs:
        f["window"] = next((lab for lab, a, b in windows if a <= f["t"] <= b), "between_windows")
    return fs
