#!/usr/bin/env python3
# T6 helper: OCR a screenshot, locate the adjacent "going" "blank" token pair (the run-together
# split chip in the suggestion strip) and print its tap point. Usage: wren110-t6-chip.py shot.png
# Prints "CHIP x y" on success, "CHIP none" otherwise. tesseract TSV over stdin.
import subprocess, sys
img = sys.argv[1]
out = subprocess.run(["tesseract", img, "stdout", "tsv"], capture_output=True, text=True).stdout
rows = []
for line in out.splitlines()[1:]:
    f = line.split("\t")
    if len(f) == 12 and f[11].strip() and float(f[10]) > 40:
        rows.append((int(f[6]), int(f[7]), int(f[8]), int(f[9]), f[11].strip()))
# group into lines by top proximity
rows.sort(key=lambda r: (r[1], r[0]))
lines = []
for r in rows:
    if lines and abs(lines[-1][0][1] - r[1]) < 12:
        lines[-1].append(r)
    else:
        lines.append([r])
found = None
for ln in lines:
    ln.sort(key=lambda r: r[0])
    toks = [t[4].lower().strip(".,!|\"'") for t in ln]
    for i in range(len(toks) - 1):
        if toks[i] == "going" and toks[i+1] == "blank":
            a, b = ln[i], ln[i+1]
            # reject the field's composing "goingblank" (single token) - the pair is the chip
            cx = (a[0] + a[2] + b[0]) // 2
            cy = a[1] + a[3] // 2
            found = (cx, cy, ln)
            break
    if found: break
if found:
    print("CHIP %d %d" % (found[0], found[1]))
    print("LINE " + " ".join(t[4] for t in found[2]))
else:
    print("CHIP none")
    print("LINE " + " | ".join(" ".join(t[4] for t in ln) for ln in lines[:8]))
