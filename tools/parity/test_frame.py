#!/usr/bin/env python3
"""Setup-frame regressions (run 2: display readback passed on wm size/density/nav settings and pixel size,
but the setup PNG was a black frame with one white gesture pill; later dumps said 3-button). The real PNG
(testdata/run2_setup_readback.png) with the real dumps must FAIL; synthetic frames drawn on the real
dumps' geometry pass only when content and nav controls are really shown. Scripted adb only."""
import json, os, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import xml.etree.ElementTree as ET
import run_legs as RL
import visual as V
import testkit as K

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

RL.time.sleep = lambda s: None
SIZE = (1080, 2316)


def st(x, pkg=RL.WPKG):
    return V.ui_state(ET.fromstring(x[x.find("<"):]), pkg)


def cats(png, before, after, nav="3button", size=SIZE):
    p, f = V.frame_check(png, before, after, nav, size)
    return sorted(x["category"] for x in p), f


def main():
    tmp = tempfile.mkdtemp(prefix="frame-")
    s1, s0 = st(K.real("blockers_1")), st(K.real("blockers_0"))
    # 1. the real run-2 frame fails, and a generic non-uniformity test would not have caught it
    w, h, lum = V.decode_png(K.RUN2_PNG)
    check((w, h) == SIZE, "run2 PNG decodes at 1080x2316 (so the old pixel-size check passed it)")
    check(len(set(lum)) > 2 and max(lum) - min(lum) > 200, "run2 PNG is NOT uniform (status icons + pill): a non-uniformity test would pass it")
    c, f = cats(K.RUN2_PNG, s1, s1)
    check({"black_frame", "content_contradicts_xml", "gesture_pill_only"} <= set(c),
          "run2 PNG + run2 dump that says 3-button and test field focused: FAIL %s" % c)
    check(f["near_black_fraction"] > 0.99 and f["field_near_white_fraction"] == 0.0 and not f["nav_pixels"]["back"]["visible"]
          and not f["nav_pixels"]["recents"]["visible"], "facts: %.1f%% near-black, field 0%% white, back/overview rectangles empty" % (100 * f["near_black_fraction"]))
    c, _ = cats(K.RUN2_PNG, s0, s0)
    check({"blocker", "xml_no_target", "black_frame"} <= set(c), "run2 PNG + run2 ANR dump: FAIL %s" % c)
    c, _ = cats(K.RUN2_PNG, s1, st(K.real("keys_release")))
    check("xml_unsettled" in c and "blocker" in c, "run2 PNG between a clean dump and the keymap-time ANR dump: unsettled + blocker")
    # 2. synthetic frames on the real geometry
    good = K.synth_png(os.path.join(tmp, "good.png"))
    check(cats(good, s1, s1)[0] == [], "a frame showing the white field and three visible nav icons, matching both dumps: passes")
    pill = K.synth_png(os.path.join(tmp, "pill.png"), icons=False, pill=True)
    check(cats(pill, s1, s1)[0] == ["gesture_pill_only"], "white field but only a centred pill in a 3-button bar: gesture_pill_only")
    bare = K.synth_png(os.path.join(tmp, "bare.png"), icons=False)
    check(cats(bare, s1, s1)[0] == ["nav_buttons_not_visible"], "white field, empty nav bar: nav_buttons_not_visible")
    black = K.synth_png(os.path.join(tmp, "black.png"), content=0)
    c, _ = cats(black, s1, s1)
    check("content_contradicts_xml" in c and "black_frame" in c, "black content with nav icons: black_frame + content_contradicts_xml %s" % c)
    grey = K.synth_png(os.path.join(tmp, "grey.png"), content=120)
    check(cats(grey, s1, s1)[0] == ["content_contradicts_xml"], "a non-black but non-white content area where the dump puts the white field: content_contradicts_xml")
    ges = st(K.as_gesture(K.real("blockers_1")))
    check("nav_buttons_absent_in_xml" in cats(good, ges, ges)[0],
          "3button requested, dumps show the gesture handle: nav_buttons_absent_in_xml")
    gp = K.synth_png(os.path.join(tmp, "g.png"), nav="gesture")
    check(cats(gp, ges, ges, "gesture")[0] == [], "gesture requested: handle in the dumps and visible in the pixels: passes")
    check(cats(gp, s1, s1, "gesture")[0] == ["nav_handle_absent_in_xml"], "gesture requested, dumps show 3 buttons: nav_handle_absent_in_xml")
    check(cats(bare, ges, ges, "gesture")[0] == ["nav_handle_not_visible"], "gesture requested, handle rectangle empty: nav_handle_not_visible")
    check(cats(good, s1, s1, size=(1080, 2400))[0] == ["size"], "a frame of another size: size")
    open(os.path.join(tmp, "bad.png"), "wb").write(b"<html>")
    check(cats(os.path.join(tmp, "bad.png"), s1, s1)[0] == ["unreadable"], "an unreadable screenshot: unreadable")
    noime = st(K.with_ime(K.real("blockers_1"), RL.WPKG))
    check(cats(good, noime, s1)[0] == ["xml_unsettled"], "the IME window appearing between the two dumps: xml_unsettled")
    # 3. Device.setup end to end: ready, then dump / screenshot / dump, then the gate
    os.environ["PARITY_TARGET_APK"] = os.path.join(HERE, "legs.json")
    def setup(**kw):
        RL.A = K.ScriptA(**kw); d = RL.Device(RL.WPKG)
        try:
            d.setup(SIZE, 450, kw.get("nav", "3button")); r = "ok"
        except RL.Inconclusive as e:
            r = "inconclusive: " + str(e)[:200]
        return r, json.loads(RL.A.saved.get("sys/display_readback.json", "{}")), RL.A
    r, rb, A = setup()
    check(r == "ok" and rb["mismatch"] == {} and rb["screenshot_px"] == "1080x2316" and rb["setup_frame"]["problems"] == [],
          "Device.setup on a ready device with a true frame: passes, readback and frame saved")
    order = [c for c in A.cmds if c.startswith(("DUMP setup_readback", "SHOT", "DUMP ready_setup_frame", "DUMP ready_launcher"))]
    i_ready = max(i for i, c in enumerate(order) if c.startswith("DUMP ready_"))
    check(order[i_ready + 1:i_ready + 4] == ["DUMP setup_readback_before", "SHOT setup_readback", "DUMP setup_readback_after"] and order.count("SHOT setup_readback") == 1,
          "the one setup screenshot is taken after readiness, between its two dumps")
    r, rb, A = setup(frame="run2")
    check(r.startswith("inconclusive") and set(rb["mismatch"]["setup_frame"]["observed"]) >= {"black_frame", "content_contradicts_xml", "gesture_pill_only"}
          and "wm_size_physical" not in rb["mismatch"], "Device.setup when the screenshot is run 2's real PNG: inconclusive (setup failed), categories recorded")
    r, rb, A = setup(frame="pill")
    check(r.startswith("inconclusive") and rb["mismatch"]["setup_frame"]["observed"] == ["gesture_pill_only"], "Device.setup with a pill-only bar: inconclusive")
    r, rb, A = setup(changing=1)
    check(r == "ok" and rb["setup_frame"]["attempts"] == 2, "a screen that changes during the first capture is captured again (bounded) and then passes")
    r, rb, A = setup(changing=5)
    check(r.startswith("inconclusive") and "xml_unsettled" in rb["mismatch"]["setup_frame"]["observed"] and rb["setup_frame"]["attempts"] == 3,
          "a screen that never settles: inconclusive after 3 captures")
    r, rb, A = setup(size="1080x1920")
    check(r.startswith("inconclusive") and "wm_size_physical" in rb["mismatch"], "the run-1 1080x1920 physical size is still a mismatch")
    print("test_frame: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
