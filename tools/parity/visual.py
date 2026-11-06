#!/usr/bin/env python3
"""Setup-frame gate: decide from the pixels of the setup screenshot AND the UI dumps taken immediately
before and after it whether the requested screen is really there. Pure Python (zlib only): the runner's
python3 has no imaging library.

A frame passes only when all of these hold (each failure is a named category):
  size                  the PNG is the requested WIDTHxHEIGHT
  xml_unsettled         the dump before and the dump after the screenshot agree (focused window, field,
                        nav controls, dialogs); otherwise the frame is not known to show either
  blocker               no system dialog (ANR / crash) in either dump
  xml_no_target         the focused window is the test-input app with its field focused
  black_frame           at most 90% of the frame is near-black (run 2's frame: status icons and one pill on
                        black; a "not uniform" test passes that frame, this one does not)
  content_contradicts_xml  the field region the dumps report (white EditText, minus any IME window) is at
                        least 90% near-white in the pixels
  nav_*                 3button: back, home and overview are in the dumps AND each one's rectangle holds a
                        visible icon (>= 0.4% of its pixels, at least 30, contrasting with its background);
                        a lone centred pill with empty back/overview rectangles is gesture_pill_only.
                        gesture: the dumps show the navigation handle and no back/overview buttons, and the
                        handle's rectangle holds visible pixels.
"""
import struct, zlib

NAV = {"back": "com.android.systemui:id/back", "home": "com.android.systemui:id/home_button",
       "recents": "com.android.systemui:id/recent_apps", "handle": "com.android.systemui:id/home_handle"}
DARK, LIGHT, INK = 24, 235, 64


def decode_png(path):
    """(width, height, luminance bytearray row-major) for an 8-bit RGB/RGBA/grey PNG, non-interlaced."""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, ihdr = 8, [], None
    while pos + 8 <= len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if typ == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif typ == b"IDAT":
            idat.append(body)
        elif typ == b"IEND":
            break
        pos += 12 + n
    if not ihdr:
        raise ValueError("no IHDR")
    w, h, depth, ctype, _, _, inter = ihdr
    bpp = {0: 1, 2: 3, 4: 2, 6: 4}.get(ctype)
    if depth != 8 or bpp is None or inter != 0 or w <= 0 or h <= 0 or w * h > 40000000:
        raise ValueError("unsupported PNG (depth %d, colour type %d, interlace %d)" % (depth, ctype, inter))
    raw = zlib.decompress(b"".join(idat))
    stride = w * bpp
    if len(raw) != h * (stride + 1):
        raise ValueError("PNG data length does not match its header")
    lum = bytearray(w * h)
    prev = bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif f == 2:
            line = bytearray((a + b) & 255 for a, b in zip(line, prev))
        elif f == 3:
            for i in range(stride):
                line[i] = (line[i] + (((line[i - bpp] if i >= bpp else 0) + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        elif f != 0:
            raise ValueError("bad PNG filter %d" % f)
        if bpp >= 3:
            r, g, b = line[0::bpp], line[1::bpp], line[2::bpp]
            lum[y * w:(y + 1) * w] = bytes((299 * x + 587 * yv + 114 * z) // 1000 for x, yv, z in zip(r, g, b))
        else:
            lum[y * w:(y + 1) * w] = line[0::bpp]
        prev = line
    return w, h, lum


def _rect(lum, w, h, b):
    l, t, r, bt = max(0, b[0]), max(0, b[1]), min(w, b[2]), min(h, b[3])
    if r <= l or bt <= t:
        return []
    out = []
    for y in range(t, bt):
        out.extend(lum[y * w + l:y * w + r])
    return out


def ink(lum, w, h, b):
    """(contrasting pixels, area) inside rectangle b: pixels at least INK from the rectangle's median."""
    px = _rect(lum, w, h, b)
    if not px:
        return 0, 0
    med = sorted(px)[len(px) // 2]
    return sum(1 for v in px if abs(v - med) >= INK), len(px)


def visible(lum, w, h, b):
    n, area = ink(lum, w, h, b)
    return area > 0 and n >= max(30, area * 0.004), n, area


def bright_bbox(lum, w, h, b, thr=200):
    l, t, r, bt = max(0, b[0]), max(0, b[1]), min(w, b[2]), min(h, b[3])
    xs, ys = [], []
    for y in range(t, bt):
        row = lum[y * w + l:y * w + r]
        for i, v in enumerate(row):
            if v >= thr:
                xs.append(l + i); ys.append(y)
    return (min(xs), min(ys), max(xs) + 1, max(ys) + 1, len(xs)) if xs else None


def frame_check(png_path, before, after, nav, want_size):
    """Problems (list of {category, detail}) and facts for one contemporaneous capture. before/after are
    run_legs.ui_state() dicts of the dumps taken right before and right after the screenshot."""
    probs, facts = [], {}
    def bad(cat, detail):
        probs.append({"category": cat, "detail": detail})
    try:
        w, h, lum = decode_png(png_path)
    except (OSError, ValueError, zlib.error) as e:
        bad("unreadable", str(e)); return probs, facts
    facts["px"] = "%dx%d" % (w, h)
    if (w, h) != tuple(want_size):
        bad("size", "screenshot %dx%d, requested %dx%d" % (w, h, want_size[0], want_size[1]))
    keys = ("focused_window", "field_focused", "field_bounds", "nav", "blockers", "ime_bounds")
    if before is None or after is None or any(before.get(k) != after.get(k) for k in keys):
        bad("xml_unsettled", "the dumps before and after the screenshot differ: %s"
            % [k for k in keys if (before or {}).get(k) != (after or {}).get(k)])
    st = after or before or {}
    if before and before.get("blockers") or after and after.get("blockers"):
        bad("blocker", "system dialog on screen: %s" % ((before or {}).get("blockers") or (after or {}).get("blockers")))
    fw = st.get("focused_window") or {}
    if fw.get("pkg") != "com.wrenboard.imetarget" or not st.get("field_focused") or not st.get("field_bounds"):
        bad("xml_no_target", "focused window %s, field focused %s" % (fw, st.get("field_focused")))
    dark = sum(1 for v in lum if v < DARK) / float(len(lum))
    facts["near_black_fraction"] = round(dark, 4)
    if dark > 0.90:
        bad("black_frame", "%.1f%% of the frame is near-black" % (100 * dark))
    fb = st.get("field_bounds")
    if fb:
        top, bottom = fb[1], fb[3]
        ib = st.get("ime_bounds")
        if ib and ib[1] < bottom:
            bottom = max(top, ib[1])
        px = _rect(lum, w, h, (fb[0], top, fb[2], bottom))
        white = sum(1 for v in px if v >= LIGHT) / float(len(px)) if px else 0.0
        facts["field_near_white_fraction"] = round(white, 4)
        if white < 0.90:
            bad("content_contradicts_xml", "the dumps put the white test field at %s but only %.1f%% of those pixels are near-white"
                % (list((fb[0], top, fb[2], bottom)), 100 * white))
    navx = st.get("nav") or {}
    vis = {}
    for k, b in navx.items():
        if b:
            ok, n, area = visible(lum, w, h, b)
            vis[k] = {"bounds": list(b), "ink_px": n, "area": area, "visible": ok}
    facts["nav_pixels"] = vis
    if nav == "3button":
        absent = [k for k in ("back", "home", "recents") if not navx.get(k)]
        if absent:
            bad("nav_buttons_absent_in_xml", "the dumps show no %s button" % absent)
        unseen = [k for k in ("back", "home", "recents") if navx.get(k) and not vis[k]["visible"]]
        bar = st.get("nav_bar_bounds")
        pill = bright_bbox(lum, w, h, bar) if bar else None
        facts["nav_bar_bright_bbox"] = list(pill) if pill else None
        if unseen:
            lone = (pill and abs((pill[0] + pill[2]) / 2.0 - w / 2.0) <= 0.05 * w and 0.05 * w <= pill[2] - pill[0] <= 0.35 * w
                    and pill[3] - pill[1] <= 0.03 * h and not vis.get("back", {}).get("visible") and not vis.get("recents", {}).get("visible"))
            bad("gesture_pill_only" if lone else "nav_buttons_not_visible",
                "3button requested and in the dumps, but no visible icon in the %s rectangle(s)%s"
                % (unseen, "; the only bright mark in the bar is a centred pill %s" % list(pill[:4]) if lone else ""))
    else:
        if not navx.get("handle") or navx.get("back") or navx.get("recents"):
            bad("nav_handle_absent_in_xml", "gesture requested; dumps show %s" % sorted(k for k, b in navx.items() if b))
        elif not vis["handle"]["visible"]:
            bad("nav_handle_not_visible", "no visible pixels in the navigation handle rectangle %s" % list(navx["handle"]))
    return probs, facts


# ---- UI dump reading (uiautomator dump --windows) ----
import re as _re
TPKG = "com.wrenboard.imetarget"
BLOCKER_TITLE = _re.compile(r"isn.t responding|keeps stopping|has stopped|isn.t working|not responding", _re.I)
BLOCKER_IDS = ("android:id/aerr_wait", "android:id/aerr_close", "android:id/aerr_app_info", "android:id/aerr_restart",
               "android:id/aerr_report", "android:id/aerr_mute")


def _b(n):
    m = _re.findall(r"-?\d+", n.get("bounds", "") if n is not None else "")
    return tuple(int(v) for v in m) if len(m) == 4 else None


def _area(b):
    return (b[2] - b[0]) * (b[3] - b[1]) if b and b[2] > b[0] and b[3] > b[1] else 0


def ui_state(root, kbpkg=None):
    """What one windows dump says: system dialogs, the focused window and its package, whether the
    test-input field is the focused node (and its bounds), the visible IME window of kbpkg, and the
    navigation bar's controls. Pure function of the XML (no device access)."""
    st = {"dump": root is not None, "blockers": [], "blocker_wait": None, "focused_window": None,
          "field_focused": False, "field_bounds": None, "ime_bounds": None, "nav": {}, "nav_bar_bounds": None}
    if root is None:
        st["blockers"].append("no ui dump")
        return st
    for w in root.iter("window"):
        nodes = list(w.iter("node"))
        wb = _b(w)
        pk = [n.get("package") for n in nodes if n.get("package")]
        pkg = max(set(pk), key=pk.count) if pk else None
        title = w.get("title") or ""
        ids = set(n.get("resource-id") for n in nodes)
        alert = [n.get("text") or "" for n in nodes if n.get("resource-id") == "android:id/alertTitle"]
        if BLOCKER_TITLE.search(title) or ids.intersection(BLOCKER_IDS) or any(BLOCKER_TITLE.search(a) for a in alert):
            st["blockers"].append(title or (alert[0] if alert else "system dialog"))
            wn = [n for n in nodes if n.get("resource-id") == "android:id/aerr_wait" and _area(_b(n))]
            if wn and st["blocker_wait"] is None:
                b = _b(wn[0]); st["blocker_wait"] = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        if w.get("focused") == "true" and st["focused_window"] is None:
            st["focused_window"] = {"type": w.get("type"), "title": title, "pkg": pkg}
            for n in nodes:
                if (n.get("package") == TPKG and n.get("class") == "android.widget.EditText" and n.get("focused") == "true"
                        and _area(_b(n))):
                    st["field_focused"], st["field_bounds"] = True, _b(n)
                    break
        if w.get("type") == "TYPE_INPUT_METHOD" and _area(wb) and (kbpkg is None or kbpkg in pk):
            st["ime_bounds"] = wb
        if title == "Navigation bar" or "com.android.systemui:id/navigation_bar_frame" in ids:
            st["nav_bar_bounds"] = wb
            for k, rid in NAV.items():
                hit = [_b(n) for n in nodes if n.get("resource-id") == rid and _area(_b(n))]
                if hit:
                    st["nav"][k] = hit[0]
    st["nav"] = {k: st["nav"].get(k) for k in NAV}
    return st
