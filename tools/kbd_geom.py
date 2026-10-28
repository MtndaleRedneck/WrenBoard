#!/usr/bin/env python3
"""Locate the on-screen keyboard in a screenshot and measure its key grid.

Harness model (validated against real WrenBoard captures):
- The app area above the keyboard is WHITE (ime-target field).
- Keyboard background is a uniform mid-dark tone; KEYS are DARKER than the
  background (subtle vertical gradient) and GLYPHS are much BRIGHTER.
  So a key pixel = brightness deviating below the bg tone, or a bright glyph
  pixel. Row gaps are flat background.
- Structure top->bottom: collapsed strip (expander + mic), number row,
  q row, a row, z row, bottom row (?123..enter), arrow row (4 wide keys).

Usage: kbd_geom.py screenshot.png overlay.png
stdout: JSON {keyboard_top, bands:[{y1,y2,cells:[{x1,y1,x2,y2,cx,cy}]}],
              strip:{clusters:int,y1,y2,cy,first:[x,y],last:[x,y]}|null,
              keys:{name:[cx,cy]}}
Exit 0 ok; 3 keyboard not found; 4 grid implausible.
"""
import json, sys
from collections import Counter
from PIL import Image, ImageDraw

def main():
    path, overlay_path = sys.argv[1], sys.argv[2]
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()

    # Row brightness profile (sampled every 8px).
    xs = list(range(0, W, 8))
    bright = []
    for y in range(H):
        s = 0
        for x in xs:
            p = px[x, y]
            s += (p[0] + p[1] + p[2]) / 3.0
        bright.append(s / len(xs))

    # Keyboard top: first sustained bright (white field) run from bottom.
    top = None
    run = 0
    for y in range(H - 1, -1, -1):
        if bright[y] > 200:
            run += 1
            if run >= 15:
                top = y + 15
                break
        else:
            run = 0
    if top is None or top > H - 300:
        print(json.dumps({"error": "no white field above keyboard region"}))
        sys.exit(3)

    # Background tone: modal brightness in the region below top.
    cnt = Counter(int(bright[y]) for y in range(top, H))
    bg = cnt.most_common(1)[0][0]

    # Row "key-ness": fraction of sampled pixels clearly darker than bg
    # (keys) or bright (glyphs). Row gaps are flat background.
    def row_keyfrac(y):
        n = 0
        for x in xs:
            p = px[x, y]
            b = (p[0] + p[1] + p[2]) / 3.0
            if b < bg - 12 or max(p) > 200:
                n += 1
        return n / len(xs)

    bands = []
    y = top
    while y < H:
        if row_keyfrac(y) > 0.35:
            y1 = y
            while y < H and (row_keyfrac(y) > 0.35 or
                             (y + 1 < H and row_keyfrac(y + 1) > 0.35)):
                y += 1
            y2 = y - 1
            if y2 - y1 >= 40:
                if bands and y1 - bands[-1][1] < 12:
                    bands[-1] = (bands[-1][0], y2)
                else:
                    bands.append((y1, y2))
        y += 1
    # A genuine key row carries bright glyphs; a uniform-dark nav/blank band
    # does not. Drop glyph-less bands when enough glyph-bearing bands exist.
    def has_glyphs(y1, y2):
        n = 0
        tot = 0
        for y in range(y1, y2 + 1, 3):
            for x in range(0, W, 8):
                p = px[x, y]
                tot += 1
                if max(p) > 200:
                    n += 1
        return tot > 0 and n / tot > 0.005
    # Only trailing glyph-less bands are dropped: a uniform-dark nav area is
    # always last, whereas a dim key row (e.g. number-row key bodies) can be
    # glyph-free in the middle.
    while len(bands) > 4 and not has_glyphs(*bands[-1]):
        bands.pop()
    if len(bands) < 4:
        print(json.dumps({"error": "too few key bands", "bands": len(bands), "bg": bg}))
        sys.exit(4)

    # Cells: per-column key score integrated over the whole band height,
    # robust to the key gradient and to hint glyphs at any slice.
    def band_cells(y1, y2):
        h = y2 - y1 + 1
        score = []
        for x in range(0, W, 2):
            n = 0
            for y in range(y1, y2 + 1, 3):
                p = px[x, y]
                b = (p[0] + p[1] + p[2]) / 3.0
                if b < bg - 10 or max(p) > 200:
                    n += 1
            score.append(n / max(len(range(y1, y2 + 1, 3)), 1))
        need = 0.45
        cells = []
        i = 0
        while i < len(score):
            if score[i] > need:
                x1 = i
                while i < len(score) and score[i] > need * 0.7:
                    i += 1
                x2 = i - 1
                if x2 - x1 >= 10:  # >= 20px
                    cells.append((x1 * 2, x2 * 2))
            i += 1
        merged = []
        for c in cells:
            if merged and c[0] - merged[-1][1] < 6:
                merged[-1] = (merged[-1][0], c[1])
            else:
                merged.append(c)
        return merged

    out_bands = []
    for (y1, y2) in bands:
        cells = band_cells(y1, y2)
        out_bands.append({"y1": y1, "y2": y2, "cells": [
            {"x1": a, "y1": y1, "x2": b, "y2": y2,
             "cx": (a + b) // 2, "cy": (y1 + y2) // 2} for (a, b) in cells]})

    # Strip: the collapsed expander/mic row (or expanded toolbar) lives in
    # the zone between keyboard top and the first key band.
    strip = None
    z1, z2 = top, out_bands[0]["y1"] - 6
    if z2 - z1 >= 30:
        zh = z2 - z1 + 1
        colscore = []
        for x in range(0, W, 2):
            n = 0
            rows = list(range(z1, z2 + 1, 3))
            for y in rows:
                p = px[x, y]
                b = (p[0] + p[1] + p[2]) / 3.0
                if b < bg - 16 or 180 < b < 250:
                    n += 1
            colscore.append(n / max(len(rows), 1))
        clusters = []
        i = 0
        while i < len(colscore):
            if colscore[i] > 0.25:
                x1 = i
                while i < len(colscore) and colscore[i] > 0.15:
                    i += 1
                x2 = i - 1
                if x2 - x1 >= 8:
                    clusters.append((x1 * 2, x2 * 2))
            i += 1
        if clusters:
            def cluster_cy(cl):
                # vertical centroid of key-ish pixels within the cluster's
                # x-range (precise icon center, avoids key-row tops)
                ys, n = 0, 0
                for y in range(z1, z2 + 1):
                    for x in range(cl[0], cl[1] + 1, 2):
                        p = px[x, y]
                        b = (p[0] + p[1] + p[2]) / 3.0
                        if b < bg - 16 or 180 < b < 250:
                            ys += y
                            n += 1
                return ys // n if n else (z1 + z2) // 2
            strip = {"clusters": len(clusters), "y1": z1, "y2": z2,
                     "cy": cluster_cy(clusters[0]),
                     "first": [(clusters[0][0] + clusters[0][1]) // 2,
                               cluster_cy(clusters[0])],
                     "last": [(clusters[-1][0] + clusters[-1][1]) // 2,
                              cluster_cy(clusters[-1])]}

    # Two-row strip (approved layout): toolbar icons on the upper row (orange glyphs),
    # chevron circle + suggestions on the lower row. Measured separately.
    toolbar = None
    if z2 - z1 >= 60:
        mid = z1 + (z2 - z1) // 2
        def is_orange(p):
            return (p[0] > 180 and 70 < p[1] < 190 and p[2] < 90 and p[0] - p[2] > 110) or (p[0] + p[1] + p[2]) / 3.0 > 170
        ocol = []
        for x in range(0, W, 2):
            n = 0
            ys = list(range(z1, mid, 2))
            for y in ys:
                if is_orange(px[x, y]):
                    n += 1
            ocol.append(n)
        cl = []
        i = 0
        while i < len(ocol):
            if ocol[i] >= 2:
                x1 = i
                gap = 0
                while i < len(ocol) and gap < 12:
                    gap = 0 if ocol[i] >= 2 else gap + 1
                    i += 1
                x2 = i - 1 - gap
                if x2 - x1 >= 6:
                    cl.append((x1 * 2, x2 * 2))
            i += 1
        items = []
        for c in cl:
            ys_ = [y for y in range(z1, mid) for x in range(c[0], c[1] + 1, 2) if is_orange(px[x, y])]
            cy = sum(ys_) // len(ys_) if ys_ else (z1 + mid) // 2
            items.append([(c[0] + c[1]) // 2, cy])
        if items:
            toolbar = {"n": len(items), "items": items, "y1": z1, "y2": mid}
        # lower row: first dark cluster = chevron circle
        lo1 = (items[0][1] + 75) if items else mid + 4
        lo2 = min(z2, lo1 + 130) if items else z2
        lowc = []
        for x in range(0, W, 2):
            n = 0
            rows_ = list(range(lo1, lo2 + 1, 3))
            for y in rows_:
                p = px[x, y]
                if (p[0] + p[1] + p[2]) / 3.0 < bg - 16:
                    n += 1
            lowc.append(n / max(len(rows_), 1))
        i = 0
        chev = None
        while i < len(lowc):
            if lowc[i] > 0.25:
                x1 = i
                while i < len(lowc) and lowc[i] > 0.15:
                    i += 1
                if (i - 1 - x1) >= 8:
                    chev = (x1 * 2, (i - 1) * 2)
                    break
            i += 1
        if chev:
            cyy = (lo1 + lo2) // 2
            strip = dict(strip or {})
            strip["first"] = [(chev[0] + chev[1]) // 2, cyy]
            strip["cy"] = cyy
            strip["chevron"] = True

    # Positional mapping with arrow-row awareness:
    #   bands[-1] with 4 wide cells = arrow row; bands[-2] = bottom row.
    keys = {}
    arrows = None
    if len(out_bands[-1]["cells"]) == 4:
        arrows = out_bands[-1]
        bottom = out_bands[-2]
        letter_bands = out_bands[:-2]
    else:
        bottom = out_bands[-1]
        letter_bands = out_bands[:-1]
    for name, i in (("arrow_up", 0), ("arrow_down", 1),
                    ("arrow_left", 2), ("arrow_right", 3)):
        if arrows:
            c = arrows["cells"][i]
            keys[name] = [c["cx"], c["cy"]]
    if len(bottom["cells"]) >= 4:
        keys["symbols"] = [bottom["cells"][0]["cx"], bottom["cells"][0]["cy"]]
        widest = max(bottom["cells"], key=lambda c: c["x2"] - c["x1"])
        keys["space"] = [widest["cx"], widest["cy"]]
        keys["enter"] = [bottom["cells"][-1]["cx"], bottom["cells"][-1]["cy"]]
    # letters: q row = last 10-cell band among letter_bands; number row = the
    # 10-cell band directly above it if present.
    bands10 = [b for b in letter_bands if len(b["cells"]) == 10]
    if bands10:
        qrow = bands10[-1]
        qi = letter_bands.index(qrow)
        for i, ch in enumerate("qwertyuiop"):
            keys[ch] = [qrow["cells"][i]["cx"], qrow["cells"][i]["cy"]]
        if qi > 0 and len(letter_bands[qi - 1]["cells"]) == 10:
            for i, ch in enumerate("1234567890"):
                keys["num_" + ch] = [letter_bands[qi - 1]["cells"][i]["cx"],
                                     letter_bands[qi - 1]["cells"][i]["cy"]]
        if qi + 1 < len(letter_bands) and len(letter_bands[qi + 1]["cells"]) == 9:
            for i, ch in enumerate("asdfghjkl"):
                keys[ch] = [letter_bands[qi + 1]["cells"][i]["cx"],
                            letter_bands[qi + 1]["cells"][i]["cy"]]
        if qi + 2 < len(letter_bands) and len(letter_bands[qi + 2]["cells"]) == 9:
            zrow = letter_bands[qi + 2]
            keys["shift"] = [zrow["cells"][0]["cx"], zrow["cells"][0]["cy"]]
            for i, ch in enumerate("zxcvbnm"):
                keys[ch] = [zrow["cells"][i + 1]["cx"], zrow["cells"][i + 1]["cy"]]
            keys["backspace"] = [zrow["cells"][-1]["cx"], zrow["cells"][-1]["cy"]]
    if strip:
        keys["strip_expand"] = strip["first"]
        keys["strip_mic"] = strip.get("last", strip["first"])
    if toolbar:
        keys["toolbar_voice"] = toolbar["items"][0]
        if len(toolbar["items"]) >= 5:
            keys["toolbar_clipboard"] = toolbar["items"][4]

    # Overlay for visual verification.
    ov = im.copy()
    d = ImageDraw.Draw(ov)
    for bi, b in enumerate(out_bands):
        d.rectangle([0, b["y1"], W - 1, b["y2"]], outline=(255, 60, 60), width=2)
        d.text((4, b["y1"] + 2), "band%d c%d" % (bi, len(b["cells"])),
               fill=(255, 200, 0))
        for ci, c in enumerate(b["cells"]):
            d.rectangle([c["x1"], c["y1"], c["x2"], c["y2"]],
                        outline=(60, 255, 60), width=1)
            d.text((c["x1"] + 3, c["cy"] - 6), str(ci), fill=(120, 255, 120))
    if strip:
        d.rectangle([0, strip["y1"], W - 1, strip["y2"]], outline=(60, 120, 255), width=2)
        d.text((W // 2 - 40, strip["y1"] + 2), "strip c%d" % strip["clusters"],
               fill=(120, 160, 255))
    for name, xy in keys.items():
        d.ellipse([xy[0] - 10, xy[1] - 10, xy[0] + 10, xy[1] + 10],
                  outline=(255, 0, 255), width=3)
        d.text((xy[0] + 12, xy[1] - 8), name, fill=(255, 0, 255))
    ov.save(overlay_path)

    print(json.dumps({"keyboard_top": top, "bg": bg, "bands": out_bands,
                      "strip": strip, "toolbar": toolbar, "keys": keys}))
    if "q" not in keys or "space" not in keys:
        sys.exit(4)

main()
