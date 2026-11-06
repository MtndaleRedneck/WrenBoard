#!/usr/bin/env python3
"""Readiness regressions (run 2, release role: the UI dump at keymap time showed the focused system dialog
"Pixel Launcher isn't responding"; no keyboard was ever on screen, all 28 keys stayed unverified, no tap
reached the keyboard). Real dumps: testdata/run2_blockers_0.xml (dialog), run2_blockers_1.xml (test field
focused, no IME window), run2_keys_release.xml (the dialog again at keymap time). Scripted adb only."""
import inspect, json, os, re, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import xml.etree.ElementTree as ET
import run_legs as RL
import results as R
import visual as V
import testkit as K

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

RL.time.sleep = lambda s: None
TITLE = "Pixel Launcher isn't responding"


def st(name, pkg=RL.WPKG):
    x = K.real(name); return V.ui_state(ET.fromstring(x[x.find("<"):]), pkg)


def dev(**kw):
    RL.A = K.ScriptA(**kw); return RL.Device(RL.WPKG), RL.A


def outcome(fn):
    try:
        return "ok", fn()
    except RL.Inconclusive as e:
        return "inconclusive", str(e)


def key_taps(A):
    """Taps that are neither the dialog's Wait button nor on the test field above the keyboard."""
    out = []
    for c in A.cmds:
        m = re.match(r"input tap (\d+) (\d+)$", c)
        if m and not (1193 <= int(m.group(2)) < 1328) and not (68 <= int(m.group(2)) < 1290):
            out.append(c)
    return out


def main():
    # 1. the real run-2 dumps
    s0, s1, sk = st("blockers_0"), st("blockers_1"), st("keys_release")
    check(s0["blockers"] == [TITLE] and s0["blocker_wait"] == (540, 1260) and not s0["field_focused"],
          "run2 blockers_0.xml: the launcher ANR dialog is a blocker, its Wait button at (540, 1260), field not focused")
    check(not s1["blockers"] and s1["field_focused"] and s1["focused_window"]["pkg"] == K.ScriptA.TPKG and s1["ime_bounds"] is None,
          "run2 blockers_1.xml: test field focused, no dialog, and NO keyboard window (so taps must not start)")
    check(sk["blockers"] == [TITLE] and sk["focused_window"]["type"] == "TYPE_SYSTEM",
          "run2 keys_release.xml: the dialog is back and focused at keymap time")
    check(s1["nav"]["back"] and s1["nav"]["home"] and s1["nav"]["recents"] and not s1["nav"]["handle"], "run2 dumps list the three 3-button controls")
    # 2. system readiness (launcher)
    d, A = dev()
    r, v = outcome(d.system_ready)
    check(r == "ok" and v["dismissed"] == [], "system_ready: launcher focused, no dialog -> ready")
    d, A = dev(anr={"phase": "launcher", "returns": 0})
    r, v = outcome(d.system_ready)
    check(r == "ok" and v["dismissed"] == [TITLE] and A.waits == 1, "system_ready: the ANR dialog once -> 'Wait' tapped, re-checked, then ready")
    d, A = dev(anr={"phase": "launcher", "returns": 2})
    r, v = outcome(d.system_ready)
    check(r == "ok" and A.waits == 3, "system_ready: the dialog returns twice -> dismissed 3 times, ready once it stays away")
    d, A = dev(anr={"phase": "launcher", "returns": 99})
    r, v = outcome(d.system_ready)
    check(r == "inconclusive" and "persistent_blocker" in v and TITLE in v and A.waits == RL.Device.MAX_DISMISS,
          "system_ready: a persistent ANR -> inconclusive after %d dismissals (%s)" % (RL.Device.MAX_DISMISS, v[:90]))
    d, A = dev(home_ok=False)
    check(outcome(d.system_ready)[0] == "inconclusive", "system_ready: no HOME activity resolves -> inconclusive")
    d, A = dev(boot=False)
    check(outcome(d.system_ready)[0] == "inconclusive", "system_ready: boot never completes -> inconclusive (bounded)")
    class StuckA(K.ScriptA):
        def keyevent(self, code, wait=0.6): self.cmds.append("input keyevent " + code)
    RL.A = StuckA(); d = RL.Device(RL.WPKG); RL.A.state = "target"; RL.A.field_focused = False
    r, v = outcome(d.system_ready)
    check(r == "inconclusive" and "not_ready" in v and sum(c.startswith("DUMP ready_launcher") for c in RL.A.cmds) <= 40,
          "system_ready: launcher never focused -> inconclusive within the bounded polls")
    # 3. field focus and keyboard visible before any key geometry or tap
    d, A = dev()
    A.state = "target"
    r, v = outcome(lambda: d.open_target())
    check(r == "ok" and d.keyboard_visible, "open_target: field focused and the keyboard window visible -> ready")
    d, A = dev(ime=None)
    r, v = outcome(lambda: d.open_target())
    check(r == "inconclusive" and "keyboard_not_visible" in v, "open_target: field focused but no keyboard window (run2 blockers_1) -> inconclusive")
    check(sum(c.startswith("input tap") for c in A.cmds) <= 1 + RL.Device.MAX_FOCUS_TAPS + 1, "open_target: at most %d focus taps, bounded" % RL.Device.MAX_FOCUS_TAPS)
    d, A = dev(ime="com.touchtype.swiftkey")
    check(outcome(lambda: d.open_target())[0] == "inconclusive", "open_target: another package's IME window is not this keyboard -> inconclusive")
    d, A = dev(anr={"phase": "field", "returns": 99})
    r, v = outcome(lambda: d.open_target())
    check(r == "inconclusive" and "persistent_blocker" in v, "open_target: the ANR dialog keeps returning (run2 keys_release) -> inconclusive")
    d, A = dev(anr={"phase": "field", "returns": 0})
    r, v = outcome(lambda: d.open_target())
    check(r == "ok" and A.waits == 1, "open_target: the ANR dialog once -> dismissed, re-checked, ready")
    d, A = dev(ime=None)
    r, v = outcome(lambda: d.open_target(keyboard="record"))
    check(r == "ok" and d.keyboard_visible is False, "open_target(keyboard='record') (model legs): no keyboard window is recorded, the leg's own checks decide")
    # 4. release_smoke never taps keys behind a blocker and never passes on provenance alone
    d, A = dev(anr={"phase": "field", "returns": 99})
    d.install = lambda apks: None; d.activate = lambda: None
    r, v = outcome(lambda: RL.leg_release_smoke(d, {"provenance_ok": True, "apk": "x.apk"}))
    check(r == "inconclusive" and key_taps(A) == [] and not any(c.startswith("DUMP keys_") for c in A.cmds),
          "release_smoke with a persistent ANR: inconclusive before any keymap dump or key tap")
    m = R.load_manifest()
    data = {"role": "release", "legs": {"release_provenance": {"status": "pass"}, "release_smoke": {"status": "inconclusive"}, "crash_anr_gate": {"status": "pass"}}}
    check(R.gate(m, data, "release") != [], "the release role does not pass with provenance only (release_smoke inconclusive)")
    # 5. the role's setup: a persistent dialog in setup is a setup failure
    os.environ["PARITY_TARGET_APK"] = os.path.join(HERE, "legs.json")
    d, A = dev(anr={"phase": "launcher", "returns": 99})
    r, v = outcome(lambda: d.setup((1080, 2316), 450, "3button"))
    rb = json.loads(A.saved.get("sys/display_readback.json", "{}"))
    check(r == "inconclusive" and rb.get("mismatch", {}).get("setup_frame", {}).get("observed") == ["not_ready"],
          "Device.setup with a persistent launcher ANR: setup inconclusive, readback saved with setup_frame not_ready")
    # 6. no retry chain, no other image or target
    src = open(os.path.join(HERE, "run_legs.py")).read()
    check(not re.search(r"adb\W+reboot|emu kill|-wipe-data|avdmanager|sdkmanager|kill-server", src), "run_legs.py has no reboot / emulator restart / image install")
    import yaml
    wf = yaml.safe_load(open(os.path.join(ROOT, ".github", "workflows", "parity.yml")))
    emus = [(jn, s["with"]) for jn, j in wf["jobs"].items() for s in j.get("steps", []) if "android-emulator-runner" in str(s.get("uses"))]
    check(len(emus) == 3 and all(w["api-level"] == 34 and w["target"] == "google_apis" and w["arch"] == "x86_64" for _, w in emus),
          "parity.yml: still one emulator step per device role, api 34 google_apis x86_64")
    print("test_readiness: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
