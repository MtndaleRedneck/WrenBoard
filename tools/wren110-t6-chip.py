#!/usr/bin/env python3
# T6 helper: locate the adjacent "going" "blank" token pair (the run-together split chip in
# the suggestion strip) and print its tap point. Usage: wren110-t6-chip.py shot.png
# Prints "CHIP x y" on success, "CHIP none" otherwise.
#
# The strip renders orange text on a dark keyboard, which tesseract's default pass misreads
# as keyboard glyphs (observed: "goingblank| | ia aes | @ # & * ..." while the "going blank"
# chip was visibly rendered - runs 45462168068 / 45465235850). So the strip band is cropped,
# inverted, thresholded and upscaled before OCR, with tap coordinates mapped back to the
# original image. Falls back to the raw full-image pass when the band yields no pair.
import subprocess, sys
from PIL import Image, ImageOps

img_path = sys.argv[1]

def ocr_rows(png_path):
    out = subprocess.run(["tesseract", png_path, "stdout", "--psm", "6", "tsv"],
                         capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines()[1:]:
        f = line.split("\t")
        if len(f) == 12 and f[11].strip() and float(f[10]) > 40:
            rows.append((int(f[6]), int(f[7]), int(f[8]), int(f[9]), f[11].strip()))
    return rows

def find_pair(rows):
    rows = sorted(rows, key=lambda r: (r[1], r[0]))
    lines = []
    for r in rows:
        if lines and abs(lines[-1][0][1] - r[1]) < 14:
            lines[-1].append(r)
        else:
            lines.append([r])
    for ln in lines:
        ln.sort(key=lambda r: r[0])
        toks = [t[4].lower().strip(".,!|\"'") for t in ln]
        for i in range(len(toks) - 1):
            if toks[i] == "going" and toks[i+1] == "blank":
                a, b = ln[i], ln[i+1]
                cx = (a[0] + a[2] + b[0]) // 2
                cy = a[1] + a[3] // 2
                return (cx, cy, ln)
    return None

img = Image.open(img_path)
W, H = img.size
# The suggestion strip is the candidate row directly under the toolbar icons; on the fixture
# it sits at ~0.55-0.60 of the height. Crop generously and map coordinates back.
x0, y0, x1, y1 = 0, int(H * 0.53), W, int(H * 0.61)
band = img.crop((x0, y0, x1, y1))
scale = 2
# Inverted, the orange strip text (~98) sits BELOW the dark-panel background (~205);
# threshold between them so text comes out black on white for tesseract.
proc = ImageOps.invert(band.convert("L")).point(lambda p: 255 if p > 130 else 0)
proc = proc.resize((proc.width * scale, proc.height * scale))
proc.save("/tmp/t6-band.png")

found = None
rows = ocr_rows("/tmp/t6-band.png")
hit = find_pair(rows)
if hit:
    cx, cy, ln = hit
    found = (x0 + cx // scale, y0 + cy // scale, ln)
else:
    # Fallback: original full-image pass (kept for light-theme or layout drift).
    rows = ocr_rows(img_path)
    hit = find_pair(rows)
    if hit:
        found = hit

if found:
    print("CHIP %d %d" % (found[0], found[1]))
    print("LINE " + " ".join(t[4] for t in found[2]))
else:
    print("CHIP none")
    rows = sorted(rows, key=lambda r: (r[1], r[0]))
    lines = []
    for r in rows:
        if lines and abs(lines[-1][0][1] - r[1]) < 14:
            lines[-1].append(r)
        else:
            lines.append([r])
    print("LINE " + " | ".join(" ".join(t[4] for t in ln) for ln in lines[:8]))
