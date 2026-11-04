#!/usr/bin/env python3
# SwiftKey reference session driver (scratch, reference only). Runs inside the emulator runner.
import subprocess, time, os, re, json, sys, glob, traceback
import xml.etree.ElementTree as ET
from PIL import Image
import numpy as np

OUT = "shots"
for d in ("", "ui", "sys", "geom", "video"):
    os.makedirs(os.path.join(OUT, d), exist_ok=True)
RF = open(os.path.join(OUT, "results.txt"), "a")
T0 = time.time()
LEGS = [l.strip() for l in (os.environ.get("SK_LEGS", "").strip() or (open("tools/skref/legs.txt").read().strip() if os.path.exists("tools/skref/legs.txt") else "")).split(",") if l.strip()]
PKG = "com.touchtype.swiftkey"
IME = PKG + "/com.touchtype.KeyboardService"
TPKG = "com.wrenboard.imetarget"
SKDIR = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "sk")
BUDGET = int(os.environ.get("LEG_BUDGET", str(60 * 60)))

def note(*a):
    s = "[%4ds] " % (time.time() - T0) + " ".join(str(x) for x in a)
    print(s, flush=True); RF.write(s + "\n"); RF.flush()

def run(args, t=90):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=t)
        return p.stdout + p.stderr
    except subprocess.TimeoutExpired:
        note("TIMEOUT", args); return ""

def sh(cmd, t=60):
    return run(["adb", "shell", cmd], t)

def save(name, text):
    with open(os.path.join(OUT, name), "w") as f: f.write(text)

def shot(name):
    p = os.path.join(OUT, name + ".png")
    for i in range(3):
        try:
            with open(p, "wb") as f:
                subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=30)
            im = Image.open(p); im.load()
            note("shot", name, im.size)
            return im.convert("RGB")
        except Exception as e:
            note("shot retry", name, e); time.sleep(1)
    return None

def tap(x, y, s=0.5):
    sh("input tap %d %d" % (x, y)); time.sleep(s)

def key(code, s=0.6):
    sh("input keyevent %s" % code); time.sleep(s)

def leg(name):
    if time.time() - T0 > BUDGET:
        note("SKIP (time budget)", name); return False
    if LEGS and name not in LEGS:
        return False
    note("== LEG", name); return True

# ---------------- UI dump helpers (app windows) ----------------
def dump(name):
    sh("rm -f /sdcard/u.xml"); sh("uiautomator dump /sdcard/u.xml", 40)
    x = run(["adb", "exec-out", "cat", "/sdcard/u.xml"], 30)
    save("ui/%s.xml" % name, x)
    try:
        return ET.fromstring(x[x.find("<?xml"):] if "<?xml" in x else x)
    except Exception:
        return None

def B(n):
    m = re.findall(r"\d+", n.get("bounds", ""))
    return tuple(int(v) for v in m) if len(m) == 4 else (0, 0, 0, 0)

def ctr(n):
    b = B(n); return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)

def texts(root):
    if root is None: return []
    return [n.get("text") or n.get("content-desc") for n in root.iter("node") if (n.get("text") or n.get("content-desc"))]

def find(root, pat, exact=False):
    if root is None: return None
    rx = re.compile(pat, re.I)
    for n in root.iter("node"):
        for k in ("text", "content-desc"):
            v = n.get(k) or ""
            if (exact and v.strip().lower() == pat.lower()) or (not exact and v and rx.search(v)):
                return n
    return None

def scroll(down=True):
    if down: sh("input swipe 540 1900 540 900 350")
    else: sh("input swipe 540 700 540 1900 300")
    time.sleep(1.0)

def tap_text(pat, name, tries=6, exact=False):
    for i in range(tries):
        r = dump("%s_%d" % (name, i))
        n = find(r, pat, exact)
        if n is not None:
            x, y = ctr(n); tap(x, y, 1.6); note("tapped '%s' at %d,%d" % (pat, x, y)); return True
        scroll(True)
    note("NOT FOUND:", pat); return False

def parents(root):
    return {c: p for p in root.iter() for c in p}

def switch_state(root, title):
    """find the switch belonging to the row titled `title`"""
    if root is None: return None, None
    par = parents(root)
    tn = None
    for n in root.iter("node"):
        if (n.get("text") or "").strip().lower() == title.lower():
            tn = n; break
    if tn is None:
        for n in root.iter("node"):
            if (n.get("text") or "").lower().startswith(title.lower()):
                tn = n; break
    if tn is None: return None, None
    a = tn
    for _ in range(6):
        a = par.get(a)
        if a is None: break
        sw = [c for c in a.iter("node") if c.get("checkable") == "true"]
        if len(sw) == 1:
            return sw[0], sw[0].get("checked") == "true"
        if len(sw) > 1: break
    return tn, None

def set_switch(title, want, name):
    for i in range(7):
        r = dump("%s_%s_%d" % (name, re.sub(r"\W", "", title)[:18], i))
        sw, st = switch_state(r, title)
        if sw is not None and st is not None:
            if st == want:
                note("SETTING %s = %s (already)" % (title, want)); return True
            x, y = ctr(sw); tap(x, y, 1.4)
            r2 = dump("%s_%s_after" % (name, re.sub(r"\W", "", title)[:18]))
            sw2, st2 = switch_state(r2, title)
            if st2 == want:
                note("SETTING %s = %s (changed)" % (title, want)); return True
            # a dialog may have opened; record and back out
            note("SETTING %s: tap did not stick (now %s); screen: %s" % (title, st2, "|".join(texts(r2))[:300]))
            shot("ui_fail_%s" % re.sub(r"\W", "", title)[:18]); key("KEYCODE_BACK", 1.0)
            return False
        scroll(True)
    note("SETTING %s: row not found" % title); return False

def to_top():
    for _ in range(4): sh("input swipe 540 600 540 2000 150")
    time.sleep(0.8)

ONBOARD = ["Skip", "Not now", "Maybe later", "No thanks", "Later", "Continue", "Accept", "Agree", "Got it",
           "OK", "Done", "Close", "Allow", "Dismiss", "Next"]

def clear_dialogs(name, rounds=6):
    for i in range(rounds):
        r = dump("%s_dlg%d" % (name, i))
        tx = texts(r)
        hit = None
        for w in ONBOARD:
            n = find(r, w, exact=True)
            if n is not None: hit = (w, n); break
        if not hit: return tx
        note("dialog: tapping '%s' (screen: %s)" % (hit[0], "|".join(tx)[:200]))
        x, y = ctr(hit[1]); tap(x, y, 2.0)
    return []

# ---------------- keyboard geometry from pixels ----------------
def kb_rows(im, top=700, dark=None, rf=0.3, cf=0.6, strip=10):
    a = np.asarray(im.convert("L")).astype(int)
    H, W = a.shape
    reg = a[top:H]
    if dark is None:
        # background = most common luminance in the bottom 40% of the screen; keys are darker
        hist = np.bincount(reg[-int(H * 0.4):].ravel(), minlength=256)
        bgl = int(hist.argmax()); dark = max(18, bgl - 14)
    m = reg < dark
    rowfrac = m.mean(axis=1)
    bands = []; inb = False
    for y, f in enumerate(rowfrac):
        if f > rf and not inb: inb = True; y0 = y
        elif f <= rf and inb: inb = False; bands.append((y0, y))
    if inb: bands.append((y0, len(rowfrac)))
    rows = []
    for y0, y1 in bands:
        if y1 - y0 < 8: continue
        cfr = m[max(y0, y1 - strip - 3):max(y0 + 1, y1 - 3)].mean(axis=0)
        segs = []; ins = False
        for x, f in enumerate(cfr):
            if f > cf and not ins: ins = True; x0 = x
            elif f <= cf and ins: ins = False; segs.append((x0, x))
        if ins: segs.append((x0, W))
        segs = [s for s in segs if s[1] - s[0] > 25]
        rows.append({"y0": y0 + top, "y1": y1 + top, "segs": segs})
    return rows, dark

KM = {}
def keymap(im, tag):
    """letters page key centers. returns dict or {} when the letters page is not found."""
    global KM
    rows, dark = kb_rows(im)
    with open(os.path.join(OUT, "geom", tag + ".json"), "w") as f:
        json.dump({"dark": dark, "rows": rows}, f)
    idx = None
    for i in range(len(rows) - 3):
        if len(rows[i]["segs"]) == 10 and len(rows[i + 1]["segs"]) == 9 and len(rows[i + 2]["segs"]) == 9:
            idx = i
    if idx is None:
        note("keymap %s: letters page not found; row seg counts %s" % (tag, [len(r["segs"]) for r in rows])); return {}
    km = {}
    def c(r, s, up=0.42):  # dark band is the lower part of the key; aim a bit above its middle
        y = int(r["y1"] - (r["y1"] - r["y0"]) * 0.5 - (r["y1"] - r["y0"]) * up * 0.5)
        return ((s[0] + s[1]) // 2, y)
    for row, labels in ((rows[idx], "qwertyuiop"), (rows[idx + 1], "asdfghjkl")):
        for s, l in zip(row["segs"], labels): km[l] = c(row, s)
    r3 = rows[idx + 2]["segs"]
    km["SHIFT"] = c(rows[idx + 2], r3[0]); km["BKSP"] = c(rows[idx + 2], r3[-1])
    for s, l in zip(r3[1:-1], "zxcvbnm"): km[l] = c(rows[idx + 2], s)
    if idx + 3 < len(rows):
        b = rows[idx + 3]["segs"]; br = rows[idx + 3]
        if b:
            wi = max(range(len(b)), key=lambda k: b[k][1] - b[k][0])
            km["SPACE"] = c(br, b[wi]); km["SYM"] = c(br, b[0]); km["ENTER"] = c(br, b[-1])
            if wi + 1 < len(b) - 1: km["."] = c(br, b[wi + 1])
            if wi - 1 >= 1: km["LEFT_OF_SPACE"] = c(br, b[wi - 1])
            if wi - 2 >= 1: km["LEFT2_OF_SPACE"] = c(br, b[wi - 2])
            km["_bottom_n"] = len(b)
    if idx >= 1 and len(rows[idx - 1]["segs"]) == 10:
        for s, l in zip(rows[idx - 1]["segs"], "1234567890"): km["N" + l] = c(rows[idx - 1], s)
    if idx + 4 < len(rows):
        km["_arrow_n"] = len(rows[idx + 4]["segs"])
    km["_qrow"] = [rows[idx]["y0"], rows[idx]["y1"]]
    KM = km
    with open(os.path.join(OUT, "geom", tag + "_keymap.json"), "w") as f: json.dump(km, f)
    note("keymap %s: %d keys, bottom row %s keys" % (tag, len(km), km.get("_bottom_n")))
    return km

def field():
    x = run(["adb", "logcat", "-d", "-s", "IMETARGET:I"], 30)
    v = None
    for l in x.splitlines():
        if "FIELD=" in l: v = l.split("FIELD=", 1)[1]
        elif "CLEARED" in l: v = ""
    return v

def clear_field():
    sh("am broadcast -a com.wrenboard.imetarget.CLEAR -p %s" % TPKG); time.sleep(0.8)

def open_target():
    sh("am start -W -n %s/.TestInput" % TPKG, 30); time.sleep(1.5)
    tap(540, 300, 2.5)

def type_text(s, km=None, gap=0.08):
    km = km or KM
    cmds = []
    for ch in s:
        k = {" ": "SPACE", ".": ".", ",": ","}.get(ch, ch.lower())
        if k == "," and k not in km: k = "LEFT_OF_SPACE"
        if k not in km:
            note("type: no key for %r" % ch); continue
        x, y = km[k]
        cmds.append("input tap %d %d" % (x, y))
    # one device shell per chunk to keep it fast
    for i in range(0, len(cmds), 12):
        sh("; ".join(cmds[i:i + 12]), 120)
    time.sleep(1.0)

def press_hold_shot(x, y, name, hold=1.0):
    sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(hold)
    im = shot(name)
    sh("input motionevent UP %d %d" % (x, y)); time.sleep(0.6)
    return im

def sysdump(tag):
    save("sys/%s_window_ime.txt" % tag, sh("dumpsys window windows | grep -i -A12 'InputMethod'", 60))
    save("sys/%s_ime.txt" % tag, sh("dumpsys input_method", 60))

def try_dump_ime(tag):
    out = sh("uiautomator dump --windows /sdcard/w.xml 2>&1; ls -la /sdcard/w.xml", 40)
    x = run(["adb", "exec-out", "cat", "/sdcard/w.xml"], 30)
    save("ui/ime_%s.xml" % tag, x)
    note("ime window dump (%s): %s, %d bytes" % (tag, out.strip()[:120], len(x)))
    try:
        return ET.fromstring(x[x.find("<"):])
    except Exception:
        return None

def prefs_snapshot(tag):
    out = sh("ls /data/data/%s/shared_prefs/" % PKG)
    keys = ["pref_keyboard_show_number_row", "pref_number_display_key", "pref_should_always_show_top_text",
            "pref_keyboard_show_all_accents", "pref_arrows_key", "pref_key_press_popup_key", "pref_large_key_text",
            "long_press_timeout", "pref_auto_correct_key", "pref_undo_autocorrect_on_backspace", "pref_auto_insert_key",
            "pref_quick_period_key", "pref_auto_caps", "pref_auto_space", "pref_cursor_control", "pref_quick_delete_key",
            "pref_should_autospace_after_flow", "pref_editor_enabled_key", "pref_editor_candidate_enabled_key",
            "pref_predict_emoji_key", "pref_dedicated_emoji_key", "pref_adaptive_imegokey_key", "pref_key_emoji_font",
            "pref_voice_enabled", "pref_multimodal_enabled", "pref_bing_suggestions_bar_enabled", "pref_compact_mode_enabled",
            "pref_system_vibration_key", "theme"]
    found = []
    for f in out.split():
        if not f.endswith(".xml"): continue
        x = sh("cat /data/data/%s/shared_prefs/%s" % (PKG, f))
        for l in x.splitlines():
            if any(k in l for k in keys) or ("theme" in l.lower() and len(l) < 300):
                found.append(f + ": " + l.strip())
    save("sys/prefs_%s.txt" % tag, "\n".join(found))
    note("prefs %s: %d matching lines from %s" % (tag, len(found), out.split()))


def record(name, secs, action):
    p = subprocess.Popen(["adb", "shell", "screenrecord --time-limit %d --bit-rate 6000000 /sdcard/%s.mp4" % (secs, name)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    time.sleep(1.2)
    try:
        action()
    finally:
        try:
            p.wait(timeout=secs + 15)
        except Exception:
            p.kill()
    time.sleep(1.0)
    run(["adb", "pull", "/sdcard/%s.mp4" % name, os.path.join(OUT, "video", name + ".mp4")], 60)
    note("video", name, os.path.exists(os.path.join(OUT, "video", name + ".mp4")))

def field_times(tag):
    x = run(["adb", "logcat", "-d", "-v", "epoch", "-s", "IMETARGET:I"], 30)
    save("sys/fieldtimes_%s.txt" % tag, x)
    ev = []
    for l in x.splitlines():
        m = re.match(r"\s*(\d+\.\d+)\s.*FIELD=(.*)$", l)
        if m: ev.append((float(m.group(1)), m.group(2)))
    return ev

def timing_leg():
    os.makedirs(os.path.join(OUT, "video"), exist_ok=True)
    km = KM
    # 1) backspace hold: repeat delay and rate from the field's own change timestamps
    clear_field(); type_text("the quick brown fox jumps over the lazy dog and then some more words to delete here ")
    time.sleep(1.5); run(["adb", "logcat", "-c"], 20)
    x, y = km["BKSP"]
    sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(4.0); sh("input motionevent UP %d %d" % (x, y)); time.sleep(1)
    ev = field_times("bksp_hold")
    if len(ev) > 3:
        t = [e[0] for e in ev]; d = [round((t[i + 1] - t[i]) * 1000) for i in range(len(t) - 1)]
        lens = [len(e[1]) for e in ev]
        note("BKSP HOLD: %d changes, intervals ms %s, lengths %s" % (len(ev), d[:40], lens[:40]))
    else:
        note("BKSP HOLD: too few field changes", ev[:5])
    # 2) long-press on 'e' and on period, recorded
    clear_field()
    def lp(k):
        def a():
            xx, yy = km[k]; sh("input motionevent DOWN %d %d" % (xx, yy)); time.sleep(1.6); sh("input motionevent UP %d %d" % (xx, yy))
        return a
    record("v01_longpress_e", 5, lp("e")); clear_field()
    record("v02_longpress_period", 5, lp(".")); clear_field()
    # 3) quick taps: key preview show/linger
    def taps():
        for k in "gh":
            xx, yy = km[k]; sh("input tap %d %d" % (xx, yy)); time.sleep(1.2)
    record("v03_tap_preview", 5, taps); clear_field()
    # 4) backspace hold recorded
    type_text("hello there general kenobi ")
    def bk():
        xx, yy = km["BKSP"]; sh("input motionevent DOWN %d %d" % (xx, yy)); time.sleep(3.0); sh("input motionevent UP %d %d" % (xx, yy))
    record("v04_backspace_hold", 6, bk); clear_field()


BOOLS = {"pref_keyboard_show_number_row": True, "pref_should_always_show_top_text": True,
         "pref_keyboard_show_all_accents": False, "pref_arrows_key": True, "pref_key_press_popup_key": True,
         "pref_large_key_text": False, "pref_undo_autocorrect_on_backspace": False, "pref_quick_period_key": True,
         "pref_auto_caps": True, "pref_auto_space": True, "pref_cursor_control": True, "pref_quick_delete_key": True,
         "pref_should_autospace_after_flow": False, "pref_editor_enabled_key": True, "pref_editor_candidate_enabled_key": True,
         "pref_predict_emoji_key": True, "pref_dedicated_emoji_key": True, "pref_adaptive_imegokey_key": False,
         "pref_voice_enabled": True, "pref_multimodal_enabled": True, "pref_bing_suggestions_bar_enabled": False,
         "pref_compact_mode_enabled": False, "pref_system_vibration_key": True,
         "pref_bing_recommender_history_enabled": True}
STRS = {"pref_number_display_key": "top_row"}
INTS = {"long_press_timeout": 450}
PF = "/data/data/%s/shared_prefs/com.touchtype.swiftkey_preferences.xml" % PKG


def ensure_ime(tag=""):
    for i in range(6):
        cur = sh("settings get secure default_input_method").strip()
        if cur == IME:
            return True
        sh("ime enable %s" % IME); sh("ime set %s" % IME); time.sleep(1.5)
    note("ensure_ime %s: could not make SwiftKey default (now %s)" % (tag, sh("settings get secure default_input_method").strip()))
    return False

def apply_prefs():
    sh("am force-stop %s" % PKG); time.sleep(1)
    x = sh("cat %s" % PF)
    if "<map" not in x:
        note("prefs file missing"); return False
    for k, v in BOOLS.items():
        val = "true" if v else "false"
        if re.search(r'<boolean name="%s" value="[a-z]+" />' % k, x):
            x = re.sub(r'<boolean name="%s" value="[a-z]+" />' % k, '<boolean name="%s" value="%s" />' % (k, val), x)
        else:
            x = x.replace("</map>", '    <boolean name="%s" value="%s" />\n</map>' % (k, val))
    for k, v in STRS.items():
        if re.search(r'<string name="%s">[^<]*</string>' % k, x):
            x = re.sub(r'<string name="%s">[^<]*</string>' % k, '<string name="%s">%s</string>' % (k, v), x)
        else:
            x = x.replace("</map>", '    <string name="%s">%s</string>\n</map>' % (k, v))
    for k, v in INTS.items():
        if re.search(r'<int name="%s" value="\d+" />' % k, x):
            x = re.sub(r'<int name="%s" value="\d+" />' % k, '<int name="%s" value="%d" />' % (k, v), x)
        else:
            x = x.replace("</map>", '    <int name="%s" value="%d" />\n</map>' % (k, v))
    tmp = os.path.join(OUT, "_p.xml")
    open(tmp, "w").write(x)
    owner = sh("stat -c %%u:%%g %s" % PF).strip()
    run(["adb", "push", tmp, "/data/local/tmp/p.xml"], 30); os.remove(tmp)
    sh("cp /data/local/tmp/p.xml %s && chown %s %s && chmod 660 %s && rm /data/local/tmp/p.xml" % (PF, owner, PF, PF))
    note("prefs written (owner %s)" % owner)
    time.sleep(1); ensure_ime("after prefs")
    return True

def ime_keys(tag):
    r = try_dump_ime(tag)
    km = {}
    if r is None: return km
    for n in r.iter("node"):
        if n.get("package") != PKG: continue
        d = (n.get("content-desc") or n.get("text") or "").strip()
        if not d: continue
        b = B(n); c = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        raw = d
        if d.lower().startswith("capital "): d = d[8:]
        if len(d) == 1 and d.isalpha(): km[d.lower()] = c
        elif len(d) == 1 and d.isdigit(): km["N" + d] = c
        elif d == ".": km["."] = c
        elif d.startswith(","): km[","] = c; km["LEFT_OF_SPACE"] = c
        elif d == "Space": km["SPACE"] = c
        elif d == "Delete": km["BKSP"] = c
        elif "caps" in d.lower() or d.lower().startswith("shift"): km["SHIFT"] = c
        elif d == "Symbols and numbers" or d.lower() in ("abc", "letters"): km["SYM"] = c
        elif d == "Emoji": km["EMOJI"] = c
        elif d == "Enter": km["ENTER"] = c
        km.setdefault("_all", []).append([raw, list(b)])
    with open(os.path.join(OUT, "geom", tag + "_imekeys.json"), "w") as f: json.dump(km, f)
    note("ime keys %s: %d entries; have %s" % (tag, len(km), [k for k in ("q", "a", "z", "SHIFT", "BKSP", "SYM", "EMOJI", ",", ".", "SPACE", "ENTER", "N1") if k in km]))
    return km

def ime_find(r, pat):
    if r is None: return None
    for n in r.iter("node"):
        d = (n.get("content-desc") or n.get("text") or "")
        if re.search(pat, d, re.I): return n
    return None

def dismiss_ime_notice(tag):
    r = try_dump_ime(tag)
    n = ime_find(r, "^OK$")
    if n is not None and "Language" in "|".join(texts(r)):
        x, y = ctr(n); tap(x, y, 1.2); note("dismissed language notice")
    return r

def find_pumpkin():
    sh("am start -n %s/com.touchtype.materialsettings.themessettings.ThemesActivity" % PKG); time.sleep(6)
    clear_dialogs("th0", 2)
    shot("th01_themes")
    # 1) the filter icon next to the chips
    r = dump("th_filter0"); n = find(r, "^Filter themes$")
    if n is not None:
        x, y = ctr(n); tap(x, y, 2); shot("th02_filter_tapped"); r = dump("th_filter1"); note("filter tapped:", "|".join(texts(r))[:400])
        key("KEYCODE_BACK", 1.5)
    # 2) walk categories via See all
    cats_done = set(); order = ["Originals", "Originals", "Festive", "Colorful", "Minimal", "Vivid", "Retro", "Carbon", "Nickel", "Material", "Abstract"]
    found = None
    for cat in order:
        sh("am start -n %s/com.touchtype.materialsettings.themessettings.ThemesActivity" % PKG); time.sleep(3)
        to_top()
        hit = False
        for i in range(16):
            r = dump("th_cat_%s_%d" % (cat, i))
            if r is None: continue
            head = None
            for nn in r.iter("node"):
                if (nn.get("text") or "") == cat and nn.get("class", "").endswith("TextView") and B(nn)[0] < 100:
                    head = nn; break
            if head is not None:
                hy = ctr(head)[1]
                for nn in r.iter("node"):
                    if (nn.get("content-desc") or nn.get("text") or "") == "See all" and B(nn)[1] <= hy <= B(nn)[3]:
                        x, y = ctr(nn); tap(x, y, 3); hit = True; break
                if hit: break
            scroll(True)
        if not hit:
            note("category not reached:", cat); continue
        for j in range(30 if cat == "Originals" else 14):
            r = dump("th_list_%s_%d" % (cat, j))
            n = find(r, "pumpkin")
            if n is not None:
                found = (cat, j); note("PUMPKIN found in", cat); break
            scroll(True)
        if found: break
        key("KEYCODE_BACK", 1.5)
    if not found:
        note("THEME: Pumpkin not found in", order); return False
    shot("th03_pumpkin_visible")
    r = dump("th03")
    # names: ImageView + TextView 'Pumpkin'; the 'Download theme' button sits right of the TextView
    tv = None
    for nn in r.iter("node"):
        if re.search("pumpkin", nn.get("text") or "", re.I): tv = nn; break
    tv = tv if tv is not None else find(r, "pumpkin")
    tb = B(tv); best = None
    for nn in r.iter("node"):
        if (nn.get("content-desc") or "") == "Download theme":
            bb = B(nn)
            if abs(((bb[1] + bb[3]) // 2) - ((tb[1] + tb[3]) // 2)) < 120 and bb[0] >= tb[0] - 40 and bb[0] - tb[2] < 200:
                best = nn; break
    if best is not None:
        x, y = ctr(best); tap(x, y, 2); note("tapped Download theme for Pumpkin"); time.sleep(12)
        shot("th04_after_download"); r = dump("th04"); tx = "|".join(texts(r)); note("after download:", tx[:400])
        if re.search(r"sign in|microsoft account|log in", tx, re.I):
            note("THEME: download asks for sign-in"); shot("th04b_signin_prompt"); key("KEYCODE_BACK", 1.5); return False
    else:
        note("no Download button next to Pumpkin (maybe already downloaded)")
    r = dump("th05"); n = None
    for nn in r.iter("node"):
        if re.search("pumpkin", nn.get("content-desc") or nn.get("text") or "", re.I) and nn.get("class", "").endswith("ImageView"):
            n = nn; break
    n = n if n is not None else find(r, "pumpkin")
    x, y = ctr(n); tap(x, y, 4); shot("th06_after_select"); r = dump("th06"); note("after select:", "|".join(texts(r))[:400])
    clear_dialogs("th06", 2)
    th = sh("grep -i theme %s | head -20" % PF); save("sys/theme_prefs.txt", th); note("theme prefs:", th.strip().replace("\n", " ")[:400])
    return True


def wren_capture(prefix, pkg_hint="wrenboard"):
    """letters, typing, symbols, popups, emoji, clipboard, settings home for a WrenBoard build"""
    open_target(); clear_field(); time.sleep(3)
    im = shot("%s01_letters_empty" % prefix); wk = keymap(im, prefix + "01")
    r = try_dump_ime(prefix + "01")
    if not wk:
        time.sleep(3); open_target(); time.sleep(2); im = shot("%s01b_letters_empty" % prefix); wk = keymap(im, prefix + "01b")
    if not wk:
        note(prefix, "no keymap; stopping this build's captures"); return
    type_text("hel", wk); shot("%s02_typing_hel" % prefix); clear_field()
    tap(*wk["SYM"], 1.2); shot("%s03_symbols" % prefix)
    rows, _ = kb_rows(Image.open(os.path.join(OUT, "%s03_symbols.png" % prefix)))
    tap(*wk["SHIFT"], 1.2); shot("%s03b_symbols_page2" % prefix)
    tap(*wk["SYM"], 1.0); open_target(); clear_field()
    if "." in wk:
        x, y = wk["."]; press_hold_shot(x, y, "%s04_period_longpress" % prefix, 1.0); clear_field()
    x, y = wk["e"]; press_hold_shot(x, y, "%s04b_e_longpress" % prefix, 1.0); clear_field()
    def taps():
        for k in "gh":
            xx, yy = wk[k]; sh("input tap %d %d" % (xx, yy)); time.sleep(1.2)
    try:
        record("%s_tap_preview" % prefix, 5, taps); clear_field()
        def lp():
            xx, yy = wk["."]; sh("input motionevent DOWN %d %d" % (xx, yy)); time.sleep(1.6); sh("input motionevent UP %d %d" % (xx, yy))
        record("%s_longpress_period" % prefix, 5, lp); clear_field()
        type_text("the quick brown fox jumps over the lazy dog and then some more words to delete here ", wk)
        time.sleep(1.5); run(["adb", "logcat", "-c"], 20)
        x, y = wk["BKSP"]; sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(4.0); sh("input motionevent UP %d %d" % (x, y)); time.sleep(1)
        ev = field_times(prefix + "_bksp")
        t = [e[0] for e in ev]; d = [round((t[i + 1] - t[i]) * 1000) for i in range(len(t) - 1)]
        note("%s BKSP HOLD: %d changes, intervals ms %s, lengths %s" % (prefix, len(ev), d[:40], [len(e[1]) for e in ev][:40]))
    except Exception:
        note(prefix, "timing error", traceback.format_exc()[-300:])
    clear_field()
    tests = ["teh quick brwon fox jumsp over teh lazy dog ", "i cant beleive its alreday tomorow ", "hello world  this is fine "]
    for i, s_ in enumerate(tests):
        clear_field(); time.sleep(0.8); type_text(s_, wk); time.sleep(1.2)
        note("%s TYPED %r -> FIELD %r" % (prefix, s_, field()))
    clear_field()
    if "LEFT2_OF_SPACE" in wk:
        tap(*wk["LEFT2_OF_SPACE"], 2.0); shot("%s05_emoji_panel" % prefix); try_dump_ime(prefix + "05")
        key("KEYCODE_BACK", 1); open_target(); clear_field()
    # clipboard: a labelled button in the IME window, else expand the action bar first
    r = try_dump_ime(prefix + "06a"); n = ime_find(r, "clipboard")
    if n is None:
        y = wk.get("N1", (0, wk["_qrow"][0] - 120))[1] - 140
        tap(60, y, 1.5); shot("%s06_actions_expanded" % prefix); r = try_dump_ime(prefix + "06b"); n = ime_find(r, "clipboard")
    sh("am broadcast -a com.wrenboard.imetarget.SETCLIP -p %s" % TPKG); time.sleep(1)
    if n is not None:
        x, y = ctr(n); tap(x, y, 2); shot("%s07_clipboard_panel" % prefix)
    key("KEYCODE_BACK", 1)
    pk = [l.split("/")[0] for l in sh("ime list -a -s").split() if ("wrenboard" in l or "futo" in l) and "touchtype" not in l]
    if pk:
        key("KEYCODE_HOME", 1); sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % pk[0]); time.sleep(5)
        shot("%s08_settings_home" % prefix); r = dump(prefix + "08"); note(prefix, "settings home:", "|".join(texts(r))[:300])
        fab = None
        for nd in r.iter("node"):
            lab_ = (nd.get("content-desc") or "") + " " + (nd.get("text") or "")
            if re.search(r"(try|test|open|show).{0,12}(keyboard|typing)|keyboard.{0,6}(preview|test)", lab_, re.I): fab = nd; note(prefix, "floating button candidate:", lab_.strip(), nd.get("bounds"))
        if fab is not None:
            x, y = ctr(fab); tap(x, y, 2.5); shot("%s09_settings_try_keyboard_open" % prefix); r2 = dump(prefix + "09"); note(prefix, "after try-keyboard tap:", "|".join(texts(r2))[:300])
            key("KEYCODE_BACK", 1)
        for pg in ("Keyboard", "Typing", "Clipboard & emoji"):
            n2 = find(dump(prefix + "pg"), "^" + re.escape(pg) + "$")
            if n2 is not None:
                x, y = ctr(n2); tap(x, y, 2.5); shot("%s10_settings_%s" % (prefix, re.sub(r"\W", "", pg))); key("KEYCODE_BACK", 1.5)
        scroll(True); shot("%s08b_settings_home_scrolled" % prefix)
        key("KEYCODE_HOME", 1)

