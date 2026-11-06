#!/usr/bin/env python3
"""Local tests of the model_* leg logic (run_legs.leg_model) with a fake adb layer and fake device:
no adb, no emulator. Each scenario scripts what the device log, field and process do during the leg
operations, and checks the leg's status. The key regression: a disable log written while the variant is
activated or the keymap is read (the model initializer is lazy, but layout setup may run first) must be
kept, because nothing in the leg clears the log any more. The leg also keeps two results apart: the
graceful neural-disable proof and the first-run fallback recognition qualification (exact "hello"); a
recognition miss blocks the leg under fallback_recognition_target_missed without changing the graceful facts."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_legs as RL
import results as R

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)


class FakeA:
    TPKG = "com.wrenboard.imetarget"
    def __init__(self, sc):
        self.t, self.sc, self.lines, self.fieldv, self.saved = 1000.0, sc, [], "", {}
    def tick(self):
        self.t += 1.0; return self.t
    def mark(self, label):
        t = self.tick()
        self.lines.append((t, "%.3f  1  1 I PARITYMARK: %s" % (t, label)))
        op = label.replace("op_%s_" % self.sc["variant"], "")
        for when, line in self.sc.get("log", []):   # log lines emitted while an op runs
            if when == op:
                t2 = self.tick(); self.lines.append((t2, "%.3f  4321  4321 E SwipeDecoderDictionary: %s" % (t2, line)))
        return t
    def logs(self, buffer, since=None, until=None, filters=()):
        return "\n".join(l for t, l in self.lines if (since is None or t >= since) and (until is None or t <= until)) + "\n"
    def field(self, since=0.0):
        return self.fieldv
    def clear_field(self):
        self.fieldv = ""
    def shot(self, n): pass
    def save(self, rel, text): self.saved[rel] = text


class FakeDevice:
    def __init__(self, A, sc):
        self.A, self.sc, self.hooks, self.km, self.pkg = A, sc, True, {}, RL.WPKG
        self.keyboard_visible, self.open_modes = True, []
        self.pids = iter(sc.get("pids", ["4321"] * 20))
    def uninstall(self): pass
    def install(self, apks): pass
    def activate(self): pass
    def open_target(self, keyboard="require"): self.open_modes.append(keyboard); self.A.fieldv = ""
    def keymap(self, tag): return {"h": (1, 1)}
    def glide(self, w): self.A.fieldv = self.sc["glide_commit"]
    def type_keys(self, s): self.A.fieldv = (self.A.fieldv + " " + s).strip() + " "
    def settle(self): return self.A.fieldv, 1.0, True
    def pid(self): return next(self.pids)
    def alive(self): return self.sc.get("alive", True)
    def findings(self, since=None, until=None):
        return [{"kind": "crash", "package": RL.WPKG, "buffer": "crash", "t": since or 0, "lines": 5}] if self.sc.get("crash") else []


def run(sc, apk="/etc/hostname"):
    A = FakeA(sc); RL.A = A
    d = FakeDevice(A, sc)
    st, ev = RL.leg_model(sc["variant"])(d, {"variants": {sc["variant"]: apk}})
    return st, ev


NOT_USABLE = "Swipe model not usable (honorable_sturgeon/model_fp32.pte); swipe model disabled"
FAILED = "Swipe model failed in getSuggestions; swipe model disabled"
cases = [
    ("missing: disable logged during ACTIVATE (before glide), legacy hello", {"variant": "missing", "log": [("activate", NOT_USABLE)], "glide_commit": "hello"}, "pass"),
    ("truncated: disable logged during KEYMAP setup, legacy hello", {"variant": "truncated", "log": [("keymap", NOT_USABLE)], "glide_commit": "hello"}, "pass"),
    ("corrupt: load failure logged during the glide, legacy hello", {"variant": "corrupt", "log": [("glide_hello", FAILED)], "glide_commit": "hello"}, "pass"),
    ("garbage commit fails even with disable log", {"variant": "missing", "log": [("glide_hello", NOT_USABLE)], "glide_commit": "hrllo"}, "fail"),
    ("empty glide output fails (recognition target missed; graceful facts kept)", {"variant": "missing", "log": [("glide_hello", NOT_USABLE)], "glide_commit": ""}, "fail"),
    ("hello without any disable evidence fails", {"variant": "truncated", "log": [], "glide_commit": "hello"}, "fail"),
    ("corrupt with only a 'not usable' line fails (wrong cause)", {"variant": "corrupt", "log": [("glide_hello", NOT_USABLE)], "glide_commit": "hello"}, "fail"),
    ("crash finding in the leg fails", {"variant": "missing", "log": [("activate", NOT_USABLE)], "glide_commit": "hello", "crash": True}, "fail"),
    ("keyboard pid changes after activation fails", {"variant": "missing", "log": [("activate", NOT_USABLE)], "glide_commit": "hello",
                                                   "pids": ["4321", "4321", "4321", "4321", "9999", "9999", "9999", "9999"]}, "fail"),
    ("process dead at the end fails", {"variant": "missing", "log": [("activate", NOT_USABLE)], "glide_commit": "hello", "alive": False}, "fail"),
]
for name, sc, want in cases:
    st, ev = run(sc)
    check(st == want, "%s -> %s (want %s) %s" % (name, st, want, ev.get("problems")))
    if name.startswith("missing: disable logged during ACTIVATE"):
        check(ev["disabled_evidence"] and ev["disabled_evidence"][0]["op"] == "activate", "disable evidence kept and tied to the op that caused it (%s)" % ev["disabled_evidence"][:1])
        check([o["op"] for o in ev["ops"]] == ["install", "activate", "open_target", "keymap", "open_target_2", "glide_hello", "clear_field", "type_ok"], "every operation recorded with its resulting text and crash state")
        check(ev["ops"][5]["field_after"] == "hello" and ev["ops"][5]["findings"] == [], "glide op carries its resulting text and findings")

# Two separately recorded results, for every model variant: a recognition miss must not touch the
# graceful-disable result, a graceful failure must not touch recognition, and either one blocks the leg.
DISABLE = {"corrupt": ("glide_hello", FAILED), "truncated": ("keymap", NOT_USABLE), "missing": ("activate", NOT_USABLE)}
MANIFEST = R.load_manifest()
for v, logline in sorted(DISABLE.items()):
    leg = "model_" + v
    for out, label in (("", "empty"), ("hallo", "non-hello")):
        st, ev = run({"variant": v, "log": [logline], "glide_commit": out})
        g, r = ev["graceful_disable"], ev["fallback_recognition"]
        check(g["status"] == "pass" and all(g["checks"].values()) and g["problems"] == [],
              "%s %s glide: graceful_disable stays pass with all four checks true (%s)" % (v, label, g["checks"]))
        check(r["status"] == "target_missed" and r["category"] == "fallback_recognition_target_missed"
              and r["glide_output"] == out and r["target"] == "hello" and "not automatically an app regression" in r["finding"],
              "%s %s glide: recognition target_missed, output %r recorded, labelled a qualification finding" % (v, label, out))
        check(ev["categories"] == ["fallback_recognition_target_missed"] and st == "fail",
              "%s %s glide: leg blocked (status %s) only by fallback_recognition_target_missed %s" % (v, label, st, ev["categories"]))
        gp = R.gate(MANIFEST, {"legs": {leg: {"status": st, "evidence": ev}}}, "candidate")
        check(("candidate/%s: fail [fallback_recognition_target_missed]" % leg) in gp,
              "%s %s glide: gate blocks overall qualification and names the category" % (v, label))
    for why, sc in (("no disable evidence", {"log": []}), ("crash finding", {"log": [logline], "crash": True}),
                    ("keyboard pid changed", {"log": [logline], "pids": ["4321"] * 4 + ["9999"] * 8})):
        sc.update({"variant": v, "glide_commit": "hello"})
        st, ev = run(sc)
        g, r = ev["graceful_disable"], ev["fallback_recognition"]
        check(g["status"] == "fail" and r["status"] == "hit" and r["category"] is None and r["glide_output"] == "hello",
              "%s %s: graceful_disable fail while recognition stays hit (%s)" % (v, why, g["problems"]))
        check(st == "fail" and ev["categories"] == ["graceful_disable_failed"],
              "%s %s: leg fails as graceful_disable_failed only %s" % (v, why, ev["categories"]))
    st, ev = run({"variant": v, "log": [logline], "glide_commit": "hello"})
    check(st == "pass" and ev["graceful_disable"]["status"] == "pass" and ev["fallback_recognition"]["status"] == "hit"
          and ev["categories"] == []
          and not [x for x in R.gate(MANIFEST, {"legs": {leg: {"status": st, "evidence": ev}}}, "candidate") if x.startswith("candidate/%s:" % leg)],
          "%s: both results pass -> leg pass, gate raises nothing for %s" % (v, leg))

try:
    run({"variant": "missing", "glide_commit": "hello"}, apk="/nonexistent.apk")
    check(False, "variant APK not built -> Inconclusive")
except RL.Inconclusive:
    check(True, "variant APK not built -> Inconclusive")
A = FakeA({"variant": "corrupt"}); RL.A = A
d = FakeDevice(A, {"variant": "corrupt", "glide_commit": "hello"}); d.hooks = False
st, ev = RL.leg_model("corrupt")(d, {"variants": {"corrupt": "/etc/hostname"}})
check(st == "inconclusive", "no WBDBG hooks stays inconclusive (%s)" % ev.get("reason"))
# Fresh Q1 regression, authored from reviewed a18c6baa (not an unreadable packet).
# Drive the REAL Device.open_target/focus/wait_ui against scripted run-2 XML.
# Hidden WBDBG geometry is deliberately available if keymap is ever reached.
import testkit as K
old_A, old_sleep = RL.A, RL.time.sleep
RL.time.sleep = lambda seconds: None
try:
    class ModelScriptA(K.ScriptA, FakeA):
        def __init__(self, sc, ime):
            K.ScriptA.__init__(self, ime=ime)
            FakeA.__init__(self, sc)

    class ReadinessDevice(FakeDevice, RL.Device):
        open_target = RL.Device.open_target
        def __init__(self, A, sc, hide_at_second):
            FakeDevice.__init__(self, A, sc)
            self.ime = RL.WPKG + "/.LatinIME"
            self.hide_at_second, self.calls = hide_at_second, []
        def keymap(self, tag):
            self.calls.append("keymap")
            if self.hide_at_second:
                self.A.ime = None
            # Coordinates may exist even for an instantiated, hidden view.
            return {"h": (1, 2000)}
        def glide(self, word):
            self.calls.append("glide")
            return FakeDevice.glide(self, word)
        def type_keys(self, text):
            self.calls.append("type_keys")
            return FakeDevice.type_keys(self, text)

    for variant in ("missing", "truncated", "corrupt"):
        for hide_at_second in (False, True):
            sc = {"variant": variant, "glide_commit": "hello"}
            A = ModelScriptA(sc, RL.WPKG if hide_at_second else None)
            RL.A = A
            d = ReadinessDevice(A, sc, hide_at_second)
            result, reason = None, None
            try:
                result = RL.leg_model(variant)(d, {"variants": {variant: "/etc/hostname"}})
            except RL.Inconclusive as exc:
                reason = str(exc)
            label = "%s model open_target%s" % (variant, "_2" if hide_at_second else "")
            check(reason is not None and "readiness[keyboard_not_visible]" in reason,
                  label + ": absent IME is readiness-inconclusive")
            check(result is None,
                  label + ": no model FAIL/result or recognition/graceful-disable categories")
            check(d.calls == (["keymap"] if hide_at_second else []),
                  label + ": hidden WBDBG coordinates cannot bypass visibility; no later geometry/glide/key taps")
            taps = [c for c in A.cmds if c.startswith("input tap ")]
            check(taps and all(int(c.split()[-1]) < 1290 for c in taps),
                  label + ": only bounded field-focus taps, no keyboard-coordinate taps")
    A = FakeA({"variant": "missing"}); RL.A = A
    d = FakeDevice(A, {"variant": "missing", "log": [("activate", NOT_USABLE)], "glide_commit": "hello"})
    RL.leg_model("missing")(d, {"variants": {"missing": "/etc/hostname"}})
    check(d.open_modes == ["require", "require"], "both model calls explicitly require visible IME")
finally:
    RL.A, RL.time.sleep = old_A, old_sleep

print("RESULT", "FAIL" if fails else "PASS", len(fails), "failures")
sys.exit(1 if fails else 0)
