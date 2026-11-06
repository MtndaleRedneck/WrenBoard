#!/usr/bin/env python3
"""Parity leg driver. Runs every leg legs.json lists for one role on a booted emulator and writes
an explicit pass / fail / inconclusive for each to OUT/results.json. Exit 0 only when every
required leg for the role passed; any setup failure, harness error or missing evidence exits 1.

Roles: baseline / candidate (WrenBoard debug build of an exact app SHA), reference (SwiftKey from
fetch_swiftkey.py), release (the signed release asset named in release-expect.json).
All text typed is a fixed synthetic sentence; nothing personal is entered or shown.
Usage: run_legs.py --role ROLE --out DIR [--apk APK] [--app-sha SHA] [--unit-json F]
                   [--release-tag TAG] [--variant-corrupt APK] [--variant-truncated APK]
"""
import argparse, hashlib, json, os, re, sys, time, traceback
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import results as R
import typing_eval as T
import crashlog as CL

WPKG = "com.wrenboard.keyboard"
SWPKG = "com.touchtype.swiftkey"
SWIME = SWPKG + "/com.touchtype.KeyboardService"
GLIDE_WORDS = ("hello", "world", "keyboard", "thanks")
BKSP_TEXT = "the quick brown fox jumps over the lazy dog and then some more words to delete here "
SPEED_TYPED = T.SENTENCE
SPEED_WANT = T.WANT
SPEED_INTERVALS = T.INTERVALS
VARIANTS = ("corrupt", "truncated", "missing")
# Verified against the app source at f4288fe: common/.../Constants.java CODE_SPACE = ' ' (32) and
# CODE_DELETE = -5; WbDebug.kt logs "KEY code=${key.code} ..." for every key. Runtime evidence:
# geom/<tag>_keys.json and sys/main/*.txt from the first run.
CODE_SPACE, CODE_DELETE = 32, -5
IME_ID = re.compile(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
A = None  # adb module, imported after PARITY_OUT is set


class Inconclusive(Exception):
    pass


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


class Device:
    def __init__(self, kbpkg):
        self.pkg, self.ime, self.km, self.hooks = kbpkg, None, {}, False

    def setup(self, wm_size, wm_density, nav):
        A.run(["adb", "wait-for-device"], 180)
        A.run(["adb", "root"], 30, check=False); time.sleep(4); A.run(["adb", "wait-for-device"], 60)
        A.log("device:", A.sh("getprop ro.product.cpu.abilist").strip(), "sdk", A.sh("getprop ro.build.version.sdk").strip())
        A.sh("wm size %dx%d" % wm_size); A.sh("wm density %d" % wm_density)
        if nav == "3button":
            A.sh("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
        for c in ("settings put system screen_off_timeout 1800000", "svc power stayon true",
                  "settings put system accelerometer_rotation 0", "settings put system user_rotation 0",
                  "input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard",
                  "settings put secure show_ime_with_hard_keyboard 1"):
            A.sh(c)
        A.sh("pm disable-user --user 0 com.google.android.inputmethod.latin", check=False)
        # 32M keeps a full main-buffer dump under the artifact gate's 50 MB per-file cap; each leg's own
        # window is saved as it ends (sys/main/<leg>.txt), so ring wrap later cannot lose leg evidence.
        A.run(["adb", "logcat", "-G", "32M"], 20)
        for b in ("main", "crash", "events"):  # the only clear in the whole pass: before anything is installed
            A.run(["adb", "logcat", "-b", b, "-c"], 20)
        tgt = os.environ.get("PARITY_TARGET_APK", "")
        if not os.path.isfile(tgt):
            raise Inconclusive("test-input app not built (PARITY_TARGET_APK)")
        A.run(["adb", "install", "-r", "-g", tgt], 180)

    def install(self, apks):
        out = A.run(["adb", "install-multiple" if len(apks) > 1 else "install", "-r", "-g"] + list(apks), 600)
        if "Success" not in out:
            raise Inconclusive("install did not report Success")
        A.log("installed", [os.path.basename(a) for a in apks], [sha256(a) for a in apks])

    def uninstall(self):
        A.run(["adb", "uninstall", self.pkg], 120, check=False)

    def activate(self):
        ime = None
        if self.pkg == SWPKG:
            ime = SWIME
        else:
            for _ in range(40):
                for l in A.sh("ime list -a -s").split():
                    if l.startswith(self.pkg + "/") and IME_ID.fullmatch(l):
                        ime = l
                if ime:
                    break
                time.sleep(2)
        if not ime:
            raise Inconclusive("keyboard IME never appeared in ime list")
        for _ in range(15):
            A.sh("ime enable %s" % ime); A.sh("ime set %s" % ime); time.sleep(2)
            if A.sh("settings get secure default_input_method").strip() == ime:
                self.ime = ime
                A.log("default IME", ime)
                return
        raise Inconclusive("could not make %s the default IME" % ime)

    def version(self):
        d = A.sh("dumpsys package %s" % self.pkg)
        vn = re.search(r"versionName=(\S+)", d); vc = re.search(r"versionCode=(\d+)", d)
        return {"version_name": vn.group(1) if vn else None, "version_code": vc.group(1) if vc else None}

    def open_target(self):
        A.sh("am start -S -W -n %s/.TestInput" % A.TPKG, 30); time.sleep(1.5)
        A.tap(540, 300, 2.0)
        self.dismiss()
        if self.ime and A.sh("settings get secure default_input_method").strip() != self.ime:
            A.sh("ime set %s" % self.ime); time.sleep(1.5); A.tap(540, 300, 2.0)
        A.clear_field()

    def dismiss(self):
        """Close first-run dialogs that cover the field; everything else is left alone."""
        for i in range(3):
            root = A.dump_windows("blockers_%d" % i)
            nodes = A.labelled(root, None)
            tx = "|".join(d for d, _ in nodes)
            want = None
            if "Choose input method" in tx:
                A.keyevent("KEYCODE_BACK", 1.0); continue
            for cue, button in (("Welcome to SwiftKey", "Get started"), ("isn't responding", "Wait"),
                                ("Physical keyboard is enabled", "Not now"), ("Language(s) enabled", "OK")):
                if cue in tx:
                    want = button
            hit = [n for d, n in nodes if d == want] if want else []
            if not hit:
                return
            A.tap(*A.centre(hit[0]), wait=1.5)

    def keymap(self, tag):
        """Key centres from WBDBG geometry (WrenBoard debug builds) or, failing that, accessibility labels."""
        km = {}
        self.hooks = False
        if self.pkg == WPKG:
            t = A.mark("keymap_" + tag)
            A.sh("am broadcast -a com.wrenboard.keyboard.WBDBG --es cmd geom -p %s" % WPKG, check=False)
            time.sleep(1.5)
            lg = A.logs("main", since=t, filters=["WBDBG:I"])
            for m in re.finditer(r"KEY code=(-?\d+) label=(\S*) cx=(\d+) cy=(\d+)", lg):
                code, c = int(m.group(1)), (int(m.group(3)), int(m.group(4)))
                if 97 <= code <= 122:
                    km[chr(code)] = c
                elif code == CODE_SPACE:
                    km["SPACE"] = c
                elif code == CODE_DELETE:
                    km["BKSP"] = c
            self.hooks = bool(km)
        if not self.hooks:
            for d, n in A.labelled(A.dump_windows("keys_" + tag), self.pkg):
                dl = d.lower()
                dl = dl[8:] if dl.startswith("capital ") else dl
                if len(dl) == 1 and dl.isalpha():
                    km[dl] = A.centre(n)
                elif dl in ("space", "space bar"):
                    km["SPACE"] = A.centre(n)
                elif dl in ("delete", "backspace"):
                    km["BKSP"] = A.centre(n)
        A.save("geom/%s_keys.json" % tag, json.dumps({"hooks": self.hooks, "keys": km}, indent=0))
        missing = [k for k in list("abcdefghijklmnopqrstuvwxyz") + ["SPACE", "BKSP"] if k not in km]
        if missing:
            raise Inconclusive("keymap %s lacks %s (hooks %s)" % (tag, missing, self.hooks))
        self.km = km
        A.log("keymap", tag, "hooks" if self.hooks else "a11y", len(km), "keys")
        return km

    def type_keys(self, s):
        cmds = ["input tap %d %d" % self.km["SPACE" if ch == " " else ch] for ch in s.lower()]
        for i in range(0, len(cmds), 12):
            A.sh("; ".join(cmds[i:i + 12]), 120)
        time.sleep(1.0)

    def glide(self, word, steps=8):
        pts = [self.km[c] for c in word]
        cmds = ["input motionevent DOWN %d %d" % pts[0]]
        for a, b in zip(pts, pts[1:]):
            for i in range(1, steps + 1):
                cmds.append("input motionevent MOVE %d %d" % (a[0] + (b[0] - a[0]) * i // steps, a[1] + (b[1] - a[1]) * i // steps))
        cmds.append("input motionevent UP %d %d" % pts[-1])
        A.sh("; ".join(cmds), 120); time.sleep(1.5)

    def settle(self, limit=12):
        t0, got, same = time.time(), A.field(), 0
        while time.time() - t0 < limit and same < 2:
            time.sleep(0.7)
            nxt = A.field()
            same, got = (same + 1, got) if nxt == got else (0, nxt)
        return got, round(time.time() - t0, 1), same >= 2

    def alive(self):
        return bool(A.sh("pidof %s" % self.pkg, check=False).strip())

    def pid(self):
        return A.sh("pidof %s" % self.pkg, check=False).strip()

    def findings(self, since=None, until=None):
        """Crash/ANR blocks (whole entries with stacks) for the keyboard and the test-input app,
        derived from the full crash, events and main buffers; the buffers themselves are saved by
        crash_anr_gate and save_buffers()."""
        c, e, m = A.logs("crash"), A.logs("events"), A.logs("main")
        out = []
        for pkg in (self.pkg, A.TPKG):
            for f in CL.findings(pkg, c, e, m):
                if (since is None or f["t"] >= since) and (until is None or f["t"] <= until):
                    f["package"] = pkg
                    out.append(f)
        return out


def last_word(s):
    w = re.findall(r"[a-z']+", (s or "").lower())
    return w[-1] if w else ""


# ---- legs: each returns (status, evidence) ----

def leg_glide(d, ctx):
    words = {}
    for w in GLIDE_WORDS:
        d.open_target()
        d.glide(w)
        got = d.settle()[0]
        words[w] = got
        A.shot("glide_%s" % w)
    ok = all(last_word(words[w]) == w for w in GLIDE_WORDS)
    return ("pass" if ok else "fail"), {"fields": words}


def leg_backspace_hold(d, ctx):
    d.open_target()
    d.type_keys(BKSP_TEXT)
    before = d.settle()[0]
    if len(before) < 40:
        return "fail", {"reason": "typing did not reach the field", "field": before}
    t = A.mark("bksp_hold")
    x, y = d.km["BKSP"]
    A.sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(3.0); A.sh("input motionevent UP %d %d" % (x, y))
    after = d.settle()[0]
    changes = len(A.field_events(t))
    removed = len(before) - len(after)
    A.shot("backspace_hold")
    ok = removed >= 20 and changes >= 5
    return ("pass" if ok else "fail"), {"len_before": len(before), "len_after": len(after), "removed": removed, "changes": changes}


def leg_cursor_drag(d, ctx):
    d.open_target()
    d.type_keys("hello world again ")
    base = d.settle()[0]
    x, y = d.km["SPACE"]
    A.sh("input swipe %d %d %d %d 700" % (x, y, max(10, x - 400), y)); time.sleep(0.8)
    d.type_keys("x")
    got = d.settle()[0]
    A.shot("cursor_drag")
    ok = "x" in got and not got.rstrip().endswith("x") and len(base) > 10
    return ("pass" if ok else "fail"), {"before": base, "after": got}


def chevron(d, tag):
    for lab, n in A.labelled(A.dump_windows(tag), d.pkg):
        m = re.fullmatch(r"(open|close) action bar", lab, re.I)
        if m:
            return m.group(1).lower(), n
    return None, None


def leg_toolbar_toggle(d, ctx):
    d.open_target()
    states = []
    s, n = chevron(d, "toolbar_0")
    states.append(s)
    if n is None and ctx["role"] == "baseline":
        # A real coverage mismatch, not a pass: the chosen baseline build has no action-bar control.
        raise Inconclusive("baseline build exposes no Open/Close Action Bar control; choose a compatible "
                           "baseline (v1.5.0 / 4094f96e or later has it) - candidate requirement unchanged")
    for i in (1, 2):
        if n is None:
            return "fail", {"reason": "no open/close action bar control", "states": states}
        A.tap(*A.centre(n), wait=2.0)
        s, n = chevron(d, "toolbar_%d" % i)
        states.append(s)
        A.shot("toolbar_%d" % i)
    flipped = None not in states and states[0] != states[1] and states[1] != states[2]
    d.km = d.keymap("after_toolbar")
    A.clear_field(); d.type_keys("ok ")
    got = d.settle()[0]
    ok = flipped and last_word(got) == "ok"
    return ("pass" if ok else "fail"), {"chevron_states": states, "field_after": got}


def parse_taps(out):
    """Typer prints 'TAP i x,y at DOWN [gap G]' per key; returns the key-down uptimes in tap order."""
    downs = []
    for m in re.finditer(r"^TAP (\d+) -?\d+,-?\d+ at (\d+)", out, re.M):
        if int(m.group(1)) != len(downs):
            return None
        downs.append(int(m.group(2)))
    return downs


def leg_continuous_typing(d, ctx):
    dex = os.environ.get("PARITY_TYPER_DEX", "")
    if not os.path.isfile(dex):
        raise Inconclusive("typing tool dex not built (PARITY_TYPER_DEX)")
    A.run(["adb", "push", dex, "/data/local/tmp/parity_typer.dex"], 60)
    pts = ":".join("%d,%d" % d.km["SPACE" if ch == " " else ch] for ch in SPEED_TYPED)
    n = len(SPEED_TYPED)
    runs, ok = [], True
    for iv in SPEED_INTERVALS:
        d.open_target(); t = A.mark("typing_%d" % iv)
        out = A.sh("CLASSPATH=/data/local/tmp/parity_typer.dex app_process /system/bin parity.Typer %s %d 40" % (pts, iv), 240)
        A.save("typing/typer_%d.txt" % iv, out)
        downs = parse_taps(out)
        pace_ok, pace = T.pace_check(iv, downs, n)
        got, secs, settled = d.settle()
        prev, drops = None, 0
        for _, v in A.field_events(t):
            if prev is not None and len(v) < len(prev) - 1:
                drops += 1
            prev = v
        gw = got.split()
        wrong = [[SPEED_TYPED.split()[j], gw[j] if j < len(gw) else None, w] for j, w in enumerate(SPEED_WANT) if j >= len(gw) or gw[j] != w]
        hook_lines = len(re.findall(r"ensureCompleted|abandonCommit", A.logs("main", since=t, filters=["WBDBG:I"]))) if d.hooks else None
        r = {"interval_ms": iv, "taps": len(downs or []), "taps_expected": n, "key_down_uptimes_ms": downs,
             "pace_ok": pace_ok, "pace": pace, "settled": settled, "settle_s": secs, "drops_over_1_char": drops,
             "field": got, "words_ok": len(SPEED_WANT) - len(wrong), "words_total": len(SPEED_WANT), "wrong": wrong,
             "wbdbg_commit_lines": hook_lines}
        runs.append(r)
        A.shot("continuous_%d" % iv)
        ok = ok and r["taps"] == n and pace_ok and settled and drops == 0
    return ("pass" if ok else "fail"), {"app_sha": ctx["app_sha"], "sentence": SPEED_TYPED, "runs": runs}


# Expected behaviour when the neural swipe model is unusable, from the app source at f4288fe:
#  - SwipeDecoderDictionary.kt: modelAvailable (lazy, first gesture lookup) logs "Swipe model not usable
#    (...); swipe model disabled" when the encoder is missing, short or an LFS pointer (SwipeModelGate);
#    a model that passes the gate but fails to load logs "Swipe model failed in <where>; swipe model
#    disabled" (disableModel). Either way getSuggestions() returns null.
#  - DictionaryFacilitatorImpl.java (getSuggestionResults): on null it falls through to the dictionary
#    groups' gesture (batch) decoding - the LEGACY gesture path. The comment at SwipeDecoderDictionary.kt
#    says so: "the caller falls back to the legacy gesture path". README-WRENBOARD.md: "swipe quietly
#    falls back and the keyboard does not crash".
# The source establishes that fallback CALL PATH only. It does not guarantee what word the legacy decoder
# recognizes for the scripted glide. So the leg records two separate results:
#  - graceful_disable (the app-source contract): causal, variant-specific disable log by the end of the
#    glide, no crash/ANR finding in the leg, one live keyboard pid from activation to the end, tapped "ok" types.
#  - fallback_recognition (OUR first-run qualification target, not an app-source guarantee): the glide
#    output, and whether it is exactly FALLBACK_WORD. A miss (empty included) is category
#    fallback_recognition_target_missed: it still blocks the leg, but it is a test-qualification finding to
#    diagnose from the output, screenshot and causal logs, not automatically an app regression, and it
#    never changes the graceful_disable facts.
MODEL_DISABLED_LOG = {"missing": "Swipe model not usable", "truncated": "Swipe model not usable",
                      "corrupt": "Swipe model failed in"}
FALLBACK_WORD = "hello"


def leg_model(variant):
    def leg(d, ctx):
        apk = ctx["variants"].get(variant)
        if not apk or not os.path.isfile(apk):
            raise Inconclusive("model variant %s APK not built" % variant)
        t_leg = A.mark("model_%s_start" % variant)
        ops = []

        def op(name, fn):
            a = A.mark("op_%s_%s" % (variant, name))
            fn()
            b = A.mark("op_%s_%s_end" % (variant, name))
            rec = {"op": name, "t_start": a, "t_end": b, "field_after": A.field(a), "pid": d.pid(),
                   "findings": [f["kind"] + "@" + f["package"] for f in d.findings(a, b)]}
            ops.append(rec)
            return rec

        op("install", lambda: (d.uninstall(), d.install([apk])))
        op("activate", d.activate)
        op("open_target", d.open_target)
        op("keymap", lambda: setattr(d, "km", d.keymap("model_" + variant)))
        if not d.hooks:
            return "inconclusive", {"reason": "no WBDBG hooks in this build", "ops": ops}
        op("open_target_2", d.open_target)
        g = op("glide_hello", lambda: d.glide("hello"))
        glided = d.settle()[0]
        g["field_after"] = glided
        op("clear_field", A.clear_field)
        ty = op("type_ok", lambda: d.type_keys("ok "))
        typed = d.settle()[0]
        ty["field_after"] = typed
        t_end = A.mark("model_%s_end" % variant)
        A.shot("model_" + variant)
        lg = A.logs("main", since=t_leg, until=t_end)
        A.save("sys/model_%s_main.txt" % variant, lg)  # the whole window, never cleared
        sdd = [l for l in lg.splitlines() if "SwipeDecoderDictionary" in l]
        disabled = []
        for l in sdd:
            if "swipe model disabled" in l:
                t = float(l.split()[0])
                disabled.append({"t": t, "op": next((o["op"] for o in ops if o["t_start"] <= t <= o["t_end"]), "between_ops"), "line": l.strip()[:300]})
        want = MODEL_DISABLED_LOG[variant]
        fs = d.findings(t_leg, t_end)
        A.save("sys/model_%s_findings.json" % variant, json.dumps(fs, indent=1))
        pids_after_activate = {o["pid"] for o in ops[1:]}
        ev = {"variant": variant, "ops": ops, "swipe_decoder_lines": sdd[:40], "disabled_evidence": disabled,
              "expected_disabled_log": want, "fallback_recognition_target": FALLBACK_WORD + " (harness first-run target on the legacy gesture path)",
              "glide_field": glided, "typed_field": typed, "process_alive": d.alive(),
              "pids_after_activate": sorted(pids_after_activate), "findings": [{k: f[k] for k in ("kind", "package", "buffer", "t", "lines")} for f in fs]}
        glide_end = g["t_end"] + 15
        checks = {
            "causal_variant_disable_log": any(want in x["line"] and x["t"] <= glide_end for x in disabled),
            "no_crash_anr": not fs,
            "keyboard_process_stable_live": bool(ev["process_alive"]) and len(pids_after_activate) == 1 and "" not in pids_after_activate,
            "tapped_ok_types": last_word(typed) == "ok",
        }
        gproblems = []
        if not checks["no_crash_anr"]:
            gproblems.append("crash/ANR findings during the leg")
        if not checks["keyboard_process_stable_live"]:
            gproblems.append("keyboard process died or restarted after activation")
        if not checks["causal_variant_disable_log"]:
            gproblems.append("no '%s ... swipe model disabled' log by the end of the glide" % want)
        if not checks["tapped_ok_types"]:
            gproblems.append("tapped keys did not type 'ok'")
        graceful = {"status": "pass" if not gproblems else "fail", "checks": checks, "problems": gproblems,
                    "label": "graceful neural-disable proof (app-source contract)"}
        hit = glided.strip().lower() == FALLBACK_WORD
        recog = {"status": "hit" if hit else "target_missed", "target": FALLBACK_WORD, "glide_output": glided,
                 "category": None if hit else "fallback_recognition_target_missed",
                 "label": "first-run fallback recognition qualification (harness target, not an app-source guarantee)"}
        if not hit:
            recog["finding"] = ("test qualification finding: the glide produced %r, not the exact target %r. Diagnose it from "
                                "the output, png/model_%s.png and sys/model_%s_main.txt; it is not automatically an app "
                                "regression and does not change the graceful-disable result" % (glided, FALLBACK_WORD, variant, variant))
        categories = []
        if graceful["status"] != "pass":
            categories.append("graceful_disable_failed")
        if not hit:
            categories.append("fallback_recognition_target_missed")
        problems = gproblems + (["fallback recognition target missed: glide output %r, target %r (test qualification finding)"
                                 % (glided, FALLBACK_WORD)] if not hit else [])
        ev = dict({"graceful_disable": graceful, "fallback_recognition": recog, "categories": categories}, **ev)
        ev["problems"] = problems
        return ("pass" if not categories else "fail"), ev
    return leg


def leg_release_provenance(d, ctx):
    import check_apk
    exp = json.load(open(os.path.join(HERE, "release-expect.json"))).get(ctx["release_tag"] or "")
    if not exp:
        return "fail", {"reason": "release tag not listed in release-expect.json"}
    if not ctx["apk"] or os.path.basename(ctx["apk"]) != exp["asset"]:
        return "fail", {"reason": "APK is not the named asset %s" % exp["asset"]}
    info, problems = check_apk.check(ctx["apk"], exp["package"], exp["version_name"], exp["version_code"], exp["abi"], exp["signer_sha256"])
    info["sha256"] = sha256(ctx["apk"]) if os.path.isfile(ctx["apk"]) else None
    ctx["provenance_ok"] = not problems
    return ("pass" if not problems else "fail"), {"readback": info, "problems": problems}


def leg_release_smoke(d, ctx):
    if not ctx.get("provenance_ok"):
        return "inconclusive", {"reason": "provenance did not pass; the APK was not installed"}
    d.install([ctx["apk"]]); d.activate(); d.open_target()
    d.km = d.keymap("release")
    d.type_keys("ok ")
    got = d.settle()[0]
    A.shot("release_smoke")
    return ("pass" if last_word(got) == "ok" else "fail"), {"field": got, "version": d.version()}


def save_buffers(tag):
    """Keep the FULL crash, events and main buffers plus /data/anr traces as artifacts."""
    for b in ("crash", "events", "main"):
        A.save("sys/full/%s_%s.txt" % (tag, b), A.logs(b))
    names = A.sh("ls /data/anr", check=False).split()
    for n in names:
        if re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", n):
            A.save("sys/full/anr/%s.txt" % n, A.sh("cat /data/anr/%s" % n, 60, check=False))
    return names


def leg_crash_anr_gate(d, ctx):
    anr_files = save_buffers("final")
    fs = CL.attribute(d.findings(), ctx["windows"])
    A.save("sys/crash_anr_findings.json", json.dumps(fs, indent=1))
    lines = ["[%s] %s %s %s pid %s (%d lines)\n%s\n" % (f["window"], f["package"], f["kind"], f["buffer"], f["pid"], f["lines"], f["text"]) for f in fs]
    A.save("sys/crash_anr.txt", "\n".join(lines) + "\n")
    summary = [{k: f[k] for k in ("window", "package", "kind", "buffer", "pid", "lines")} for f in fs]
    return ("pass" if not fs else "fail"), {"findings": len(fs), "by_window": summary[:50], "anr_trace_files": anr_files,
                                           "full_buffers": "sys/full/final_{crash,events,main}.txt"}


def leg_unit_suite(d, ctx):
    p = ctx.get("unit_json")
    if not p or not os.path.isfile(p):
        return "fail", {"reason": "no unit-suite summary"}
    s = json.load(open(p))
    keep = {k: s.get(k) for k in ("tests", "failed", "skipped", "executed", "suites", "problems")}
    t, sk, fl = s.get("tests"), s.get("skipped"), s.get("failed")
    coherent = all(isinstance(v, int) and not isinstance(v, bool) and v >= 0 for v in (t, sk, fl)) and sk <= t and fl <= t - sk
    ok = s.get("ok") is True and coherent and t - sk > 0 and fl == 0
    return ("pass" if ok else "fail"), keep


LEGS = {"glide": leg_glide, "backspace_hold": leg_backspace_hold, "cursor_drag": leg_cursor_drag,
        "toolbar_toggle": leg_toolbar_toggle, "continuous_typing": leg_continuous_typing,
        "model_corrupt": leg_model("corrupt"), "model_truncated": leg_model("truncated"), "model_missing": leg_model("missing"),
        "release_provenance": leg_release_provenance, "release_smoke": leg_release_smoke,
        "crash_anr_gate": leg_crash_anr_gate, "unit_suite": leg_unit_suite}
NO_DEVICE = ("unit_suite", "release_provenance")


def env_display():
    m = re.fullmatch(r"(\d{3,4})x(\d{3,4})", os.environ.get("WM_SIZE", "1080x2400"))
    dn = os.environ.get("WM_DENSITY", "420")
    nav = os.environ.get("NAV", "gesture")
    if not m or not dn.isdigit() or nav not in ("gesture", "3button"):
        raise SystemExit("bad WM_SIZE / WM_DENSITY / NAV")
    return (int(m.group(1)), int(m.group(2))), int(dn), nav


def main():
    global A
    ap = argparse.ArgumentParser()
    ap.add_argument("--role", required=True, choices=["baseline", "candidate", "reference", "release"])
    ap.add_argument("--out", required=True); ap.add_argument("--apk"); ap.add_argument("--app-sha")
    ap.add_argument("--unit-json"); ap.add_argument("--release-tag")
    for v in VARIANTS:
        ap.add_argument("--variant-" + v)
    o = ap.parse_args()
    if o.role in ("baseline", "candidate") and not (o.app_sha and re.fullmatch(r"[0-9a-f]{40}", o.app_sha)):
        ap.error("--app-sha must be a 40-character SHA for %s" % o.role)
    os.environ["PARITY_OUT"] = o.out
    import adb as A_
    A = A_
    m = R.load_manifest()
    legs = R.legs_for(m, o.role)
    unknown = [l["name"] for l in legs if l["name"] not in LEGS]
    if unknown:
        raise R.ManifestError("no driver for legs %s" % unknown)
    res = R.Results(os.path.join(o.out, "results.json"), o.role, "swiftkey" if o.role == "reference" else "wrenboard", o.app_sha)
    ctx = {"app_sha": o.app_sha, "apk": o.apk, "unit_json": o.unit_json, "release_tag": o.release_tag, "role": o.role,
           "variants": {v: getattr(o, "variant_" + v) for v in VARIANTS}, "windows": []}
    d = Device(SWPKG if o.role == "reference" else WPKG)
    setup_error = None
    needs_device = any(l["name"] not in NO_DEVICE for l in legs)
    if needs_device:
        t_setup = None
        try:
            size, dens, nav = env_display()
            d.setup(size, dens, nav)
            t_setup = A.mark("setup")
            if o.role == "reference":
                lst = os.path.join(os.environ.get("RUNNER_TEMP", ""), "sk", "install-list.txt")
                if not os.path.isfile(lst):
                    raise Inconclusive("SwiftKey was not fetched and verified")
                d.install(open(lst).read().split()); d.activate()
                res.note("swiftkey %s" % json.dumps(d.version()))
            elif o.role in ("baseline", "candidate"):
                if not o.apk or not os.path.isfile(o.apk):
                    raise Inconclusive("no app APK for %s" % o.role)
                d.install([o.apk]); d.activate()
                res.note("app %s apk sha256 %s %s" % (o.app_sha, sha256(o.apk), json.dumps(d.version())))
            if o.role != "release":
                d.open_target(); d.keymap("start")
            res.note("capabilities %s" % json.dumps({"wbdbg_hooks": d.hooks}))
        except (Exception, SystemExit) as e:
            setup_error = "%s: %s" % (type(e).__name__, e)
            A.log("SETUP FAILED", setup_error, traceback.format_exc()[-800:])
        try:
            t_end = A.mark("setup_end")
            ctx["windows"].append(("setup", t_setup if t_setup is not None else 0.0, t_end))
            sf = d.findings(None, t_end)
            if sf:
                save_buffers("setup")
                res.note("setup crash/ANR findings: %s" % json.dumps([{k: f[k] for k in ("package", "kind", "buffer", "pid")} for f in sf]))
                setup_error = setup_error or "crash/ANR during setup"
        except Exception as e:
            setup_error = setup_error or "could not read setup evidence: %s" % e
    for l in legs:
        name = l["name"]
        if setup_error and name not in NO_DEVICE:
            res.set(name, "inconclusive", reason="setup failed: " + setup_error[:300]); continue
        t0 = None
        try:
            if name not in NO_DEVICE:
                t0 = A.mark("leg_" + name)
            st, ev = LEGS[name](d, ctx)
            st = R.hook_status(l, d.hooks, st)
        except Inconclusive as e:
            st, ev = "inconclusive", {"reason": str(e)}
        except (Exception, SystemExit) as e:
            st, ev = "inconclusive", {"reason": "harness error %s: %s" % (type(e).__name__, str(e)[:300])}
            A.log("LEG ERROR", name, traceback.format_exc()[-800:])
        if t0 is not None and name != "crash_anr_gate":
            try:
                t1 = A.mark("leg_%s_end" % name)
                ctx["windows"].append(("leg:" + name, t0, t1))
                A.save("sys/main/%s.txt" % name, A.logs("main", since=t0, until=t1))
                lf = d.findings(t0, t1)
                if lf:
                    ev["leg_crash_anr"] = [{k: f[k] for k in ("package", "kind", "buffer", "pid", "lines")} for f in lf]
                    if st == "pass":
                        st = "fail"
            except Exception as e:
                ev["evidence_error"] = str(e)[:300]
                if st == "pass":
                    st = "inconclusive"
        res.set(name, st, **ev)
    problems = R.gate(m, res.data, o.role)
    for p in problems:
        print("::error::%s" % p)
    print("ROLE %s: %s" % (o.role, "PASS" if not problems else "FAIL (%d)" % len(problems)))
    return 0 if not problems else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException:
        traceback.print_exc()
        sys.exit(1)
