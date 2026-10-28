#!/usr/bin/env python3
"""toolbar_state.py <png> <bandTop> <bandBottom>: print orange-pixel count in the toolbar band."""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
t, b = int(sys.argv[2]), int(sys.argv[3])
n = 0
for y in range(t, b):
    for x in range(0, im.width, 2):
        r, g, bl = im.getpixel((x, y))
        if r > 200 and 100 < g < 200 and bl < 80:
            n += 1
print(n)
