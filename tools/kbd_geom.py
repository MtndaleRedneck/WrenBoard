#!/usr/bin/env python3
"""Locate the on-screen keyboard in a screenshot and measure its key grid.

Harness assumptions (validated by the ime-target app):
- The app area above the keyboard is WHITE (bright).
- The keyboard is a dark region with lighter key rectangles separated by
  darker gaps. Rows are full-width; keys in a row are separated by gaps.

Usage: kbd_geom.py screenshot.png overlay.png
stdout: JSON {keyboard_top, bands:[{y1,y2,cells:[{x1,y1,x2,y2,cx,cy}]}],
              toolbar:{cells:int,strip:[y1,y2]}|null, keys:{name:[cx,cy]}}
Exit 0 ok; 3 keyboard not found; 4 grid implausible.
"""
import json, sys
from PIL import Image, ImageDraw

def col_means(im, y, W, step=2):
    px = im.load()
    return [sum(px[x, y]) / 3.0 for x in range(0, W, step)]

def main():
    path, overlay_path = sys.argv[1], sys.argv[2]
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()

    # Row brightness profile (sampled).
    bright = []
    for y in range(H):
        s = 0
        n = 0
        for x in range(0, W, 8):
            p = px[x, y]
            s += (p[0] + p[1] + p[2]) / 3.0
            n += 1
        bright.append(s / max(n, 1))

    # Keyboard top: scan from bottom for first sustained bright (white field) run.
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
    if top is None or top > H - 200:
        print(json.dumps({"error": "no white field above keyboard region"}))
        sys.exit(3)

    # Region profile stats: key bands vs background gaps.
    region = bright[top:]
    srt = sorted(region)
    base = srt[int(len(srt) * 0.20)]
    high = srt[int(len(srt) * 0.85)]
    if high - base < 12:
        print(json.dumps({"error": "keyboard region has no key/gap contrast",
                          "base": base, "high": high}))
        sys.exit(3)
    thr = base + (high - base) * 0.45

    # Bands: runs above threshold, min height 20, merge gaps < 8.
    bands = []
    y = top
    while y < H:
        if bright[y] > thr:
            y1 = y
            while y < H and (bright[y] > thr or (y + 1 < H and bright[y + 1] > thr)):
                y += 1
            y2 = y - 1
            if y2 - y1 >= 20:
                if bands and y1 - bands[-1][1] < 8:
                    bands[-1] = (bands[-1][0], y2)
                else:
                    bands.append((y1, y2))
        y += 1
    # Drop a trailing band that is likely the nav area: nav bar is dark, so
    # it should already be excluded by the threshold.
    if len(bands) < 3:
        print(json.dumps({"error": "too few key bands", "bands": len(bands)}))
        sys.exit(4)

    # Cells per band from the mid-row brightness run.
    out_bands = []
    for (y1, y2) in bands:
        ym = (y1 + y2) // 2
        row = col_means(im, ym, W)
        cells = []
        x = 0
        while x < len(row):
            if row[x] > thr:
                x1 = x
                while x < len(row) and row[x] > thr - 8:
                    x += 1
                x2 = x - 1
                if x2 - x1 >= 4:  # >=16px wide
                    cells.append((x1 * 2, x2 * 2))
            x += 1
        # Merge cells separated by tiny gaps (<12px) - glyph holes inside a key.
        merged = []
        for c in cells:
            if merged and c[0] - merged[-1][1] < 5:
                merged[-1] = (merged[-1][0], c[1])
            else:
                merged.append(c)
        out_bands.append({"y1": y1, "y2": y2, "cells": [
            {"x1": a, "y1": y1, "x2": b, "y2": y2,
             "cx": (a + b) // 2, "cy": (y1 + y2) // 2} for (a, b) in merged]})

    # Toolbar: strip between keyboard top and first band; count icon clusters.
    toolbar = None
    if out_bands:
        strip_y1, strip_y2 = top, out_bands[0]["y1"]
        if strip_y2 - strip_y1 >= 30:
            ym = (strip_y1 + strip_y2) // 2
            row = col_means(im, ym, W)
            mx = max(row) if row else 0
            tthr = base + (mx - base) * 0.5
            clusters = 0
            inb = False
            for v in row:
                if v > tthr and not inb:
                    clusters += 1
                    inb = True
                elif v <= tthr:
                    inb = False
            toolbar = {"cells": clusters, "strip": [strip_y1, strip_y2],
                       "cy": ym}

    # Positional key mapping.
    keys = {}
    def cell(b, i):
        cs = b["cells"]
        return [cs[i]["cx"], cs[i]["cy"]] if -len(cs) <= i < len(cs) else None
    bands10 = [b for b in out_bands if len(b["cells"]) == 10]
    qrow = bands10[-1] if bands10 else None
    qletters = "qwertyuiop"
    if qrow:
        for i, ch in enumerate(qletters):
            keys[ch] = cell(qrow, i)
        qi = out_bands.index(qrow)
        if qi > 0 and len(out_bands[qi - 1]["cells"]) == 10:
            for i, ch in enumerate("1234567890"):
                keys["num_" + ch] = cell(out_bands[qi - 1], i)
        if qi + 1 < len(out_bands) and len(out_bands[qi + 1]["cells"]) == 9:
            arow = out_bands[qi + 1]
            for i, ch in enumerate("asdfghjkl"):
                keys[ch] = cell(arow, i)
        if qi + 2 < len(out_bands) and len(out_bands[qi + 2]["cells"]) == 9:
            zrow = out_bands[qi + 2]
            keys["shift"] = cell(zrow, 0)
            for i, ch in enumerate("zxcvbnm"):
                keys[ch] = cell(zrow, i + 1)
            keys["backspace"] = cell(zrow, -1)
        # bottom row: last band; first cell = symbols key, widest = space,
        # last = enter.
        brow = out_bands[-1]
        if brow is not qrow and len(brow["cells"]) >= 3:
            keys["symbols"] = cell(brow, 0)
            widest = max(brow["cells"], key=lambda c: c["x2"] - c["x1"])
            keys["space"] = [widest["cx"], widest["cy"]]
            keys["enter"] = cell(brow, -1)
    if toolbar and toolbar["cells"] >= 5 and out_bands:
        ty = toolbar["cy"]
        n = toolbar["cells"]
        keys["toolbar_voice"] = [W // (2 * n), ty]

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
    if toolbar:
        d.rectangle([0, toolbar["strip"][0], W - 1, toolbar["strip"][1]],
                    outline=(60, 120, 255), width=2)
    for name, xy in keys.items():
        if xy:
            d.ellipse([xy[0] - 8, xy[1] - 8, xy[0] + 8, xy[1] + 8],
                      outline=(255, 0, 255), width=3)
            d.text((xy[0] + 10, xy[1] - 8), name, fill=(255, 0, 255))
    ov.save(overlay_path)

    print(json.dumps({"keyboard_top": top, "bands": out_bands,
                      "toolbar": toolbar, "keys": keys}))
    if not qrow or "space" not in keys:
        sys.exit(4)

main()
