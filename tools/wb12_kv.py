#!/usr/bin/env python3
"""Reads WBDBG logcat lines written by DEBUG builds (latin/uix/WbDebug.kt).
  wb12_kv.py <logfile> xy KEY|MORE <field> <value>   -> "cx cy" of the last matching key
  wb12_kv.py <logfile> list KEY|MORE                 -> one "code label cx cy w h" per key
  wb12_kv.py <logfile> kbd <field>                   -> a field of the last KBD line (y, h, elementId, ...)
  wb12_kv.py <logfile> sugg                          -> the last SUGG list (pipe separated)
"""
import re, sys

def rows(path, kind):
    out = []
    for line in open(path, errors="ignore"):
        m = re.search(r"WBDBG\s*:\s*(KEY|MORE|KBD) (.*)$", line.rstrip())
        if not m or m.group(1) != kind:
            continue
        d = {}
        for kv in m.group(2).split():
            if "=" in kv:
                k, v = kv.split("=", 1)
                d[k] = v
        out.append(d)
    return out

def main():
    path, mode = sys.argv[1], sys.argv[2]
    if mode == "xy":
        kind, field, val = sys.argv[3], sys.argv[4], sys.argv[5]
        r = rows(path, kind)
        m = [d for d in r if d.get(field) == val]
        if not m and field == "label":
            m = [d for d in r if d.get(field, "").lower() == val.lower()]
        if not m:
            sys.exit(1)
        print(m[-1]["cx"], m[-1]["cy"])
    elif mode == "list":
        for d in rows(path, sys.argv[3]):
            print(d.get("code"), d.get("label"), d.get("cx"), d.get("cy"), d.get("w"), d.get("h"))
    elif mode == "kbd":
        r = rows(path, "KBD")
        if not r:
            sys.exit(1)
        print(r[-1].get(sys.argv[3], ""))
    elif mode == "sugg":
        last = None
        for line in open(path, errors="ignore"):
            m = re.search(r"WBDBG\s*:\s*SUGG \[(.*)\]\s*$", line.rstrip())
            if m:
                last = m.group(1)
        if last is None:
            sys.exit(1)
        print(last)

main()
