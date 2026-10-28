#!/usr/bin/env python3
"""pagecheck.py <shot.png> <x1> <y1> <x2> <y2> <ref.png> [--save]
--save stores the crop as the reference; otherwise prints SAME or DIFF <mean abs diff>."""
import sys
from PIL import Image, ImageChops, ImageStat
shot, x1, y1, x2, y2, ref = sys.argv[1], *map(int, sys.argv[2:6]), sys.argv[6]
c = Image.open(shot).convert("L").crop((x1, y1, x2, y2))
if "--save" in sys.argv:
    c.save(ref); print("SAVED"); sys.exit(0)
r = Image.open(ref).convert("L")
if r.size != c.size: print("DIFF size"); sys.exit(0)
# glyph-mask comparison: tiny light labels on dark keys barely move the mean abs diff, so compare the
# bright-pixel masks instead (a pixel counts as glyph when brighter than 90).
cm = c.point(lambda v: 255 if v > 90 else 0); rm = r.point(lambda v: 255 if v > 90 else 0)
diff = sum(1 for a, b in zip(cm.getdata(), rm.getdata()) if a != b)
union = sum(1 for a, b in zip(cm.getdata(), rm.getdata()) if a or b) or 1
frac = diff / union
print(("SAME %.2f" if frac < 0.35 else "DIFF %.2f") % frac)
