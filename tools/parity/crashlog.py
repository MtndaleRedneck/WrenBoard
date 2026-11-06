#!/usr/bin/env python3
"""Crash / ANR evidence for the parity harness.

The driver saves the FULL crash buffer, the FULL events buffer and the FULL main buffer (logcat
-v epoch), plus /data/anr traces, as artifacts; this module only derives findings from them and never
replaces them. A finding is a whole logcat ENTRY BLOCK - consecutive lines from the same pid and tag,
e.g. "FATAL EXCEPTION", "Process: <pkg>, PID: <pid>", the exception and every stack line - attributed
to a package when ANY line of the block names the package, or when the block's pid is known to belong
to the package (from "Process: <pkg>, PID: n", the subject fields of am_proc_start / am_crash / am_anr
events, ActivityManager "Start proc", or a native ">>> pkg <<<" tombstone header; see pkg_pids). So a crash cannot be lost because the line naming the package sits next to, rather
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


# Tag-aware subject layouts. Every pid the collector attributes comes from a field that names the SUBJECT
# process, by the exact layout of its tag; the pid column of the logging entry is used only for blocks the
# crashing process wrote itself (the crash buffer). system_server writes every am_* event and ActivityManager
# line, so its pid is never a subject; a uid is never read as a pid.
#   am_proc_start [user, pid, uid, process, type, component]   (pid 1, process 3)
#   am_proc_bound [user, pid, process]                          (pid 1, process 2)
#   am_crash      [user, pid, process, flags, exception, ...]   (pid 1, process 2)
#   am_anr        [user, pid, package, flags, reason]           (pid 1, package 2)
# Other "[n,n,pkg" events carry another process's pid or a uid there (notification_cancel_all
# [uid, callingPid, pkg, ...], am_pss [pid, uid, process, ...]) and are never read for pids.
PROC_EVENTS = {"am_proc_start": (1, 3), "am_proc_bound": (1, 2), "am_crash": (1, 2), "am_anr": (1, 2)}
SUBJECT_EVENTS = ("am_crash", "am_anr")


def _proc(pkg):
    """The package's own process names: pkg or pkg:<name>, matched whole."""
    return re.compile(r"%s(?::[A-Za-z0-9_.]+)?" % re.escape(pkg))


def _pr(pkg):
    return r"%s(?::[A-Za-z0-9_.]+)?" % re.escape(pkg)


def event_fields(e):
    """The bracketed payload of an events entry as a list of strings, or None."""
    m = re.match(r"\s*\[(.*)\]\s*$", e["msg"])
    return [x.strip() for x in m.group(1).split(",")] if m else None


def event_subject(e):
    """(pid, process) of a PROC_EVENTS entry, read from its payload by the tag's layout; else None."""
    if e["tag"] not in PROC_EVENTS:
        return None
    f = event_fields(e)
    ip, iname = PROC_EVENTS[e["tag"]]
    if not f or len(f) <= max(ip, iname) or not f[ip].isdigit():
        return None
    return int(f[ip]), f[iname]


def subject_patterns(pkg):
    """Lines a process of pkg is the SUBJECT of (pid group 1 where there is one):
    Java "Process: <pkg>, PID: <pid>", native tombstone "pid: N, tid: N, name: ... >>> <pkg> <<<",
    ActivityManager "Start proc <pid>:<pkg>/", and ActivityManager "ANR in <pkg>" (no pid)."""
    p = _pr(pkg)
    return {"java": re.compile(r"Process: %s, PID: (\d+)(?!\d)" % p),
            "native": re.compile(r"pid: (\d+), tid: \d+, name: .*>>> %s <<<" % p),
            "start": re.compile(r"Start proc (\d+):%s/" % p),
            "anr_in": re.compile(r"\bANR in %s(?![A-Za-z0-9_.:])" % p)}


def pkg_pids(pkg, entries):
    """Pids of pkg's OWN processes only, each from a subject field by the exact layout of its line or tag:
    the PROC_EVENTS payload (process field equal to pkg or pkg:<name>), Java "Process: pkg, PID: n",
    the native tombstone header, ActivityManager "Start proc n:pkg/". Never an entry's own pid column,
    never a uid."""
    proc, pats = _proc(pkg), subject_patterns(pkg)
    pids = set()
    for e in entries:
        if e["tag"] in PROC_EVENTS:
            s = event_subject(e)
            if s and proc.fullmatch(s[1]):
                pids.add(s[0])
            continue
        for k in ("java", "native", "start"):
            for m in pats[k].finditer(e["msg"]):
                pids.add(int(m.group(1)))
    return pids


KIND = (("ANR", re.compile(r"\bANR in\b|am_anr")), ("native", re.compile(r">>> .* <<<|Fatal signal|tombstone")),
        ("crash", re.compile(r"FATAL EXCEPTION|am_crash|Process: .*PID")))


def findings(pkg, crash_text, events_text, main_text=""):
    """Every crash/ANR finding for pkg across the three buffers, each with its full text.
    crash buffer: a whole entry block, attributed when a subject line of the block names pkg (Java
    "Process: pkg, PID", native ">>> pkg <<<") or when the block's own pid (the crashing process wrote it)
    is one of pkg's pids. events buffer: each am_crash / am_anr entry on its own, attributed only by its
    payload's subject package or subject pid, never by the emitting (system_server) pid. main buffer: an
    ActivityManager "ANR in pkg" block, or a FATAL EXCEPTION block whose "Process:" line names pkg."""
    res = []
    all_entries = parse(crash_text) + parse(events_text) + parse(main_text)
    pids = pkg_pids(pkg, all_entries)
    proc, pats = _proc(pkg), subject_patterns(pkg)
    def add(name, b, named):
        body = "\n".join(e["raw"] for e in b)
        kind = next((k for k, pat in KIND if pat.search(body)), "other")
        res.append({"buffer": name, "t": b[0]["t"], "pid": b[0]["pid"], "tag": b[0]["tag"], "kind": kind,
                    "named_in_block": named, "lines": len(b), "text": body})
    for e in parse(events_text):
        if e["tag"] not in SUBJECT_EVENTS:
            continue
        s = event_subject(e)
        if s and (proc.fullmatch(s[1]) or s[0] in pids):
            add("events", [e], bool(proc.fullmatch(s[1])))
            res[-1]["subject_pid"] = s[0]
    for b in blocks(parse(crash_text)):
        body = "\n".join(e["raw"] for e in b)
        named = bool(pats["java"].search(body) or pats["native"].search(body))
        if named or b[0]["pid"] in pids:
            add("crash", b, named)
    for b in blocks(parse(main_text)):
        body = "\n".join(e["raw"] for e in b)
        named = bool(pats["anr_in"].search(body) or ("FATAL EXCEPTION" in body and pats["java"].search(body)))
        if named:
            add("main", b, True)
    res.sort(key=lambda f: f["t"])
    return res


def anr_trace_findings(pkg, traces):
    """traces: {file name: text} of /data/anr files. A trace is pkg's when its SUBJECT is a process of pkg:
    the pid of the "----- dumping pid: N" header (else the first "----- pid N" section) and that section's
    "Cmd line:" equal to pkg or pkg:<name>. Other processes dumped in the same file are never the subject."""
    proc, res = _proc(pkg), []
    for name, text in sorted((traces or {}).items()):
        text = text or ""
        secs = re.findall(r"^----- pid (\d+) at [^\n]*-----\nCmd line: (\S+)", text, re.M)
        d = re.search(r"^----- dumping pid: (\d+)", text, re.M)
        subj = int(d.group(1)) if d else (int(secs[0][0]) if secs else None)
        cmd = next((c for p, c in secs if int(p) == subj), None)
        if subj is not None and cmd and proc.fullmatch(cmd):
            sm = re.search(r"^Subject: (.*)$", text, re.M)
            res.append({"buffer": "anr_trace", "file": name, "t": None, "pid": subj, "tag": "anr_trace", "kind": "ANR",
                        "named_in_block": True, "lines": text.count("\n"),
                        "text": "trace %s: pid %d Cmd line %s; Subject: %s" % (name, subj, cmd, sm.group(1) if sm else "?")})
    return res


def attribute(fs, windows):
    """windows: list of (label, t_start, t_end) in device epoch secs. Adds f['window'] to each finding."""
    for f in fs:
        f["window"] = next((lab for lab, a, b in windows if a <= f["t"] <= b), "between_windows")
    return fs
