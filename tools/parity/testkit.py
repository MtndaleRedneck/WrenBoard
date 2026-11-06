#!/usr/bin/env python3
"""Test kit for the readiness and setup-frame regressions: a scripted stand-in for the adb module
(no adb, no emulator) whose screen is built from run 2's real UI dumps (testdata/run2_*.xml) and either
run 2's real setup PNG or a synthetic frame drawn here. Used by test_readiness.py, test_frame.py and
test_display.py."""
import os, re, struct, tempfile, zlib
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
TD = os.path.join(HERE, "testdata")
RUN2_PNG = os.path.join(TD, "run2_setup_readback.png")
LAUNCHER = "com.google.android.apps.nexuslauncher"
W, H = 1080, 2316


def real(name):
    return open(os.path.join(TD, "run2_%s.xml" % name)).read()


def with_ime(xml, pkg, top=1290):
    win = ('<window index="3" title="InputMethod" bounds="[0,%d][1080,2181]" active="false" focused="false" '
           'accessibility-focused="false" id="40" layer="3" type="TYPE_INPUT_METHOD"><hierarchy rotation="0">'
           '<node index="0" text="" resource-id="%s:id/keyboard_view" class="android.view.View" package="%s" '
           'content-desc="" focused="false" bounds="[0,%d][1080,2181]" /></hierarchy></window>' % (top, pkg, pkg, top))
    return xml.replace("</display>", win + "</display>")


def as_launcher(xml):
    x = xml.replace('title="IME target"', 'title=""').replace("com.wrenboard.imetarget", LAUNCHER)
    return x.replace('focused="true" scrollable', 'focused="false" scrollable')


def as_gesture(xml):
    """The 3-button nav window's buttons replaced by the gesture navigation handle."""
    x = re.sub(r'resource-id="com.android.systemui:id/(back|recent_apps)"', 'resource-id="com.android.systemui:id/gone_\\1"', xml)
    x = x.replace('resource-id="com.android.systemui:id/home_button"', 'resource-id="com.android.systemui:id/home_handle"')
    x = re.sub(r'(resource-id="com.android.systemui:id/gone_(?:back|recent_apps)"[^>]*bounds=")\[[^"]*', r'\1[0,0][0,0]', x)
    return x.replace('resource-id="com.android.systemui:id/home_handle" class="android.widget.ImageView" package="com.android.systemui" content-desc="Home"',
                     'resource-id="com.android.systemui:id/home_handle" class="android.view.View" package="com.android.systemui" content-desc=""')


def synth_png(path, w=W, h=H, nav="3button", content=255, icons=True, pill=False):
    """A frame drawn from the real dumps' geometry: grey status bar [0,0,1080,68], content rows of value
    `content`, black nav bar [0,2181,1080,2316] with three icons (3button) and/or a centred pill."""
    rows = []
    for y in range(h):
        if y < 68:
            row = bytearray([200]) * (w * 3)
        elif y < 2181 * h // H:
            row = bytearray([content]) * (w * 3)
        else:
            row = bytearray(w * 3)
            if nav == "3button" and icons and 2226 <= y < 2271:
                for x0, x1 in ((205, 250), (515, 565), (826, 872)):
                    row[x0 * 3:x1 * 3] = bytearray([230]) * ((x1 - x0) * 3)
            if (pill or nav == "gesture") and 2277 <= y < 2288:
                row[430 * 3:650 * 3] = bytearray([230]) * (220 * 3)
        rows.append(b"\x00" + bytes(row))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(b"".join(rows), 1)) + chunk(b"IEND", b""))
    return path


class ScriptA:
    """Stand-in adb. Screen states: launcher, target (field focused once tapped), anr (run 2's dialog).
    anr: {"phase": "launcher"|"field"|"frame", "returns": N} shows run 2's ANR dialog when that phase is
    reached and brings it back N times after each "Wait" (a large N = persistent). ime: the keyboard
    package whose IME window appears once the field is focused (None = never). frame: "good", "run2",
    "pill" or a callable(path) for the screenshot."""
    TPKG = "com.wrenboard.imetarget"

    def __init__(self, ime="com.wrenboard.keyboard", anr=None, frame="good", nav="3button", boot=True, home_ok=True,
                 size="1080x2316", dens="450", navmode="0", overlay="threebutton", changing=0):
        self.ime, self.anr, self.frame, self.nav, self.boot, self.home_ok = ime, dict(anr or {}), frame, nav, boot, home_ok
        self.size, self.dens, self.navmode, self.overlay, self.changing = size, dens, navmode, overlay, changing
        self.state, self.field_focused, self.anr_on, self.waits = "launcher", False, False, 0
        self.cmds, self.saved, self.events, self.tmp = [], {}, [], tempfile.mkdtemp(prefix="kit-")
        import adb as realA
        self.bounds, self.centre, self.labelled = realA.bounds, realA.centre, realA.labelled

    def _phase(self, ph):
        if self.anr.get("phase") == ph and not self.anr.get("done"):
            self.anr_on, self.anr["done"] = True, True

    def log(self, *a): self.events.append(("log",) + a)
    def save(self, rel, text): self.saved[rel] = text
    def keyevent(self, code, wait=0.6):
        self.cmds.append("input keyevent " + code)
        if code == "KEYCODE_HOME" and not self.anr_on:
            self.state = "launcher"; self._phase("launcher")
        elif code == "KEYCODE_BACK" and self.anr_on:
            self.anr_on = False
    def tap(self, x, y, wait=0.5): self.sh("input tap %d %d" % (int(x), int(y)))
    def clear_field(self): self.cmds.append("CLEAR")
    def field(self, since=0.0): return ""
    def run(self, cmd, timeout=60, check=True):
        self.cmds.append(" ".join(cmd)); return "Success\n"

    def sh(self, c, timeout=60, check=True):
        self.cmds.append(c)
        if c == "getprop sys.boot_completed":
            return "1\n" if self.boot else "\n"
        if c.startswith("cmd package resolve-activity"):
            return "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n%s/.NexusLauncherActivity\n" % LAUNCHER if self.home_ok else "No activity found\n"
        if c == "dumpsys window":
            if self.anr_on:
                f = "Application Not Responding: %s" % LAUNCHER
            else:
                f = (LAUNCHER + "/.NexusLauncherActivity") if self.state == "launcher" else (self.TPKG + "/" + self.TPKG + ".TestInput")
            return "  mCurrentFocus=Window{1a2b u0 %s}\n" % f
        if c.startswith("am start"):
            self.state, self.field_focused = "target", True; self._phase("frame")
            return "Status: ok\n"
        m = re.match(r"input tap (\d+) (\d+)$", c)
        if m:
            x, y = int(m.group(1)), int(m.group(2))
            if self.anr_on:
                if 72 <= x < 1008 and 1193 <= y < 1328:  # run 2's "Wait" button
                    self.waits += 1
                    self.anr_on = self.anr.get("returns", 0) >= self.waits
            elif self.state == "target":
                self.field_focused = True; self._phase("field")
            return ""
        if c == "wm size":
            return "Physical size: %s\n" % self.size
        if c == "wm density":
            return "Physical density: %s\n" % self.dens
        if c == "settings get secure navigation_mode":
            return self.navmode + "\n"
        if c == "cmd overlay list android":
            return "".join("%s com.android.internal.systemui.navbar.%s\n" % ("[x]" if o == self.overlay else "[ ]", o) for o in ("threebutton", "gestural"))
        if c.startswith("getprop"):
            return "x86_64\n"
        return ""

    def xml(self):
        if self.anr_on:
            x = real("blockers_0")
        elif self.state == "launcher":
            x = as_launcher(real("blockers_1"))
        else:
            x = real("blockers_1")
            if not self.field_focused:
                x = x.replace('focused="true" scrollable', 'focused="false" scrollable')
            elif self.ime:
                x = with_ime(x, self.ime)
        return as_gesture(x) if self.nav == "gesture" else x

    def dump_windows(self, tag):
        self.cmds.append("DUMP " + tag)
        if self.changing and tag == "setup_readback_after":
            self.changing -= 1
            x = real("blockers_0")
        else:
            x = self.xml()
        self.saved["ui/%s.xml" % tag] = x
        return ET.fromstring(x[x.find("<"):])

    def shot(self, name):
        self.cmds.append("SHOT " + name)
        p = os.path.join(self.tmp, name + ".png")
        if callable(self.frame):
            return self.frame(p)
        if self.frame == "run2":
            open(p, "wb").write(open(RUN2_PNG, "rb").read()); return p
        return synth_png(p, nav=self.nav, pill=(self.frame == "pill"), icons=(self.frame != "pill"))
