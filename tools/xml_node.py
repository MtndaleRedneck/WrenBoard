#!/usr/bin/env python3
"""xml_node.py <dump.xml> <exact text>: prints 'cx cy y1' of the first node with exactly that text; exit 1 if missing (never falls back)."""
import sys, re
x = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = sys.argv[2].replace("&", "&amp;")
for m in re.finditer(r'<node [^>]*?>', x):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1) == want:
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2, y1); sys.exit(0)
sys.exit(1)
