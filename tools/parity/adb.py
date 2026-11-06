#!/usr/bin/env python3
"""Small adb layer for the parity harness. Every command is an argument list; a nonzero exit or
a timeout raises CommandError, so no failure is silently ignored. Device shell strings are built
only from integers and fixed constants."""
import os, re, subprocess, time
import xml.etree.ElementTree as ET

OUT = os.environ.get("PARITY_OUT", "shots")
TPKG = "com.wrenboard.imetarget"


class CommandError(Exception):
    pass


def log(*a):
    line = " ".join(str(x) for x in a)
    print(line, flush=True)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "run.log"), "a") as f:
        f.write(line + "\n")


_server = []


def start_server():
    """Start the adb server with no pipes attached, so no daemon ever holds a captured pipe open."""
    if not _server:
        subprocess.run(["adb", "start-server"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=60, start_new_session=True)
        _server.append(True)


def run(cmd, timeout=60, check=True):
    if cmd and cmd[0] == "adb":
        start_server()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise CommandError("timed out after %ss: %s" % (timeout, " ".join(cmd)[:200]))
    if check and r.returncode != 0:
        raise CommandError("exit %d: %s :: %s" % (r.returncode, " ".join(cmd)[:200], (r.stderr or r.stdout)[-400:]))
    return r.stdout


def sh(cmd, timeout=60, check=True):
    return run(["adb", "shell", cmd], timeout, check)


def save(rel, text):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        f.write(text)
    return p


def shot(name):
    p = os.path.join(OUT, "png", name + ".png")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        r = subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=30)
    if r.returncode != 0 or os.path.getsize(p) == 0:
        raise CommandError("screencap failed for %s" % name)
    return p


def tap(x, y, wait=0.5):
    sh("input tap %d %d" % (int(x), int(y))); time.sleep(wait)


def keyevent(code, wait=0.6):
    if not re.fullmatch(r"KEYCODE_[A-Z0-9_]+", code):
        raise ValueError(code)
    sh("input keyevent %s" % code); time.sleep(wait)


def bounds(n):
    m = re.findall(r"\d+", n.get("bounds", ""))
    return tuple(int(v) for v in m) if len(m) == 4 else (0, 0, 0, 0)


def centre(n):
    b = bounds(n)
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def dump_windows(tag):
    sh("rm -f /sdcard/parity_w.xml", check=False)
    sh("uiautomator dump --windows /sdcard/parity_w.xml", 40, check=False)
    x = run(["adb", "exec-out", "cat", "/sdcard/parity_w.xml"], 30, check=False)
    save("ui/%s.xml" % tag, x)
    try:
        return ET.fromstring(x[x.find("<"):])
    except ET.ParseError:
        return None


def labelled(root, pkg):
    out = []
    if root is None:
        return out
    for n in root.iter("node"):
        if pkg and n.get("package") != pkg:
            continue
        d = (n.get("content-desc") or n.get("text") or "").strip()
        if d:
            out.append((d, n))
    return out


def field_log():
    return run(["adb", "logcat", "-d", "-v", "epoch", "-s", "IMETARGET:I"], 30)


def field_events(since=0.0):
    """(device epoch, field text) for every change the test-input app logged at or after `since`."""
    ev = []
    for l in field_log().splitlines():
        m = re.match(r"\s*(\d+\.\d+)\s", l)
        if not m or float(m.group(1)) < since:
            continue
        f = re.search(r"FIELD=(.*)$", l)
        if f:
            ev.append((float(m.group(1)), f.group(1)))
        elif "CLEARED" in l:
            ev.append((float(m.group(1)), ""))
    return ev


def field(since=0.0):
    ev = field_events(since)
    return ev[-1][1] if ev else ""


_marks = [0]


def mark(label):
    """Write a marker into the device log and return its device epoch time. Nothing is ever cleared
    after setup: evidence windows are [mark, mark] intervals over the preserved buffers."""
    if not re.fullmatch(r"[A-Za-z0-9_:.-]{1,60}", label):
        raise ValueError("bad mark label")
    _marks[0] += 1
    tok = "M%d_%s" % (_marks[0], label)
    sh("log -t PARITYMARK %s" % tok)
    for _ in range(10):
        for l in run(["adb", "logcat", "-d", "-v", "epoch", "-s", "PARITYMARK:I"], 30).splitlines():
            if l.rstrip().endswith(" " + tok):
                return float(l.split()[0])
        time.sleep(0.3)
    raise CommandError("marker %s never appeared in logcat" % tok)


def logs(buffer, since=None, until=None, filters=()):
    """Full -v epoch dump of one buffer (main, crash, events), optionally cut to [since, until]."""
    out = run(["adb", "logcat", "-d", "-b", buffer, "-v", "epoch"] + (["-s"] + list(filters) if filters else []), 120)
    if since is None and until is None:
        return out
    keep = []
    for l in out.splitlines():
        m = re.match(r"\s*(\d+\.\d+)\s", l)
        if m:
            t = float(m.group(1))
            inside = (since is None or t >= since) and (until is None or t <= until)
        if (m and inside) or (not m and keep):
            keep.append(l)
    return "\n".join(keep) + ("\n" if keep else "")


def clear_field():
    sh("am broadcast -a com.wrenboard.imetarget.CLEAR -p %s" % TPKG); time.sleep(0.8)
