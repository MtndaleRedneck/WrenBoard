#!/usr/bin/env python3
"""Display setup regressions (first run: inputs 1080x2316 / 450 dpi / 3button, UI dumps showed 1080x1920).
1. avd_config.sh writes hw.lcd.width/height/density from the validated env into the AVD before boot and
   refuses bad values. 2. display_readback() gates on wm size, wm density, the navigation mode, the enabled
   nav overlay and a real screenshot's pixel size, and records requested and observed values. 3. A
   mismatch during Device.setup makes setup fail (so every device leg of the role is inconclusive) with
   the readback saved. 4. Every emulator step in parity.yml runs avd_config.sh. Fake adb only."""
import json, os, struct, subprocess, sys, tempfile, zlib
import yaml
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import run_legs as RL

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)


def png(path, w, h):
    raw = b"".join(b"\x00" + b"\x00" * (w * 3) for _ in range(2))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class FakeA:
    TPKG = "com.wrenboard.imetarget"
    def __init__(self, tmp, size="1080x2316", dens="450", over=None, dover=None, nav="0",
                 overlay="threebutton", shot=(1080, 2316)):
        self.tmp, self.size, self.dens, self.over, self.dover, self.nav, self.overlay, self.shotpx = tmp, size, dens, over, dover, nav, overlay, shot
        self.cmds, self.saved = [], {}
    def sh(self, c, timeout=60, check=True):
        self.cmds.append(c)
        if c == "wm size":
            return "Physical size: %s\n" % self.size + ("Override size: %s\n" % self.over if self.over else "")
        if c == "wm density":
            return "Physical density: %s\n" % self.dens + ("Override density: %s\n" % self.dover if self.dover else "")
        if c == "settings get secure navigation_mode":
            return self.nav + "\n"
        if c == "cmd overlay list android":
            out = ""
            for o in ("threebutton", "gestural"):
                out += "%s com.android.internal.systemui.navbar.%s\n" % ("[x]" if o == self.overlay else "[ ]", o)
            return out
        if c.startswith("getprop"):
            return "x86_64\n"
        return ""
    def run(self, cmd, timeout=60, check=True):
        self.cmds.append(" ".join(cmd)); return "Success\n"
    def shot(self, name):
        p = os.path.join(self.tmp, name + ".png"); png(p, *self.shotpx); return p
    def save(self, rel, text): self.saved[rel] = text
    def log(self, *a): pass


def main():
    tmp = tempfile.mkdtemp(prefix="disp-")
    # 1. avd_config.sh
    avdh = os.path.join(tmp, "avd"); os.makedirs(os.path.join(avdh, "test.avd"))
    cfg = os.path.join(avdh, "test.avd", "config.ini")
    def avd(env, name="test", body="hw.cpu.ncore=2\nhw.lcd.width=320\nhw.lcd.height=640\nhw.lcd.density=160\nhw.mainKeys=yes\n"):
        open(cfg, "w").write(body)
        e = {"PATH": os.environ["PATH"], "ANDROID_AVD_HOME": avdh}; e.update(env)
        return subprocess.run(["bash", os.path.join(HERE, "avd_config.sh"), name], env=e, capture_output=True, text=True, timeout=20)
    good = {"WM_SIZE": "1080x2316", "WM_DENSITY": "450", "NAV": "3button"}
    r = avd(good)
    lines = open(cfg).read().splitlines()
    check(r.returncode == 0 and "hw.lcd.width=1080" in lines and "hw.lcd.height=2316" in lines and "hw.lcd.density=450" in lines
          and "hw.mainKeys=no" in lines and "hw.cpu.ncore=2" in lines and sum(l.startswith("hw.lcd.width") for l in lines) == 1,
          "avd_config.sh writes hw.lcd.width=1080, height=2316, density=450 (old values replaced, other keys kept)")
    for env, why in ((dict(good, WM_SIZE="1080x2316;id"), "WM_SIZE with shell text"), (dict(good, WM_SIZE="1080"), "WM_SIZE without height"),
                     (dict(good, WM_DENSITY="45"), "two-digit density"), (dict(good, WM_DENSITY="450 "), "density with a space"),
                     (dict(good, NAV="threebutton"), "unknown nav"), ({"WM_DENSITY": "450", "NAV": "3button"}, "WM_SIZE unset")):
        before = open(cfg).read() if os.path.exists(cfg) else ""
        r = avd(env)
        check(r.returncode != 0 and "hw.lcd.width=320" in open(cfg).read(), "avd_config.sh refuses %s and leaves the AVD unchanged" % why)
    r = avd(good, name="../x")
    check(r.returncode != 0, "avd_config.sh refuses an AVD name with a path")
    os.remove(cfg)
    e = {"PATH": os.environ["PATH"], "ANDROID_AVD_HOME": avdh}; e.update(good)
    r = subprocess.run(["bash", os.path.join(HERE, "avd_config.sh"), "test"], env=e, capture_output=True, text=True, timeout=20)
    check(r.returncode != 0, "avd_config.sh fails when the AVD config does not exist")
    # 2. display_readback
    def rb(**kw):
        RL.A = FakeA(tmp, **kw); RL.time.sleep = lambda s: None
        return RL.display_readback((1080, 2316), 450, kw.pop("_nav", "3button") if "_nav" in kw else "3button")
    x = rb()
    check(x["mismatch"] == {} and x["screenshot_px"] == "1080x2316" and x["navigation_mode"] == "0", "readback passes when every value is the requested one")
    x = rb(size="1080x1920", shot=(1080, 1920))
    check(set(x["mismatch"]) == {"wm_size_physical", "screenshot_px"} and x["mismatch"]["wm_size_physical"] == {"requested": "1080x2316", "observed": "1080x1920"},
          "readback flags the first run's observed 1080x1920 (physical size and screenshot), with both values recorded")
    x = rb(over="1080x1920")
    check(set(x["mismatch"]) == {"wm_size_override"}, "readback flags a wm size override that differs from the request")
    x = rb(over="1080x2316", size="1080x1920")
    check("wm_size_physical" in x["mismatch"], "a matching override does not hide a wrong physical size")
    x = rb(dens="420")
    check(set(x["mismatch"]) == {"wm_density_physical"}, "readback flags a density mismatch")
    x = rb(dover="480")
    check(set(x["mismatch"]) == {"wm_density_override"}, "readback flags a density override mismatch")
    x = rb(nav="2", overlay="gestural")
    check(set(x["mismatch"]) == {"navigation_mode", "nav_overlay"}, "readback flags gesture nav when 3button was requested")
    x = rb(overlay="none")
    check(set(x["mismatch"]) == {"nav_overlay"}, "readback flags a 3-button overlay that is not enabled")
    x = rb(shot=(1080, 2400))
    check(set(x["mismatch"]) == {"screenshot_px"}, "readback flags a screenshot whose pixels differ from the request")
    RL.A = FakeA(tmp, nav="2", overlay="gestural")
    x = RL.display_readback((1080, 2316), 450, "gesture")
    check(x["mismatch"] == {}, "gesture request passes with navigation_mode 2 and the gestural overlay")
    check("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural" not in RL.A.cmds, "(readback alone changes nothing)")
    # 3. Device.setup gates on the readback
    tgt = os.path.join(tmp, "t.apk"); open(tgt, "w").write("x"); os.environ["PARITY_TARGET_APK"] = tgt
    # (v3.3: Device.setup now also waits for readiness and gates on the settled setup frame, so the device
    # here is testkit.ScriptA: run 2's real dumps and a true synthetic frame; test_frame.py covers the frame.)
    import testkit as K
    for kw, want_ok, why in (({}, True, "matching device"), ({"size": "1080x1920"}, False, "the first run's 1080x1920")):
        RL.A = K.ScriptA(**kw)
        d = RL.Device(RL.WPKG)
        try:
            d.setup((1080, 2316), 450, "3button"); ok = True
        except RL.Inconclusive as e:
            ok = False; msg = str(e)
        saved = json.loads(RL.A.saved.get("sys/display_readback.json", "{}"))
        check(ok == want_ok and saved.get("requested", {}).get("size") == "1080x2316", "Device.setup with %s: %s, readback saved" % (why, "passes" if want_ok else "inconclusive"))
        check("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton" in RL.A.cmds
              and not any(c.startswith("wm size ") or c.startswith("wm density ") for c in RL.A.cmds),
              "Device.setup (%s) enables the 3-button overlay and applies no wm override" % why)
    RL.A = K.ScriptA(nav="gesture", navmode="2", overlay="gestural"); d = RL.Device(RL.WPKG); d.setup((1080, 2316), 450, "gesture")
    check("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural" in RL.A.cmds, "Device.setup enables the gestural overlay for nav=gesture")
    # 3b. the reference must run native: primaryCpuAbi read back after install
    for out, want in (("    primaryCpuAbi=x86_64\n    secondaryCpuAbi=null\n", "x86_64"), ("    primaryCpuAbi=arm64-v8a\n", "arm64-v8a"),
                      ("    primaryCpuAbi=null\n", None), ("", None)):
        class AbiA(FakeA):
            def sh(self, c, timeout=60, check=True):
                return out if c.startswith("dumpsys package") else FakeA.sh(self, c, timeout, check)
        RL.A = AbiA(tmp)
        check(RL.installed_abi(RL.SWPKG) == want, "installed_abi() reads primaryCpuAbi %r" % want)
    import inspect
    src = inspect.getsource(RL.main)
    check('if abi != "x86_64"' in src and src.index("installed_abi(SWPKG)") < src.index("d.activate()\n                res.note(\"swiftkey"),
          "reference setup is inconclusive unless SwiftKey's primaryCpuAbi is x86_64 (checked before activation)")
    # 4. workflow wiring
    wf = yaml.safe_load(open(os.path.join(ROOT, ".github", "workflows", "parity.yml")))
    n = 0
    for jn, job in wf["jobs"].items():
        for st in job.get("steps", []):
            if "android-emulator-runner" in str(st.get("uses")):
                n += 1
                w = st["with"]
                check(w.get("pre-emulator-launch-script") == "bash harness/tools/parity/avd_config.sh test" and w.get("avd-name") == "test"
                      and all(k in job.get("env", {}) for k in ("WM_SIZE", "WM_DENSITY", "NAV")),
                      "%s: the emulator step writes the validated display into AVD 'test' before boot" % jn)
    check(n == 3, "all 3 emulator steps checked (%d)" % n)
    print("test_display: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
