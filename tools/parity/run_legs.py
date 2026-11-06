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
import argparse, hashlib, json, os, re, struct, sys, time, traceback
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import results as R
import typing_eval as T
import crashlog as CL
import visual as V

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


NAV_OVERLAY = {"3button": "com.android.internal.systemui.navbar.threebutton",
               "gesture": "com.android.internal.systemui.navbar.gestural"}
NAV_MODE = {"3button": "0", "gesture": "2"}  # Settings.Secure.NAVIGATION_MODE values (2-button is 1)


def wm_value(out, kind):
    """(physical, override) from `wm size` / `wm density` output; override None when not set."""
    pat = r"\d+x\d+" if kind == "size" else r"\d+"
    p = re.search(r"Physical %s: (%s)" % (kind, pat), out)
    o = re.search(r"Override %s: (%s)" % (kind, pat), out)
    return (p.group(1) if p else None), (o.group(1) if o else None)


def png_size(path):
    with open(path, "rb") as f:
        h = f.read(24)
    if len(h) < 24 or h[:8] != b"\x89PNG\r\n\x1a\n" or h[12:16] != b"IHDR":
        return None
    return "%dx%d" % struct.unpack(">II", h[16:24])


def display_readback(wm_size, wm_density, nav, tries=8, frame=None):
    """Read back what the device is after setup: wm size, wm density, navigation mode and enabled nav
    overlay, and an actual screenshot's pixel size. Every value must equal the requested one (a wm
    override, if any, must equal it too); otherwise `mismatch` lists requested and observed values.
    frame: Device.setup_frame()'s contemporaneous capture (screenshot between two UI dumps, checked by
    visual.frame_check); its pixel size is the screenshot_px, and any frame problem is a mismatch."""
    want = {"size": "%dx%d" % wm_size, "density": str(wm_density), "nav_mode": NAV_MODE[nav]}
    for i in range(tries):
        nm = A.sh("settings get secure navigation_mode", check=False).strip()
        if nm == want["nav_mode"]:
            break
        time.sleep(1.5)
    sp, so = wm_value(A.sh("wm size"), "size")
    dp, do = wm_value(A.sh("wm density"), "density")
    ov = [l.strip() for l in A.sh("cmd overlay list android", check=False).splitlines() if "navbar" in l]
    shot = png_size(A.shot("setup_readback")) if frame is None else frame.get("px")
    rb = {"requested": dict(want, nav=nav), "wm_size_physical": sp, "wm_size_override": so,
          "wm_density_physical": dp, "wm_density_override": do, "navigation_mode": nm,
          "nav_overlays": ov, "screenshot_px": shot}
    mm = {}
    for key, got, w in (("wm_size_physical", sp, want["size"]), ("wm_density_physical", dp, want["density"]),
                        ("navigation_mode", nm, want["nav_mode"]), ("screenshot_px", shot, want["size"])):
        if got != w:
            mm[key] = {"requested": w, "observed": got}
    for key, got, w in (("wm_size_override", so, want["size"]), ("wm_density_override", do, want["density"])):
        if got is not None and got != w:
            mm[key] = {"requested": w, "observed": got}
    if not any(("[x] " + NAV_OVERLAY[nav]) in l for l in ov):
        mm["nav_overlay"] = {"requested": "[x] " + NAV_OVERLAY[nav], "observed": ov}
    if frame is not None:
        rb["setup_frame"] = frame
        if frame.get("problems"):
            mm["setup_frame"] = {"requested": "settled frame: test field shown, %s nav controls visible, agreeing with the UI dumps" % nav,
                                 "observed": [p["category"] for p in frame["problems"]]}
    rb["mismatch"] = mm
    return rb


LETTERS = "abcdefghijklmnopqrstuvwxyz"
REQUIRED_KEYS = list(LETTERS) + ["SPACE", "BKSP"]
# Accessibility nodes under any of these ids (the suggestion strip's chips, the toolbar) are never keys:
# in the first run the release keymap matched 'i' to the "I" suggestion chip.
NOT_KEY_ID = re.compile(r"suggestion|candidate|actionbar|toolbar", re.I)
KEYBOARD_VIEW = {WPKG: WPKG + ":id/keyboard_view"}
# Bounded QWERTY layout table for builds without hooks: (rows in the keyboard area, index of the q-row).
# Letter rows: 10 keys from the left edge; 9 keys offset half a key; z..m offset 1.5 keys with BKSP at
# the right end; SPACE at the centre of the row below. Only a tapped-and-verified position is ever used.
QWERTY_LAYOUTS = {"rows4": (4, 0), "rows5_number_row": (5, 1)}


def keyboard_area(root, pkg):
    """Bounds of the innermost keyboard_view of the keyboard package (WrenBoard), or, for a keyboard
    whose view id is not known (SwiftKey), the input-method window; None when neither is found."""
    if root is None:
        return None
    best = None
    vid = KEYBOARD_VIEW.get(pkg)
    for n in root.iter("node"):
        if vid and n.get("package") == pkg and n.get("resource-id") == vid:
            b = A.bounds(n)
            a = (b[2] - b[0]) * (b[3] - b[1])
            if a > 0 and (best is None or a < best[0]):
                best = (a, b)
    if best:
        return best[1]
    if vid:
        return None
    for w in root.iter("window"):
        if w.get("type") == "TYPE_INPUT_METHOD":
            b = A.bounds(w)
            if (b[2] - b[0]) * (b[3] - b[1]) > 0:
                return b
    return None


def key_nodes(root, pkg, area):
    """(label, node) for labelled nodes of pkg whose centre lies inside area and which are not under a
    suggestion / candidate / toolbar node (checked on the node and every ancestor)."""
    out = []
    if root is None or not area:
        return out
    def walk(n, banned):
        banned = banned or bool(NOT_KEY_ID.search(n.get("resource-id") or ""))
        if n.tag == "node" and not banned and n.get("package") == pkg:
            d = (n.get("content-desc") or n.get("text") or "").strip()
            x, y = A.centre(n)
            if d and area[0] <= x < area[2] and area[1] <= y < area[3]:
                out.append((d, n))
        for c in n:
            walk(c, banned)
    walk(root, False)
    return out


def geometry_keys(area, layout):
    """Key centres for one QWERTY_LAYOUTS entry inside area; None when the area is implausibly small."""
    if not area:
        return None
    l, t, r, b = area
    w, h = r - l, b - t
    rows, q = QWERTY_LAYOUTS[layout]
    if w < 300 or h < 200:
        return None
    kw, rh = w / 10.0, h / float(rows)
    y = lambda i: int(t + (q + i + 0.5) * rh)
    km = {}
    for j, c in enumerate("qwertyuiop"):
        km[c] = (int(l + (j + 0.5) * kw), y(0))
    for j, c in enumerate("asdfghjkl"):
        km[c] = (int(l + (j + 1.0) * kw), y(1))
    for j, c in enumerate("zxcvbnm"):
        km[c] = (int(l + (j + 2.0) * kw), y(2))
    km["BKSP"] = (int(r - 0.75 * kw), y(2))
    km["SPACE"] = (int(l + w / 2.0), y(3))
    return km


def installed_abi(pkg):
    """The ABI the package manager chose for an installed package (dumpsys primaryCpuAbi), or None."""
    m = re.search(r"primaryCpuAbi=(\S+)", A.sh("dumpsys package %s" % pkg, 60, check=False))
    return m.group(1) if m and m.group(1) != "null" else None


class Device:
    def __init__(self, kbpkg):
        self.pkg, self.ime, self.km, self.hooks, self.readback = kbpkg, None, {}, False, None
        self.keyboard_visible = None

    def setup(self, wm_size, wm_density, nav):
        A.run(["adb", "wait-for-device"], 180)
        A.run(["adb", "root"], 30, check=False); time.sleep(4); A.run(["adb", "wait-for-device"], 60)
        A.log("device:", A.sh("getprop ro.product.cpu.abilist").strip(), "sdk", A.sh("getprop ro.build.version.sdk").strip())
        # Size and density come from the AVD itself (avd_config.sh writes hw.lcd.* before boot); no wm
        # override is applied, so the readback below sees what the device really is.
        A.sh("cmd overlay enable-exclusive --category %s" % NAV_OVERLAY[nav])
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
        # The display readback is taken only once the system is ready (boot complete, the launcher focused,
        # no system dialog) and the test field is on screen and focused, from ONE settled capture: UI dump,
        # screenshot, UI dump (run 2's setup PNG was a black frame with one pill while later dumps said
        # 3-button). A dialog that keeps coming back, or a frame that contradicts its dumps, is a setup
        # failure: every device leg of the role is then inconclusive.
        frame = None
        try:
            self.system_ready()
            frame = self.setup_frame(wm_size, nav)
        except Inconclusive as e:
            frame = {"px": None, "problems": [{"category": "not_ready", "detail": str(e)[:400]}]}
        self.readback = display_readback(wm_size, wm_density, nav, frame=frame)
        A.save("sys/display_readback.json", json.dumps(self.readback, indent=1))
        if self.readback["mismatch"]:
            raise Inconclusive("display readback mismatch %s" % json.dumps(self.readback["mismatch"])[:1500])

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

    def open_target(self, keyboard="require"):
        """Start the test-input app and, before any key geometry or tap, prove readiness from a bounded
        wait (wait_ui): no system dialog, the test field focused in the focused window and, with
        keyboard="require", this keyboard's IME window visible; otherwise Inconclusive. keyboard="record"
        (model legs) records IME visibility in self.keyboard_visible and leaves the verdict to the leg's own
        graceful-disable checks, whose semantics are unchanged."""
        A.sh("am start -S -W -n %s/.TestInput" % A.TPKG, 30); time.sleep(1.5)
        A.tap(540, 300, 2.0)
        self.dismiss()
        if self.ime and A.sh("settings get secure default_input_method").strip() != self.ime:
            A.sh("ime set %s" % self.ime); time.sleep(1.5); A.tap(540, 300, 2.0)
        st = self.focus("field", need_ime=(keyboard == "require"))
        self.keyboard_visible = bool(st.get("ime_bounds"))
        A.clear_field()

    # ---- readiness (bounded; no emulator restart, no retry chain, no other image or target) ----
    READY_LIMIT_S, FOCUS_LIMIT_S, MAX_DISMISS, MAX_FOCUS_TAPS = 120.0, 45.0, 3, 3

    def current_focus(self):
        m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^}]*)\}", A.sh("dumpsys window", 60, check=False))
        return m.group(1).strip() if m else None

    def wait_ui(self, tag, want, limit, on_miss=None):
        """Poll windows dumps until want(state) returns None on two consecutive dumps. A system dialog
        (ANR or crash, in the dump or as the focused window) gets "Wait" (else BACK) and is re-checked;
        after MAX_DISMISS dismissals of a dialog that keeps returning, or past `limit` seconds or 40
        polls, raise Inconclusive naming the last reason. Every dump is saved as ui/ready_<tag>_<n>.xml."""
        t0, clean, dismissed, why = time.time(), 0, [], "no dump yet"
        for i in range(40):
            if time.time() - t0 > limit:
                break
            st = V.ui_state(A.dump_windows("ready_%s_%d" % (tag, i)), self.pkg)
            cf = self.current_focus()
            st["current_focus"] = cf
            if cf and V.BLOCKER_TITLE.search(cf) or cf and cf.startswith("Application "):
                st["blockers"] = st["blockers"] or [cf]
            if st["blockers"] and st["blockers"] != ["no ui dump"]:
                clean = 0
                why = "system dialog %s" % st["blockers"]
                if len(dismissed) >= self.MAX_DISMISS:
                    A.log("READY", tag, "persistent dialog", st["blockers"])
                    raise Inconclusive("readiness[persistent_blocker] %s: %s still shown after %d dismissals" % (tag, st["blockers"], len(dismissed)))
                dismissed.append(st["blockers"][0])
                if st.get("blocker_wait"):
                    A.tap(*st["blocker_wait"], wait=2.0)
                else:
                    A.keyevent("KEYCODE_BACK", 2.0)
                continue
            why = want(st) if st["dump"] else "no ui dump"
            if why:
                clean = 0
                if on_miss:
                    on_miss(st)
                time.sleep(1.5)
                continue
            clean += 1
            if clean >= 2:
                st["dismissed"] = dismissed
                A.log("READY", tag, "after", i + 1, "dumps; dismissed", dismissed)
                return st
            time.sleep(1.0)
        raise Inconclusive("readiness[not_ready] %s within %ds: %s" % (tag, limit, why))

    def system_ready(self):
        """Boot completed, the HOME activity's package is the focused window, no system dialog."""
        t0 = time.time()
        for _ in range(60):
            if A.sh("getprop sys.boot_completed", check=False).strip() == "1":
                break
            if time.time() - t0 > self.READY_LIMIT_S:
                break
            time.sleep(2)
        else:
            raise Inconclusive("readiness[not_ready] boot not completed")
        if A.sh("getprop sys.boot_completed", check=False).strip() != "1":
            raise Inconclusive("readiness[not_ready] boot not completed within %ds" % self.READY_LIMIT_S)
        out = A.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME", check=False)
        homes = [l.strip() for l in out.splitlines() if IME_ID.fullmatch(l.strip())]
        if len(homes) != 1:
            raise Inconclusive("readiness[not_ready] HOME activity not resolved: %r" % out[-200:])
        home = homes[0].split("/")[0]
        A.keyevent("KEYCODE_HOME", 1.5)
        def want(st):
            fw = st.get("focused_window") or {}
            cf = st.get("current_focus") or ""
            if fw.get("pkg") != home or (cf and not cf.startswith(home + "/")):
                return "launcher %s not focused (window %s, focus %r)" % (home, fw, cf)
            return None
        return self.wait_ui("launcher", want, self.READY_LIMIT_S, on_miss=lambda st: A.keyevent("KEYCODE_HOME", 1.0))

    def focus(self, tag, need_ime):
        """The test-input field focused in the focused window (and this keyboard's IME window visible when
        need_ime); a bounded number of taps on the field while it is not."""
        taps = [0]
        def want(st):
            fw = st.get("focused_window") or {}
            cf = st.get("current_focus") or ""
            if fw.get("pkg") != A.TPKG or not st.get("field_focused") or (cf and not cf.startswith(A.TPKG + "/")):
                return "readiness[field_not_focused] window %s, field focused %s, focus %r" % (fw, st.get("field_focused"), cf)
            if need_ime and not st.get("ime_bounds"):
                return "readiness[keyboard_not_visible] no visible %s input-method window" % self.pkg
            return None
        def tap(st):
            if taps[0] < self.MAX_FOCUS_TAPS:
                b = st.get("field_bounds")
                A.tap(*(((b[0] + b[2]) // 2, min(b[1] + 232, (b[1] + b[3]) // 2)) if b else (540, 300)), wait=1.5)
                taps[0] += 1
        return self.wait_ui(tag, want, self.FOCUS_LIMIT_S, on_miss=tap)

    def setup_frame(self, wm_size, nav, attempts=3):
        """ONE contemporaneous capture after settling: UI dump, screenshot, UI dump, of the test-input app
        with its field focused; checked by visual.frame_check (pixels against both dumps). Repeated (at
        most `attempts`) only while the two dumps disagree, i.e. while the screen is still changing."""
        A.sh("am start -S -W -n %s/.TestInput" % A.TPKG, 30); time.sleep(1.5)
        self.focus("setup_frame", need_ime=False)
        for i in range(attempts):
            time.sleep(1.0)
            before = V.ui_state(A.dump_windows("setup_readback_before"), self.pkg)
            png = A.shot("setup_readback")
            after = V.ui_state(A.dump_windows("setup_readback_after"), self.pkg)
            probs, facts = V.frame_check(png, before, after, nav, wm_size)
            if not any(p["category"] == "xml_unsettled" for p in probs):
                break
        frame = {"px": facts.get("px"), "problems": probs, "facts": facts, "attempts": i + 1,
                 "png": "png/setup_readback.png", "ui": ["ui/setup_readback_before.xml", "ui/setup_readback_after.xml"]}
        A.save("sys/setup_frame.json", json.dumps(frame, indent=1))
        return frame

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
        detail = {}
        if not self.hooks:
            # No hooks: keys only from accessibility nodes inside the keyboard area (suggestion chips and
            # the toolbar excluded), then the bounded QWERTY geometry table for what is still missing.
            # Every such key counts only after tap-to-text proves it types its own character.
            root = A.dump_windows("keys_" + tag)
            area = keyboard_area(root, self.pkg)
            cand = {}
            for d, n in key_nodes(root, self.pkg, area):
                dl = d.lower()
                dl = dl[8:] if dl.startswith("capital ") else dl
                if len(dl) == 1 and dl.isalpha():
                    cand[dl] = A.centre(n)
                elif dl in ("space", "space bar"):
                    cand["SPACE"] = A.centre(n)
                elif dl in ("delete", "backspace"):
                    cand["BKSP"] = A.centre(n)
            km, detail = self.verify_keys(cand, area)
        A.save("geom/%s_keys.json" % tag, json.dumps(dict({"hooks": self.hooks, "keys": km}, **detail), indent=0))
        missing = [k for k in REQUIRED_KEYS if k not in km]
        if missing:
            raise Inconclusive("keymap %s lacks %s (hooks %s%s)" % (tag, missing, self.hooks,
                               "; unverified by tap-to-text" if not self.hooks else ""))
        self.km = km
        A.log("keymap", tag, "hooks" if self.hooks else "verified a11y/geometry", len(km), "keys")
        return km

    def tap_text(self, pts):
        """Clear the field, tap the given points, and return the settled field text."""
        A.clear_field()
        A.sh("; ".join("input tap %d %d" % (int(x), int(y)) for x, y in pts), 60)
        return self.settle(6)[0]

    def verify_keys(self, cand, area):
        """Tap-to-text: a letter key counts only when tapping it types that letter (case-insensitive),
        SPACE only when o, k, SPACE types "ok ", BKSP only when o, k, BKSP leaves "o". Sources tried in
        order: accessibility nodes, then each layout of QWERTY_LAYOUTS inside the keyboard area. Returns
        (verified keys, evidence); anything left unverified is simply absent, so keymap() is inconclusive."""
        ok, src, tried = {}, {}, []
        sources = [("a11y", cand)] + [("geometry:" + name, geometry_keys(area, name)) for name in QWERTY_LAYOUTS]
        for sname, km in sources:
            if not km:
                continue
            todo = [k for k in LETTERS if k in km and k not in ok]
            if todo:
                got = self.tap_text([km[k] for k in todo])
                tried.append({"source": sname, "keys": "".join(todo), "typed": got})
                if got.lower() == "".join(todo):
                    good = todo
                else:
                    good = []
                    for k in todo:
                        g1 = self.tap_text([km[k]])
                        if g1.lower() == k:
                            good.append(k)
                        else:
                            tried.append({"source": sname, "keys": k, "typed": g1})
                for k in good:
                    ok[k], src[k] = km[k], sname
            if "o" in ok and "k" in ok:
                for key, want in (("SPACE", "ok "), ("BKSP", "o")):
                    if key in ok or key not in km:
                        continue
                    got = self.tap_text([ok["o"], ok["k"], km[key]])
                    tried.append({"source": sname, "keys": "ok+" + key, "typed": got})
                    if got.lower() == want:
                        ok[key], src[key] = km[key], sname
            if all(k in ok for k in REQUIRED_KEYS):
                break
        A.clear_field()
        return ok, {"keyboard_area": list(area) if area else None, "verified_by": src, "tap_to_text": tried[:80],
                    "unverified": [k for k in REQUIRED_KEYS if k not in ok]}

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
            rec = {"op": name, "t_start": a, "t_end": b, "field_after": A.field(a), "pid": d.pid(), "keyboard_visible": d.keyboard_visible,
                   "findings": [f["kind"] + "@" + f["package"] for f in d.findings(a, b)]}
            ops.append(rec)
            return rec

        op("install", lambda: (d.uninstall(), d.install([apk])))
        op("activate", d.activate)
        op("open_target", lambda: d.open_target(keyboard="require"))
        op("keymap", lambda: setattr(d, "km", d.keymap("model_" + variant)))
        if not d.hooks:
            return "inconclusive", {"reason": "no WBDBG hooks in this build", "ops": ops}
        op("open_target_2", lambda: d.open_target(keyboard="require"))
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
                d.install(open(lst).read().split())
                abi = installed_abi(SWPKG)
                res.note("swiftkey primaryCpuAbi %s" % abi)
                if abi != "x86_64":  # never time an ARM-translated reference as if it were native
                    raise Inconclusive("SwiftKey installed with primaryCpuAbi %s, not native x86_64" % abi)
                d.activate()
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
        if d.readback is not None:
            res.note("display readback %s" % json.dumps(d.readback))
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
