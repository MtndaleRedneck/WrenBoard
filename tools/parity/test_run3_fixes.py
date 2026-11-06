#!/usr/bin/env python3
"""Regressions for the four run-3 harness causes, as the two reviewers agreed them (R1-R4, v3.5).
No adb (a scripted stand-in), no emulator, no APK.
R1 test-only APK: the app build command is the reviewed one (no injected testOnly property); `-t` is added
   to `adb install` ONLY for the baseline / candidate WrenBoard debug APKs (and the model variants re-signed
   from them) on an emulator. The reference (SwiftKey) and release installs never get -t.
R2 SwiftKey component: derived from the fetched base APK's manifest by fetch_swiftkey.resolve_ime() (pinned
   aapt2 xmltree; the one service with android:permission BIND_INPUT_METHOD and action
   android.view.InputMethod), passed to run_legs as --reference-ime, and inside the same bounded wait
   (40 polls, 2 s apart) exactly that component must be in `ime list -a -s` before `ime enable`.
   Unresolved, ambiguous or never listed: inconclusive. Fixture: Tab's real 9.13.18.4 manifest dump.
R3 release keymap: keyboard screenshot and geometry log line at keymap time (v3.4); after any row-model
   mismatch ALL letter rows are re-probed within a fixed budget; SPACE must append exactly " " and BKSP
   delete exactly one character; the whole verified map must type itself or nothing counts.
R4 crashlog: tag-aware subject layouts only (am_proc_start [user,pid,uid,pkg], am_crash / am_anr payload
   subject, Java "Process: pkg, PID: n", native tombstone, "Start proc n:pkg/"), ANR traces by their
   subject; run 3's real reference events and ANR trace give no finding for the test app, and a synthetic
   real ANR, Java crash or native crash of it fails crash_anr_gate."""
import json, os, re, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import adb as realA
import run_legs as RL
import crashlog as CL
import fetch_swiftkey as F
import testkit as K

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

SLEEPS = []
RL.time.sleep = lambda s: SLEEPS.append(s)
TD = os.path.join(HERE, "testdata")
section = {"name": None}
def sec(name):
    section["name"] = name; print("== " + name)

# ---------------------------------------------------------------- R1
sec("R1 test-only APK: -t only for the app roles' debug installs")
WF = open(os.path.join(ROOT, ".github", "workflows", "parity.yml")).read()
asm = [l.strip() for l in WF.splitlines() if "gradlew" in l and re.search(r"\bassemble\w*", l) and not l.strip().startswith("#")]
check(asm == ["./gradlew --no-daemon assembleStableDebug -Pandroid.injected.build.abi=x86_64"],
      "R1 the app build command is exactly the reviewed 4f364c1 one: %s" % asm)
check("injected.testOnly" not in WF, "R1 no android.injected.testOnly property anywhere in the workflow")
check(not re.search(r"adb\S*\s+install\b[^\n]*\s-t\b", WF), "R1 the workflow itself passes no `adb install -t`")
class InstA:
    def __init__(self, emulator=True): self.cmds, self.emulator = [], emulator
    def run(self, cmd, timeout=60, check=True): self.cmds.append(list(cmd)); return "Success\n"
    def sh(self, c, timeout=60, check=True):
        self.cmds.append(["sh", c])
        return ("1\n" if self.emulator else "\n") if c in ("getprop ro.boot.qemu", "getprop ro.kernel.qemu") else ""
    def log(self, *a): pass
tmp = tempfile.mkdtemp(prefix="r1-")
apk = os.path.join(tmp, "app-debug.apk"); open(apk, "wb").write(b"x")
var = os.path.join(tmp, "corrupt.apk"); open(var, "wb").write(b"y")
rel = os.path.join(tmp, "WrenBoard-1.5.apk"); open(rel, "wb").write(b"z")
sk = [os.path.join(tmp, n) for n in ("com.touchtype.swiftkey.apk", "config.x86_64.apk")]
for p in sk: open(p, "wb").write(b"s")
def installs(dev, apks, emulator=True):
    RL.A = InstA(emulator)
    try:
        dev.install(apks); r = "ok"
    except RL.Inconclusive as e:
        r = "inconclusive: %s" % e
    return [c for c in RL.A.cmds if c[:1] == ["adb"]], r
for role in ("baseline", "candidate"):
    dev = RL.Device(RL.WPKG, app_debug=RL.app_debug_role(role))
    cm, r = installs(dev, [apk])
    check(r == "ok" and cm == [["adb", "install", "-r", "-g", "-t", apk]], "R1 %s debug APK: adb install -r -g -t <apk> on the emulator: %s" % (role, cm))
    cm, r = installs(dev, [var])
    check(r == "ok" and cm == [["adb", "install", "-r", "-g", "-t", var]], "R1 %s model variant (re-signed from the debug APK): -t as well: %s" % (role, cm))
    cm, r = installs(dev, [apk], emulator=False)
    check(r.startswith("inconclusive") and cm == [], "R1 %s debug APK on a non-emulator: no install at all, inconclusive (%s)" % (role, r[:60]))
dev = RL.Device(RL.WPKG, app_debug=RL.app_debug_role("release"))
cm, r = installs(dev, [rel])
check(r == "ok" and cm == [["adb", "install", "-r", "-g", rel]], "R1 release install: adb install -r -g <apk>, no -t: %s" % cm)
dev = RL.Device(RL.SWPKG, app_debug=RL.app_debug_role("reference"))
cm, r = installs(dev, sk)
check(r == "ok" and cm == [["adb", "install-multiple", "-r", "-g"] + sk], "R1 reference (SwiftKey) install: adb install-multiple -r -g, no -t: %s" % cm)
check(RL.app_debug_role("baseline") and RL.app_debug_role("candidate") and not RL.app_debug_role("reference") and not RL.app_debug_role("release"),
      "R1 app_debug_role: true for baseline and candidate only")
import inspect
msrc = inspect.getsource(RL.main)
check("Device(SWPKG if o.role == \"reference\" else WPKG, app_debug=app_debug_role(o.role))" in msrc, "R1 main() builds the role's Device with app_debug_role(role)")
dev = RL.Device(RL.SWPKG, app_debug=True)
cm, r = installs(dev, sk)
check(r.startswith("inconclusive") and cm == [], "R1 -t is refused for any package other than WrenBoard, even if asked (%s)" % r[:60])
psrc = inspect.getsource(RL.leg_release_provenance) + open(os.path.join(HERE, "check_apk.py")).read()
check("check_apk.check(" in inspect.getsource(RL.leg_release_provenance), "R1 release provenance still runs check_apk.check before the install")
check(re.search(r"^permissions:\s*\n\s*contents: read\s*$", WF, re.M) is not None and re.search(r"^\s*on:\s*\n\s*workflow_dispatch:", WF, re.M)
      and not re.search(r"^\s*(push|pull_request|schedule):", WF, re.M) and set(re.findall(r"retention-days: (\d+)", WF)) == {"7"},
      "R1 workflow still workflow_dispatch only, contents: read, 7-day retention")

# ---------------------------------------------------------------- R2
sec("R2 SwiftKey IME component from the fetched manifest")
FIX = open(os.path.join(TD, "swiftkey_9.13.18.4_ime_service_xmltree.txt")).read()
SKC = "com.touchtype.swiftkey/com.touchtype.KeyboardService"
check(F.ime_services(FIX) == ("com.touchtype.swiftkey", [SKC]), "R2 the real 9.13.18.4 dump gives exactly one IME service: %s" % (F.ime_services(FIX),))
check(F.ime_services(FIX.replace('"com.touchtype.KeyboardService"', '".KeyboardService"')) == ("com.touchtype.swiftkey", ["com.touchtype.swiftkey/com.touchtype.swiftkey.KeyboardService"]),
      "R2 a relative \".KeyboardService\" is expanded against the manifest package")
check(F.ime_services(FIX.replace('"com.touchtype.KeyboardService"', '"KeyboardService"'))[1] == ["com.touchtype.swiftkey/com.touchtype.swiftkey.KeyboardService"],
      "R2 a bare class name is expanded against the manifest package")
svc = FIX[FIX.index("          E: service"):]
two = FIX + svc.replace("com.touchtype.KeyboardService", "com.touchtype.OtherImeService")
check(len(F.ime_services(two)[1]) == 2, "R2 two qualifying services are both found (ambiguous)")
noperm = svc.replace("com.touchtype.KeyboardService", "com.touchtype.NoPerm").replace("android.permission.BIND_INPUT_METHOD", "android.permission.BIND_JOB_SERVICE")
noact = svc.replace("com.touchtype.KeyboardService", "com.touchtype.NoAction").replace('"android.view.InputMethod"', '"android.intent.action.MAIN"')
check(F.ime_services(FIX + noperm + noact)[1] == [SKC], "R2 a service without BIND_INPUT_METHOD, or without the InputMethod action, is not an IME service")
check(F.ime_services(noperm.replace("NoPerm", "X") + "")[1] == [], "R2 no qualifying service: nothing found")
# resolve_ime through the pinned build-tools copy of aapt2 (a stand-in script that prints the fixture)
def bt(xmltree, rc=0):
    root = tempfile.mkdtemp(prefix="r2sdk-"); d = os.path.join(root, "build-tools", F.apksig.BUILD_TOOLS); os.makedirs(d)
    open(os.path.join(d, "source.properties"), "w").write("Pkg.Revision=%s\n" % F.apksig.BUILD_TOOLS)
    fx = os.path.join(root, "xmltree.txt"); open(fx, "w").write(xmltree)
    p = os.path.join(d, "aapt2")
    open(p, "w").write('#!/bin/sh\n[ "$1 $2 $3 $4" = "dump xmltree --file AndroidManifest.xml" ] || exit 9\ncat "%s"\nexit %d\n' % (fx, rc)); os.chmod(p, 0o755)
    os.environ["ANDROID_HOME"] = root
    return root
def resolve(xmltree, rc=0):
    bt(xmltree, rc); out = tempfile.mkdtemp(prefix="r2out-"); a = os.path.join(out, "com.touchtype.swiftkey.apk"); open(a, "wb").write(b"x")
    F.resolve_ime(a, out)
    return json.load(open(os.path.join(out, "ime-component.json"))), os.path.join(out, "ime-component.json")
rec, good_json = resolve(FIX)
check(rec["component"] == SKC and rec["candidates"] == [SKC] and F.apksig.BUILD_TOOLS in rec["reader"],
      "R2 resolve_ime on the real dump, pinned aapt2 %s: %s" % (F.apksig.BUILD_TOOLS, rec["component"]))
for xt, rc, why, want in ((two, 0, "two IME services", "ambiguous"), (FIX.replace(svc, noperm), 0, "no BIND_INPUT_METHOD service", "unresolved"),
                          (FIX, 1, "aapt2 exits nonzero", "unresolved"),
                          (FIX.replace('package="com.touchtype.swiftkey" (Raw: "com.touchtype.swiftkey")', 'package="com.example.other" (Raw: "com.example.other")'), 0, "manifest of another package", "unresolved")):
    rec2, _ = resolve(xt, rc)
    check(rec2["component"] is None and (rec2["reason"] or "").startswith(want), "R2 %s: component null, %s (%s)" % (why, want, rec2["reason"]))
src_rl = open(os.path.join(HERE, "run_legs.py")).read()
check("KeyboardService" not in src_rl and not hasattr(RL, "SWIME"), "R2 run_legs.py carries no hardcoded SwiftKey component")
fsrc = open(os.path.join(HERE, "fetch_swiftkey.py")).read()
check("KeyboardService" not in fsrc and len(re.findall(r"^    resolve_ime\(apk, out\)$", fsrc, re.M)) == 1 and len(re.findall(r"^    resolve_ime\(paths\[0\], out\)", fsrc, re.M)) == 1,
      "R2 fetch_swiftkey derives it on both routes (base APK), with no hardcoded name either")
check('--reference-ime "$RUNNER_TEMP/sk/ime-component.json"' in WF and WF.count("--reference-ime") == 1 and re.search(r"--role reference [^\n]*--reference-ime", WF),
      "R2 the workflow passes the fetch's ime-component.json to the reference run_legs only")
# run_legs.reference_ime
def ref_ime(path):
    try:
        return RL.reference_ime(path)
    except RL.Inconclusive as e:
        return "inconclusive: %s" % e
check(ref_ime(good_json) == SKC, "R2 reference_ime() reads the derived component")
amb_json = resolve(two)[1]
for p, why in ((None, "no --reference-ime"), (os.path.join(tmp, "absent.json"), "file missing"), (amb_json, "ambiguous record")):
    check(ref_ime(p).startswith("inconclusive"), "R2 reference_ime: %s -> %s" % (why, ref_ime(p)[:90]))
bad = os.path.join(tmp, "bad.json"); json.dump({"component": "com.example.other/.Ime"}, open(bad, "w"))
check(ref_ime(bad).startswith("inconclusive"), "R2 reference_ime: a component of another package is refused")
check(msrc.index("reference_ime(o.reference_ime)") < msrc.index("d.activate()\n                res.note(\"swiftkey"), "R2 main() resolves the component before activating SwiftKey")
class ImeA:
    """`ime list -a -s` shows `listed` from poll number `after` on (1-based; None = never). `ime enable`
    of an id not yet listed fails exactly as the run-3 device did (adb shell exit 255)."""
    def __init__(self, listed, after):
        self.listed = listed if isinstance(listed, list) else [listed]
        self.after, self.polls, self.cmds, self.enabled, self.default, self.logs = after, 0, [], set(), "", []
    def log(self, *a): self.logs.append(" ".join(str(x) for x in a))
    def visible(self): return self.after is not None and self.polls >= self.after
    def sh(self, c, timeout=60, check=True):
        self.cmds.append(c)
        if c == "ime list -a -s":
            self.polls += 1
            return "com.android.inputmethod.latin/.LatinIME\n" + ("".join(l + "\n" for l in self.listed) if self.visible() else "")
        m = re.match(r"ime (enable|set) (\S+)$", c)
        if m:
            if not (self.visible() and m.group(2) in self.listed):
                raise realA.CommandError("exit 255: adb shell %s :: Unknown input method %s cannot be enabled for user #0" % (c, m.group(2)))
            if m.group(1) == "enable": self.enabled.add(m.group(2))
            elif m.group(2) in self.enabled: self.default = m.group(2)
            return ""
        if c == "settings get secure default_input_method":
            return self.default + "\n"
        return ""
def act(pkg, listed, after, expect=None):
    RL.A = ImeA(listed, after); del SLEEPS[:]
    d = RL.Device(pkg); d.ime_expect = expect
    try:
        d.activate(); return d, RL.A, "ok"
    except RL.Inconclusive as e:
        return d, RL.A, "inconclusive: %s" % e
    except realA.CommandError as e:
        return d, RL.A, "error: %s" % e
def first(cmds, pre):
    return next((i for i, c in enumerate(cmds) if c.startswith(pre)), None)
DER = RL.reference_ime(good_json)
d, a, r = act(RL.SWPKG, SKC, 4, DER)
check(r == "ok" and d.ime == SKC, "R2 derived component listed on the 4th poll: activated as %s (%s)" % (SKC, r[:120]))
fe, lp = first(a.cmds, "ime enable"), max([i for i, c in enumerate(a.cmds) if c == "ime list -a -s"], default=-1)
check(fe is not None and fe > lp and a.polls == 4 and SLEEPS[:3] == [2, 2, 2], "R2 first `ime enable` only after the poll that listed it, 2 s apart (polls %d)" % a.polls)
d, a, r = act(RL.SWPKG, SKC, None, DER)
check(r.startswith("inconclusive") and "never appeared" in r and a.polls == 40 and first(a.cmds, "ime enable") is None,
      "R2 never listed: 40 polls, no `ime enable`, inconclusive (%d polls)" % a.polls)
sw_sleeps = list(SLEEPS)
d, a, r = act(RL.WPKG, RL.WPKG + "/org.futo.inputmethod.latin.LatinIME", None)
check(r.startswith("inconclusive") and a.polls == 40 and SLEEPS == sw_sleeps, "R2 same helper and bound as WrenBoard: 40 polls, same sleeps")
d, a, r = act(RL.SWPKG, SKC, 1, None)
check(r.startswith("inconclusive") and "unresolved" in r and a.polls == 0, "R2 unresolved component: inconclusive before any poll or enable (%s)" % r[:70])
d, a, r = act(RL.SWPKG, RL.SWPKG + "/com.touchtype.OtherService", 1, DER)
check(r.startswith("inconclusive") and first(a.cmds, "ime enable") is None, "R2 only another SwiftKey component listed: never accepted, inconclusive")
d, a, r = act(RL.SWPKG, [SKC, RL.SWPKG + "/com.touchtype.OtherService"], 2, DER)
check(r.startswith("inconclusive") and "not exactly" in r and first(a.cmds, "ime enable") is None, "R2 the derived one plus another SwiftKey component listed: ambiguous, inconclusive")
d, a, r = act(RL.SWPKG, "com.touchtype.swiftkey/.KeyboardService", 1, "com.touchtype.swiftkey/com.touchtype.swiftkey.KeyboardService")
check(r == "ok" and d.ime == "com.touchtype.swiftkey/.KeyboardService", "R2 the short \"pkg/.Name\" listing form matches its expanded component, enabled as listed")
d, a, r = act(RL.WPKG, RL.WPKG + "/org.futo.inputmethod.latin.LatinIME", 2)
check(r == "ok" and d.ime == RL.WPKG + "/org.futo.inputmethod.latin.LatinIME" and a.polls == 2, "R2 WrenBoard path unchanged: listed on poll 2, activated")

# ---------------------------------------------------------------- R3
sec("R3 release keymap: frame, geometry log, full re-probe, SPACE / BKSP, fail closed")
XML3 = open(os.path.join(TD, "run3_keys_release.xml")).read()
class KeyA:
    """Release keyboard without hooks over run 3's real keys_release dump. `typer(x, y)` returns the text a
    tap inserts (default: nothing types). shot() is the real adb.shot with screencap stubbed to a PNG."""
    TPKG = "com.wrenboard.imetarget"
    bounds, centre, labelled = staticmethod(realA.bounds), staticmethod(realA.centre), staticmethod(realA.labelled)
    CommandError = realA.CommandError
    def __init__(self, out, typer=lambda x, y: "", shot_ok=True):
        self.out, self.typer, self.shot_ok, self.text, self.order, self.loglines, self.saved = out, typer, shot_ok, "", [], [], {}
    def log(self, *a): self.loglines.append(" ".join(str(x) for x in a))
    def save(self, rel, text): self.saved[rel] = text
    def logs(self, *a, **k): return ""   # no WBDBG lines: a build without hooks (v1.5.0)
    def mark(self, label): return 0.0
    def dump_windows(self, tag):
        self.order.append("dump:" + tag); return ET.fromstring(XML3[XML3.find("<"):])
    def shot(self, name):
        self.order.append("shot:" + name)
        realA.OUT = self.out
        def fake_run(cmd, stdout=None, timeout=None, **k):
            if self.shot_ok: stdout.write(open(K.synth_png(os.path.join(self.out, ".tmp.png")), "rb").read())
            return subprocess.CompletedProcess(cmd, 0 if self.shot_ok else 1)
        saved, realA.subprocess.run = realA.subprocess.run, fake_run
        try:
            return realA.shot(name)
        finally:
            realA.subprocess.run = saved
    def clear_field(self): self.text = ""; self.order.append("clear")
    def field(self, since=0.0): return self.text
    def sh(self, c, timeout=60, check=True):
        for m in re.finditer(r"input tap (\d+) (\d+)", c):
            self.order.append("tap"); t = self.typer(int(m.group(1)), int(m.group(2)))
            self.text = self.text[:-1] if t == "\b" else self.text + t
        return ""
out = tempfile.mkdtemp(prefix="f3-")
RL.A = KeyA(out); d = RL.Device(RL.WPKG); d.settle = lambda limit=12: (RL.A.text, 0.0, True)
try:
    d.keymap("release"); r = "ok"
except RL.Inconclusive as e:
    r = str(e)
o = RL.A.order
ix = lambda v: o.index(v) if v in o else -1
check(ix("shot:keymap_release") >= 0 and ix("shot:keymap_release") == ix("dump:keys_release") + 1,
      "R3 screenshot keymap_release taken right after the keys_release dump")
check(0 <= ix("shot:keymap_release") < ix("tap"), "R3 screenshot taken before any tap-to-text tap")
png = os.path.join(out, "png", "keymap_release.png")
check(os.path.isfile(png) and open(png, "rb").read(8) == b"\x89PNG\r\n\x1a\n", "R3 written as png/keymap_release.png (same adb.shot / screencap -p path as png/setup_readback.png)")
geo = [l for l in RL.A.loglines if l.startswith("keymap geometry release ")]
check(len(geo) == 1, "R3 exactly one 'keymap geometry release' log line")
g = json.loads(geo[0][len("keymap geometry release "):]) if geo else {}
check(g.get("ime_window") == [0, 1147, 1080, 2316], "R3 log carries the IME window bounds from the dump: %s" % g.get("ime_window"))
check(g.get("keyboard_area") == [0, 1273, 1080, 2181], "R3 log carries the keyboard area: %s" % g.get("keyboard_area"))
L4 = (g.get("layouts") or {}).get("rows4") or {}
check(L4.get("SPACE") == [486, 1953, 594, 2180] and L4.get("e") == [216, 1272, 324, 1499] and L4.get("BKSP") == [945, 1726, 1053, 1953],
      "R3 rows4 rects around the run-3 tap points (SPACE %s, e %s)" % (L4.get("SPACE"), L4.get("e")))
L5 = (g.get("layouts") or {}).get("rows5_number_row") or {}
check(len(L4) == 29 and len(L5) == 29 and L5.get("q") == [0, 1454, 108, 1635], "R3 rects for both layouts, 28 keys + row bands each (rows5 q %s)" % L5.get("q"))
check(L4.get("row_bands") == [[1273.0, 1500.0], [1500.0, 1727.0], [1727.0, 1954.0], [1954.0, 2181.0]] and len(L5.get("row_bands") or []) == 5,
      "R3 row bands logged: rows4 %s" % L4.get("row_bands"))
check(r.startswith("keymap release lacks") and "SPACE" in r and "BKSP" in r and d.km == {}, "R3 nothing typed: still Inconclusive, no key accepted (%s)" % r[:60])
keys = json.loads(RL.A.saved.get("geom/release_keys.json", "{}"))
check(keys.get("keys") == {} and set(keys.get("unverified", [])) >= {"SPACE", "BKSP"}, "R3 geom json records no verified key")
# A typer that types only letters (SPACE and BKSP do nothing), the run-3 outcome: still Inconclusive.
area = (0, 1273, 1080, 2181); g4 = RL.geometry_keys(area, "rows4")
inv = {v: k for k, v in g4.items()}
RL.A = KeyA(tempfile.mkdtemp(prefix="f3b-"), typer=lambda x, y: inv.get((x, y), "") if len(inv.get((x, y), "")) == 1 else "")
d = RL.Device(RL.WPKG); d.settle = lambda limit=12: (RL.A.text, 0.0, True)
try:
    d.keymap("release"); r = "ok"
except RL.Inconclusive as e:
    r = str(e)
check(r == "keymap release lacks ['SPACE', 'BKSP'] (hooks False; unverified by tap-to-text)", "R3 letters type, SPACE/BKSP do not: the run-3 line, unchanged (%s)" % r[:80])
# Screenshot failure is logged and never turns into a pass or a crash of the keymap step.
RL.A = KeyA(tempfile.mkdtemp(prefix="f3c-"), shot_ok=False); d = RL.Device(RL.WPKG); d.settle = lambda limit=12: (RL.A.text, 0.0, True)
try:
    d.keymap("release"); r = "ok"
except RL.Inconclusive as e:
    r = "inconclusive"
except Exception as e:
    r = "error %r" % e
check(r == "inconclusive" and any(l.startswith("keymap screenshot failed:") for l in RL.A.loglines), "R3 a failed screencap is logged; the keymap gate still decides (%s)" % r)
# The artifact gate stages the new PNG exactly like the setup PNG.
src = tempfile.mkdtemp(prefix="f3gate-"); os.makedirs(os.path.join(src, "png"))
if os.path.isfile(png):  # the frame the keymap step itself wrote
    shutil.copy(png, os.path.join(src, "png", "keymap_release.png"))
K.synth_png(os.path.join(src, "png", "setup_readback.png"))
dest = os.path.join(tempfile.mkdtemp(prefix="f3up-"), "release")
gp = subprocess.run(["bash", os.path.join(HERE, "filter_artifacts.sh"), src, dest], capture_output=True, text=True, timeout=60)
check(gp.returncode == 0 and os.path.isfile(os.path.join(dest, "png", "keymap_release.png")) and os.path.isfile(os.path.join(dest, "png", "setup_readback.png")),
      "R3 filter_artifacts.sh stages png/keymap_release.png beside png/setup_readback.png (rc %d)" % gp.returncode)


# R3 new: a run-3-like release keyboard. Ground truth in the run-3 keyboard area [0,1273][1080,2181]: the
# q-row keys' top band (y < 1440) types the number hint (q1 w2 e3 ... p0), except that e, t, y, u, i, o, p
# type their letter when tapped alone into an empty field (run 3: the batch typed 'abcd3fgh8...' but single
# taps of e..p typed letters, while q, r, w typed 1, 4, 2 even alone); the q row proper is [1440,1580),
# the a row [1580,1760), the z row with BKSP [1760,1960), the bottom row with SPACE [1960,2181).
AREA3 = (0, 1273, 1080, 2181)
HINT = dict(zip("qwertyuiop", "1234567890"))
def run3_truth(x, y, text, space=" ", bksp=1):
    l, t, r, b = AREA3; kw = (r - l) / 10.0
    if y < t or y >= b: return ""
    if y < 1580:
        j = int((x - l) // kw); k = "qwertyuiop"[j] if 0 <= j < 10 else ""
        if y < 1440:
            return k if (text == "" and k in "etyuiop") else HINT.get(k, "")
        return k
    if y < 1760:
        j = int((x - l - 0.5 * kw) // kw); return "asdfghjkl"[j] if 0 <= j < 9 else ""
    if y < 1960:
        if x >= r - 1.5 * kw: return "\b" * bksp
        j = int((x - l - 1.5 * kw) // kw); return "zxcvbnm"[j] if 0 <= j < 7 else ""
    return space if l + 3 * kw <= x < r - 3 * kw else ""
class TruthA(KeyA):
    """KeyA whose taps go through truth(x, y, text) -> inserted text, or backspaces ("\b" each)."""
    def __init__(self, out, truth, max_taps=None):
        KeyA.__init__(self, out); self.truth, self.max_taps, self.probes = truth, max_taps, 0
    def clear_field(self): self.text = ""; self.order.append("clear"); self.probes += 1; self.ntap = 0
    def sh(self, c, timeout=60, check=True):
        for m in re.finditer(r"input tap (\d+) (\d+)", c):
            self.order.append("tap"); self.ntap = getattr(self, "ntap", 0) + 1
            if self.max_taps is not None and self.ntap > self.max_taps: continue
            out = self.truth(int(m.group(1)), int(m.group(2)), self.text)
            for ch in out:
                self.text = self.text[:-1] if ch == "\b" else self.text + ch
        return ""
def keymap_with(truth, **kw):
    RL.A = TruthA(tempfile.mkdtemp(prefix="r3t-"), truth, **kw); d = RL.Device(RL.WPKG); d.settle = lambda limit=12: (RL.A.text, 0.0, True)
    try:
        d.keymap("release"); r = "ok"
    except RL.Inconclusive as e:
        r = "inconclusive: %s" % e
    return d, json.loads(RL.A.saved.get("geom/release_keys.json", "{}")), r
d, ev, r = keymap_with(run3_truth)
g4, g5 = RL.geometry_keys(AREA3, "rows4"), RL.geometry_keys(AREA3, "rows5_number_row")
tt = ev.get("tap_to_text", [])
check(any(m["source"] == "geometry:rows4" and m["keys"] == "qwertyuiop" for m in ev.get("row_model_mismatch", [])),
      "R3 run-3-like: the rows4 q row is recorded as a row-model mismatch (%s)" % [m["typed"] for m in ev.get("row_model_mismatch", [])][:2])
rp_ = [m for m in tt if m.get("phase") == "reprobe"]
check(sorted(set(m["keys"] for m in rp_)) == sorted(RL.ROWS) and [x["row"] for x in ev.get("reprobe", [])] == list(RL.ROWS),
      "R3 run-3-like: the mismatch triggers a re-probe of ALL three letter rows, not only q, r, w (%s)" % [x["row"] for x in ev.get("reprobe", [])])
check(r == "ok" and sorted(d.km) == sorted(RL.REQUIRED_KEYS), "R3 run-3-like: all 28 keys proven after the re-probe (%s)" % r[:80])
check(all(d.km[k] == g5[k] for k in "qwertyuiop") and all(ev["verified_by"][k] == "geometry:rows5_number_row" for k in "qwertyuiop"),
      "R3 run-3-like: the whole q row comes from the row model that typed it whole (rows5), not e..p from rows4 single taps")
check(d.km["e"] != g4["e"] and all(run3_truth(d.km[k][0], d.km[k][1], "x") == k for k in RL.LETTERS),
      "R3 run-3-like: no accepted letter sits where a tap in a sequence types anything but itself (e not at rows4 %s)" % (g4["e"],))
check(ev.get("final_confirm") == RL.LETTERS, "R3 the verified letters typed together give exactly a..z (%r)" % ev.get("final_confirm"))
sp = [m for m in tt if m.get("keys") == "ok+SPACE" and m["source"] == ev["verified_by"].get("SPACE")]
bk = [m for m in tt if m.get("keys") == "ok+BKSP" and m["source"] == ev["verified_by"].get("BKSP")]
check(sp and sp[0].get("before") == "ok" and sp[0].get("typed") == "ok ", "R3 SPACE proven by appending exactly one space: %r -> %r" % ((sp[0].get("before"), sp[0].get("typed")) if sp else None))
check(bk and bk[0].get("before") == "ok" and bk[0].get("typed") == "o", "R3 BKSP proven by deleting exactly one character: %r -> %r" % ((bk[0].get("before"), bk[0].get("typed")) if bk else None))
probes = RL.A.probes
check(len(rp_) <= RL.REPROBE_BUDGET == 12 and probes <= RL.KEYMAP_PROBE_BUDGET + 1, "R3 within the stated budgets: %d re-probes (<= %d), %d probes in all (<= %d)" % (len(rp_), RL.REPROBE_BUDGET, probes, RL.KEYMAP_PROBE_BUDGET))
# SPACE and BKSP: only an exact one-space growth / one-character delete counts.
for kw_, key, why in (({"space": ""}, "SPACE", "space types nothing"), ({"space": "  "}, "SPACE", "space types two spaces"),
                      ({"space": "\b\bOK "}, "SPACE", "space autocorrects ok -> OK (text changes beyond one space)"),
                      ({"space": "ay "}, "SPACE", "space commits a different word (okay)"),
                      ({"bksp": 2}, "BKSP", "backspace deletes two characters"), ({"bksp": 0}, "BKSP", "backspace deletes nothing")):
    d, ev, r = keymap_with(lambda x, y, text, kw_=kw_: run3_truth(x, y, text, **kw_))
    check(r.startswith("inconclusive") and key in ev.get("unverified", []) and key not in d.km and ev.get("final_confirm") == RL.LETTERS,
          "R3 %s: %s unverified, keymap inconclusive, letters still proven (%s)" % (why, key, r[:70]))
# Fail closed: rows type correctly alone, but the full verified map does not type itself (taps past the 12th
# in a probe are lost): no letter is accepted at all.
truth4 = lambda x, y, text: {v: k for k, v in RL.geometry_keys(AREA3, "rows4").items()}.get((x, y), "").replace("SPACE", " ").replace("BKSP", "\b")
d, ev, r = keymap_with(truth4, max_taps=12)
check(r.startswith("inconclusive") and ev.get("final_confirm") != RL.LETTERS and not any(k in d.km or k in ev.get("keys", {}) for k in RL.LETTERS),
      "R3 the full map cannot be proven (sequence breaks after 12 taps): no letter accepted, inconclusive (final %r)" % ev.get("final_confirm"))
d, ev, r = keymap_with(truth4)
check(r == "ok" and not ev.get("row_model_mismatch") and not ev.get("reprobe"), "R3 a keyboard matching rows4 needs no re-probe and is proven (%s)" % r[:60])
# A keyboard that types the wrong letter everywhere: every row mismatches, budgets hold, nothing accepted.
d, ev, r = keymap_with(lambda x, y, text: "x" if AREA3[1] <= y < AREA3[3] else "")
rp_ = [m for m in ev.get("tap_to_text", []) if m.get("phase") == "reprobe"]
check(r.startswith("inconclusive") and set(ev.get("keys", {})) <= {"x"},
      "R3 a keyboard typing 'x' everywhere proves at most the x key, never another (%s)" % sorted(ev.get("keys", {})))
check(len(rp_) <= RL.REPROBE_BUDGET and RL.A.probes <= RL.KEYMAP_PROBE_BUDGET + 1, "R3 worst case stays inside the budgets (%d re-probes, %d probes)" % (len(rp_), RL.A.probes))
vsrc = inspect.getsource(RL.Device.verify_keys)
check("REPROBE_BUDGET" in vsrc and "KEYMAP_PROBE_BUDGET" in vsrc and RL.KEYMAP_PROBE_BUDGET == 140, "R3 budgets are fixed constants (%d probes, %d re-probes)" % (RL.KEYMAP_PROBE_BUDGET, RL.REPROBE_BUDGET))

# ---------------------------------------------------------------- R4
sec("R4 crashlog: tag-aware subject attribution")
TP = "com.wrenboard.imetarget"
EV3 = open(os.path.join(TD, "run3_reference_setup_events_excerpt.txt")).read()
pids = CL.pkg_pids(TP, CL.parse(EV3))
check(pids == {4149}, "R4 run-3 events: the test app's pids are {4149} (am_proc_start / am_proc_bound): %s" % sorted(pids))
check(522 not in pids, "R4 system_server 522 (notification_cancel_all writer and caller) is not a test-app pid")
check(10192 not in pids, "R4 uid 10192 (am_pss / am_proc_start uid field) is not a test-app pid")
fs = CL.findings(TP, "", EV3, "")
check(fs == [], "R4 run-3 reference setup: the com.google.android.as ANR is no longer charged to the test app (%d)" % len(fs))
REAL_ANR = "         1793992510.000   522  3073 I am_anr  : [0,4149,com.wrenboard.imetarget,818527813,Input dispatching timed out]\n"
fs = CL.findings(TP, "", EV3 + REAL_ANR, "")
check(len(fs) == 1 and fs[0]["kind"] == "ANR" and "4149,com.wrenboard.imetarget" in fs[0]["text"], "R4 an am_anr of the test app itself is still a finding")
CRASH = ("1793992511.000  4149  4149 E AndroidRuntime: FATAL EXCEPTION: main\n"
         "1793992511.000  4149  4149 E AndroidRuntime: java.lang.IllegalStateException: boom\n")
fs = CL.findings(TP, CRASH, EV3, "")
check(len(fs) == 1 and fs[0]["pid"] == 4149 and not fs[0]["named_in_block"], "R4 an unnamed crash block of pid 4149 is attributed through am_proc_start")
SYS = ("1793992512.000   522   522 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: main\n"
       "1793992512.000   522   522 E AndroidRuntime: java.lang.RuntimeException: not the test app\n"
       "1793992513.000 10192 10192 E AndroidRuntime: FATAL EXCEPTION: main\n")
check(CL.findings(TP, SYS, EV3, "") == [], "R4 crash blocks of pid 522 (system_server) and of 'pid' 10192 (a uid) are not charged to the test app")
NAMED = ("1793992514.000  4150  4150 E AndroidRuntime: FATAL EXCEPTION: main\n"
         "1793992514.000  4150  4150 E AndroidRuntime: Process: com.wrenboard.imetarget, PID: 4150\n")
fs = CL.findings(TP, NAMED, EV3, "")
check(len(fs) == 1 and fs[0]["pid"] == 4150 and fs[0]["kind"] == "crash", "R4 a named FATAL EXCEPTION of the test app is still a finding")
check(CL.pkg_pids(TP, CL.parse("1.0  1000  1200 I ActivityManager: Start proc 4242:com.wrenboard.imetarget/u0a192 for top-activity {x}\n")) == {4242},
      "R4 ActivityManager 'Start proc <pid>:<pkg>/' gives the app pid")
# End to end: crash_anr_gate over a device whose buffers are run 3's events (+/- a real test-app ANR).
class GateA:
    TPKG = TP
    def __init__(self, events): self.ev, self.saved = events, {}
    def logs(self, b, since=None, until=None, filters=()): return self.ev if b == "events" else ""
    def sh(self, c, timeout=60, check=True): return ""
    def save(self, rel, text): self.saved[rel] = text
for evs, want, m in ((EV3, "pass", "run-3 buffers as they were"), (EV3 + REAL_ANR, "fail", "plus an ANR of the test app")):
    RL.A = GateA(evs)
    st, evd = RL.leg_crash_anr_gate(RL.Device(RL.WPKG), {"windows": [("setup", 0, 2e9)]})
    check(st == want, "R4 crash_anr_gate %s: %s (findings %s)" % (m, st, evd.get("findings")))


# R4 new: exact layouts, run 3's real reference events + ANR trace, synthetic real target faults.
_atf = getattr(CL, "anr_trace_findings", None)
ATF = lambda pkg, traces: _atf(pkg, traces) if _atf else None   # None (a failed check) when the trace reader is absent
LINE = lambda t, pid, tid, tag, msg, prio="I": "%14.3f %5d %5d %s %-8s: %s\n" % (t, pid, tid, prio, tag, msg)
E = lambda e: CL.parse(e)
check(CL.pkg_pids(TP, E(LINE(1.0, 522, 566, "am_proc_start", "[0,4149,10192,com.wrenboard.imetarget,next-top-activity,{x}]"))) == {4149},
      "R4 am_proc_start [user,pid,uid,pkg,...]: the pid field (4149), never the uid (10192) or the writer (522)")
check(CL.pkg_pids(TP, E(LINE(1.0, 522, 566, "am_proc_start", "[0,4150,10192,com.wrenboard.imetarget:remote,service,{x}]"))) == {4150},
      "R4 am_proc_start of a pkg:<name> process counts")
check(CL.pkg_pids(TP, E(LINE(1.0, 522, 566, "am_proc_start", "[0,4151,10193,com.wrenboard.imetarget2,activity,{x}]"))) == set(),
      "R4 am_proc_start of another package with the same prefix does not")
check(CL.pkg_pids(TP, E(LINE(1.0, 522, 522, "notification_cancel_all", "[1000,522,com.wrenboard.imetarget,0,0,0,5,NULL]")
                     + LINE(1.0, 522, 564, "am_pss", "[4149,10192,com.wrenboard.imetarget,1,2,3,4,0,2,2]"))) == set(),
      "R4 notification_cancel_all [uid,callingPid,pkg] and am_pss are never read for pids")
check(CL.pkg_pids(TP, E(LINE(1.0, 9001, 9001, "DEBUG", "pid: 4152, tid: 4160, name: RenderThread  >>> com.wrenboard.imetarget <<<", "F"))) == {4152},
      "R4 native tombstone header 'pid: N, tid: N, name: ... >>> pkg <<<': the subject pid, not crash_dump's 9001")
check(CL.pkg_pids(TP, E(LINE(1.0, 4153, 4153, "AndroidRuntime", "Process: com.wrenboard.imetarget, PID: 4153", "E"))) == {4153}, "R4 Java 'Process: pkg, PID: n'")
check(CL.pkg_pids(TP, E(LINE(1.0, 522, 600, "ActivityManager", "Start proc 4154:com.wrenboard.imetarget/u0a192 for top-activity {x}"))) == {4154}, "R4 'Start proc <pid>:<pkg>/'")
OTHER_ANR = LINE(5.0, 522, 3073, "am_anr", "[0,1659,com.google.android.as,818527813,No response to onStartJob]")
TGT_ANR = LINE(5.5, 522, 3073, "am_anr", "[0,4149,com.wrenboard.imetarget,818527813,Input dispatching timed out]")
fs = CL.findings(TP, "", OTHER_ANR + TGT_ANR, "")
check(len(fs) == 1 and fs[0].get("subject_pid") == 4149 and "com.google.android.as" not in fs[0]["text"],
      "R4 am_anr entries are judged one by one by their payload subject: two in the same system_server block, only the target's counts")
check(CL.findings(TP, "", OTHER_ANR.replace("1659", "522"), "") == [], "R4 an am_anr whose subject pid is system_server's own is not charged to the target")
EV_FULL = open(os.path.join(TD, "run3_reference_setup_events.txt")).read()
ANR_FULL = open(os.path.join(TD, "run3_reference_anr_2026-11-06-19-14-28-515.txt")).read()
import hashlib
check(hashlib.sha256(EV_FULL.encode()).hexdigest() == "142f59d04ea076edbf44c942a08a61b74d7e0747c9664289f7d3a17fe12c1b1d"
      and hashlib.sha256(ANR_FULL.encode()).hexdigest() == "494ac19efd75fe7ce23a80715a39ad9c724e717c664425a610fe79f34bed490b",
      "R4 fixtures are run 3's actual parity-reference/sys/full/setup_events.txt and anr/anr_2026-11-06-19-14-28-515.txt (sha256)")
for pkg in (TP, RL.WPKG, RL.SWPKG):
    check(CL.findings(pkg, "", EV_FULL, "") == [] and ATF(pkg, {"anr_2026-11-06-19-14-28-515": ANR_FULL}) == [],
          "R4 NEGATIVE run 3's real reference events + ANR trace: no finding for %s" % pkg)
check(CL.pkg_pids(TP, E(EV_FULL)) == {4149}, "R4 run 3's real events: the test app's only pid is 4149 (%s)" % sorted(CL.pkg_pids(TP, E(EV_FULL))))
asi = CL.findings("com.google.android.as", "", EV_FULL, "") + (ATF("com.google.android.as", {"t": ANR_FULL}) or [])
check(len(asi) == 2 and {f["buffer"] for f in asi} == {"events", "anr_trace"} and all(f["pid"] in (522, 1659) for f in asi),
      "R4 the collector still sees run 3's real ANR, for its real subject com.google.android.as (event + trace)")
sec_tgt = ANR_FULL + "\n----- pid 4149 at 2026-11-06 19:14:29.000000000+0000 -----\nCmd line: com.wrenboard.imetarget\n"
check(ATF(TP, {"t": sec_tgt}) == [], "R4 the target dumped as a secondary process inside another app's ANR trace is not its ANR")
# Synthetic real faults of the target: each detected, each fails crash_anr_gate.
TRACE_TGT = ("Subject: Input dispatching timed out\n\n----- dumping pid: 4149 at 60000\n\n"
             "----- pid 4149 at 2026-11-06 19:20:00.000000000+0000 -----\nCmd line: com.wrenboard.imetarget\nABI: 'x86_64'\n")
JAVA = (LINE(1793992600.0, 4149, 4149, "AndroidRuntime", "FATAL EXCEPTION: main", "E")
        + LINE(1793992600.0, 4149, 4149, "AndroidRuntime", "Process: com.wrenboard.imetarget, PID: 4149", "E")
        + LINE(1793992600.0, 4149, 4149, "AndroidRuntime", "java.lang.IllegalStateException: boom", "E"))
NATIVE = (LINE(1793992601.0, 9001, 9001, "DEBUG", "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***", "F")
          + LINE(1793992601.0, 9001, 9001, "DEBUG", "pid: 4149, tid: 4149, name: wrenboard.imetarget  >>> com.wrenboard.imetarget <<<", "F")
          + LINE(1793992601.0, 9001, 9001, "DEBUG", "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0", "F"))
ANR_EV = LINE(1793992602.0, 522, 3073, "am_anr", "[0,4149,com.wrenboard.imetarget,818527813,Input dispatching timed out]")
ANR_MAIN = (LINE(1793992602.0, 522, 3073, "ActivityManager", "ANR in com.wrenboard.imetarget (com.wrenboard.imetarget/.TestInput)", "E")
            + LINE(1793992602.0, 522, 3073, "ActivityManager", "PID: 4149", "E"))
class GateB:
    TPKG = TP
    def __init__(self, crash="", events="", main="", traces=None):
        self.b, self.traces, self.saved = {"crash": crash, "events": events, "main": main}, dict(traces or {}), {}
    def logs(self, b, since=None, until=None, filters=()): return self.b.get(b, "")
    def sh(self, c, timeout=60, check=True):
        if c == "ls /data/anr": return "".join(n + "\n" for n in self.traces)
        m = re.match(r"cat /data/anr/(\S+)$", c)
        return self.traces.get(m.group(1), "") if m else ""
    def save(self, rel, text): self.saved[rel] = text
def gate(**kw):
    RL.A = GateB(**kw)
    return RL.leg_crash_anr_gate(RL.Device(RL.WPKG), {"windows": [("setup", 0, 2e9)]})
st, evd = gate(events=EV_FULL, traces={"anr_2026-11-06-19-14-28-515": ANR_FULL})
check(st == "pass" and evd["findings"] == 0, "R4 NEGATIVE crash_anr_gate over run 3's real reference events + ANR trace: pass, 0 findings (%s)" % evd.get("by_window"))
for name, kw, kind in (("ANR (am_anr event)", {"events": EV_FULL + ANR_EV}, "ANR"), ("ANR (ActivityManager 'ANR in')", {"main": ANR_MAIN}, "ANR"),
                       ("ANR (trace whose subject is the target)", {"events": EV_FULL, "traces": {"anr_x": TRACE_TGT, "anr_2026-11-06-19-14-28-515": ANR_FULL}}, "ANR"),
                       ("Java crash", {"crash": JAVA, "events": EV_FULL}, "crash"), ("native crash", {"crash": NATIVE, "events": EV_FULL}, "native")):
    st, evd = gate(**kw)
    bw = evd.get("by_window") or []
    check(st == "fail" and any(f["package"] == TP and f["kind"] == kind for f in bw),
          "R4 POSITIVE synthetic %s of the target: detected as %s and crash_anr_gate FAILS (%s)" % (name, kind, [(f["kind"], f["buffer"], f["pid"]) for f in bw]))

print("RESULT", "FAIL" if fails else "PASS", len(fails), "failures")
sys.exit(1 if fails else 0)
