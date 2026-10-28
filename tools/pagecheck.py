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
d = ImageStat.Stat(ImageChops.difference(c, r)).mean[0]
print(("SAME %.1f" if d < 10 else "DIFF %.1f") % d)
