#!/usr/bin/env python3
"""Release keymap regressions, using the first run's real UI dump (testdata/run1_keys_release.xml, the
v1.5.0 release keyboard over the test-input app). In that run the accessibility scan matched 'i' to the
"I" suggestion chip. Now: keys come only from inside the keyboard_view (chips and toolbar excluded); a
build without hooks falls back to the bounded QWERTY geometry table; and every key counts only after
tap-to-text proves it. A fake device types whatever key its own ground-truth layout puts under each tap.
No adb, no emulator."""
import json, os, re, sys
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import adb as realA
import run_legs as RL

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

XML = open(os.path.join(HERE, "testdata", "run1_keys_release.xml")).read()
AREA = (0, 1012, 1080, 1920)


class FakeA:
    """Taps land on the key whose cell (from a ground-truth layout) contains the point. chip_hits maps
    rectangles to the text a tap there inserts (a suggestion chip commits a word)."""
    TPKG = "com.wrenboard.imetarget"
    bounds, centre = staticmethod(realA.bounds), staticmethod(realA.centre)
    def __init__(self, truth=None, xml=XML, dead=(), shift_first=False):
        self.truth, self.xml, self.dead, self.text, self.saved, self.taps = truth, xml, set(dead), "", {}, 0
        self.shift_first = shift_first
    def key_at(self, x, y):
        if not self.truth:
            return None
        l, t, r, b = AREA
        rows, q = RL.QWERTY_LAYOUTS[self.truth]
        rh, kw = (b - t) / rows, (r - l) / 10.0
        if not (l <= x < r and t <= y < b):
            return "CHIP" if 876 <= y < 1012 and 366 <= x < 706 else None
        row = int((y - t) // rh) - q
        if row == 0:
            j = int((x - l) // kw); return "qwertyuiop"[j] if 0 <= j < 10 else None
        if row == 1:
            j = int((x - l - 0.5 * kw) // kw); return "asdfghjkl"[j] if 0 <= j < 9 else None
        if row == 2:
            if x >= r - 1.5 * kw: return "BKSP"
            j = int((x - l - 1.5 * kw) // kw); return "zxcvbnm"[j] if 0 <= j < 7 else None
        if row == 3:
            return "SPACE" if l + 3 * kw <= x < r - 3 * kw else None
        return None
    def sh(self, c, timeout=60, check=True):
        for m in re.finditer(r"input tap (\d+) (\d+)", c):
            self.taps += 1
            k = self.key_at(int(m.group(1)), int(m.group(2)))
            if k in self.dead or k is None:
                continue
            if k == "CHIP": self.text += "I "
            elif k == "SPACE": self.text += " "
            elif k == "BKSP": self.text = self.text[:-1]
            else: self.text += k.upper() if (self.shift_first and not self.text) else k
        return ""
    def clear_field(self): self.text = ""
    def field(self, since=0.0): return self.text
    def dump_windows(self, tag): return ET.fromstring(self.xml[self.xml.find("<"):])
    def save(self, rel, text): self.saved[rel] = text
    def log(self, *a): pass
    def mark(self, label): return 1.0
    def logs(self, *a, **k): return ""   # no WBDBG lines: a build without hooks


def device(fa):
    RL.A = fa; RL.time.sleep = lambda s: None
    return RL.Device(RL.WPKG)


def main():
    root = ET.fromstring(XML[XML.find("<"):])
    RL.A = FakeA()
    # the run-1 defect, reproduced with the old rule and gone with the new one
    old = [d for d, n in realA.labelled(root, RL.WPKG) if d.lower() == "i"]
    check(len(old) == 1, "old rule: the run-1 dump yields exactly one 'i' match (the \"I\" suggestion chip)")
    check(RL.keyboard_area(root, RL.WPKG) == AREA, "keyboard_area() picks the innermost keyboard_view [0,1012][1080,1920], not the whole input view")
    new = [d for d, n in RL.key_nodes(root, RL.WPKG, RL.keyboard_area(root, RL.WPKG))]
    check("I" not in new and not any(d.lower() == "i" for d in new), "new rule: 'i' no longer matches the \"I\" chip (%r)" % new)
    chips = RL.key_nodes(root, RL.WPKG, (0, 0, 1080, 1920))
    check(not any(d in ("The", "I", "You", "Open Action Bar") for d, n in chips), "chips and the action bar are excluded even when the area would contain them")
    # geometry fallback, verified by tap-to-text
    for truth in RL.QWERTY_LAYOUTS:
        fa = FakeA(truth=truth); d = device(fa)
        km = d.keymap("release")
        ev = json.loads(fa.saved["geom/release_keys.json"])
        srcs = set(ev["verified_by"].values())
        check(sorted(km) == sorted(RL.REQUIRED_KEYS) and not ev["unverified"] and "geometry:" + truth in srcs and all(x.startswith("geometry:") for x in srcs)
              and all(fa.key_at(*km[k]) == k for k in RL.REQUIRED_KEYS),
              "no hooks, true layout %s: all 28 keys verified by tap-to-text, each at a point that really types it (%s)" % (truth, sorted(srcs)))
        if truth == "rows5_number_row":
            check(any(t["source"] == "geometry:rows4" for t in ev["tap_to_text"]), "the rows4 guess was tried first; only its positions that tap-verified were kept")
    fa = FakeA(truth="rows4", shift_first=True); d = device(fa)
    check(sorted(d.keymap("release")) == sorted(RL.REQUIRED_KEYS), "auto-capitalised first letter still verifies (case-insensitive)")
    for dead, why in ((["k"], "the k key types nothing"), (["SPACE"], "space types nothing"), (["BKSP"], "backspace does nothing")):
        fa = FakeA(truth="rows4", dead=dead); d = device(fa)
        try:
            d.keymap("release"); check(False, "keymap is inconclusive when %s" % why)
        except RL.Inconclusive as e:
            ev = json.loads(fa.saved["geom/release_keys.json"])
            check(set(dead) <= set(ev["unverified"]) and "unverified" in str(e), "keymap is inconclusive when %s (unverified %s)" % (why, ev["unverified"]))
    fa = FakeA(truth=None); d = device(fa)
    try:
        d.keymap("release"); check(False, "a keyboard that types nothing is inconclusive")
    except RL.Inconclusive:
        check(True, "a keyboard that types nothing is inconclusive")
    # an a11y node inside the area that is really a chip (no id) is caught by tap-to-text
    bad = XML.replace('content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,1012][1080,1920]"',
                      'content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,1012][1080,1920]"><node index="0" text="" resource-id="" class="android.view.View" package="com.wrenboard.keyboard" content-desc="x" bounds="[400,900][500,1000]" /><node index="1" text="" resource-id="" class="android.view.View" package="com.wrenboard.keyboard" content-desc="z" bounds="[500,1300][560,1400]" /></node><node index="9" bounds="[0,0][1,1]"', 1)
    check(bad != XML, "(fixture variant with two unlabelled-id nodes built)")
    fa = FakeA(truth="rows4", xml=bad); d = device(fa)
    km = d.keymap("release"); ev = json.loads(fa.saved["geom/release_keys.json"])
    check(ev["verified_by"].get("z") == "geometry:rows4" and km["z"] != (530, 1350),
          "a wrong a11y key position (z at a cell that types something else) fails tap-to-text and geometry supplies the verified one")
    # release_smoke still types and checks the field; a keymap that cannot be verified is never a pass
    import inspect
    src = inspect.getsource(RL.leg_release_smoke)
    check('d.type_keys("ok ")' in src and 'last_word(got) == "ok"' in src, "release_smoke still taps o, k, space and checks the field (not provenance only)")
    fa = FakeA(truth=None); d = device(fa)
    d.install = d.activate = d.open_target = lambda *a: None
    try:
        st = RL.leg_release_smoke(d, {"provenance_ok": True, "apk": "x"})[0]
    except RL.Inconclusive:
        st = "inconclusive"
    check(st == "inconclusive", "release_smoke with unverifiable keys is inconclusive, not pass (%s)" % st)
    print("test_keymap: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
