#!/usr/bin/env python3
"""Summarise JUnit XML reports and FAIL unless they prove a passing suite.

Exit 0 only when: the directory exists, holds at least one .xml report, every report is
non-empty well-formed JUnit (testsuite or testsuites root with integer counts), every suite's
counts are coherent (nonnegative, skipped <= tests, failures + errors <= tests - skipped, and equal
to its <testcase> children when it lists them), at least one test actually executed
(tests - skipped > 0 over the whole report set: an all-skipped suite fails), and there are no
failures or errors. Legitimately skipped tests inside a suite with real executed coverage are fine.
The count proves only what the reports contain, not that every intended test class ran.
Usage: unit_summary.py REPORT_DIR [--json OUT.json]
"""
import glob, json, os, sys
import xml.etree.ElementTree as ET


def suites_of(root):
    if root.tag == "testsuite":
        return [root]
    if root.tag == "testsuites":
        s = root.findall("testsuite")
        if not s:
            raise ValueError("testsuites root holds no testsuite")
        return s
    raise ValueError("root element is <%s>, not a JUnit testsuite" % root.tag)


def count(s, attr):
    v = s.get(attr)
    if v is None:
        return 0 if attr in ("skipped", "errors") else None
    n = int(v)
    if n < 0:
        raise ValueError("negative %s" % attr)
    return n


def summarise(d):
    errs, rows = [], []
    tests = failed = skipped = 0
    if not os.path.isdir(d):
        return {"ok": False, "problems": ["report directory %s does not exist" % d], "tests": 0, "failed": 0, "suites": 0, "rows": []}
    files = sorted(glob.glob(os.path.join(d, "*.xml")))
    if not files:
        errs.append("no .xml reports in %s" % d)
    for p in files:
        name = os.path.basename(p)
        if os.path.getsize(p) == 0:
            errs.append("%s is empty" % name); continue
        try:
            for s in suites_of(ET.parse(p).getroot()):
                n, fl, er = count(s, "tests"), count(s, "failures"), count(s, "errors")
                if n is None or fl is None:
                    raise ValueError("missing tests/failures count")
                sk = count(s, "skipped")
                if sk > n:
                    raise ValueError("suite %s: skipped %d > tests %d" % (s.get("name"), sk, n))
                if fl + er > n - sk:
                    raise ValueError("suite %s: failures+errors %d > executed %d" % (s.get("name"), fl + er, n - sk))
                cases = s.findall("testcase")
                if cases:
                    c_sk = sum(1 for c in cases if c.find("skipped") is not None)
                    c_fl = sum(1 for c in cases if c.find("failure") is not None or c.find("error") is not None)
                    if len(cases) != n or c_sk != sk or c_fl != fl + er:
                        raise ValueError("suite %s: counts tests=%d skipped=%d failed=%d disagree with its testcases (%d, %d skipped, %d failed)"
                                         % (s.get("name"), n, sk, fl + er, len(cases), c_sk, c_fl))
                tests += n; failed += fl + er; skipped += sk
                rows.append({"suite": s.get("name"), "tests": n, "failed": fl + er, "skipped": sk})
        except (ET.ParseError, ValueError, TypeError) as e:
            errs.append("%s is malformed: %s" % (name, e))
    if files and not errs and tests == 0:
        errs.append("reports hold 0 tests")
    elif files and not errs and tests - skipped <= 0:
        errs.append("no test executed: %d tests, %d skipped" % (tests, skipped))
    if failed:
        errs.append("%d failed or errored tests" % failed)
    return {"ok": not errs, "problems": errs, "tests": tests, "failed": failed, "skipped": skipped,
            "executed": tests - skipped, "suites": len(rows), "rows": rows}


def main(argv):
    if len(argv) < 2:
        print("usage: unit_summary.py REPORT_DIR [--json OUT]"); return 2
    r = summarise(argv[1])
    for row in r["rows"]:
        print("%-72s tests=%3d failed=%d skipped=%d" % (row["suite"], row["tests"], row["failed"], row["skipped"]))
    print("UNIT TESTS: %d run, %d failed, %d skipped, %d suites" % (r["tests"], r["failed"], r.get("skipped", 0), r["suites"]))
    for p in r["problems"]:
        print("UNIT SUITE PROBLEM: %s" % p)
    print("UNIT SUITE: %s" % ("PASS" if r["ok"] else "FAIL"))
    if "--json" in argv:
        with open(argv[argv.index("--json") + 1], "w") as f:
            json.dump(r, f, indent=1)
    return 0 if r["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
