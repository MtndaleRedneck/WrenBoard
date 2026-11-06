#!/usr/bin/env python3
"""Local tests for unit_summary.py and verdict.py: every bad report or incomplete result must fail."""
import json, os, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import results as R, verdict as V

TC = '<testcase name="a"/><testcase name="b"/><testcase name="c"/>'
GOOD = '<testsuite name="s" tests="3" failures="0" errors="0" skipped="0">%s</testsuite>' % TC
SKIP = '<testcase name="k"><skipped/></testcase>'
CASES = [("missing directory", None, 1), ("no reports", {}, 1), ("empty report", {"a.xml": ""}, 1),
         ("zero tests", {"a.xml": '<testsuite name="s" tests="0" failures="0"/>'}, 1),
         ("failures", {"a.xml": '<testsuite name="s" tests="3" failures="1" errors="0"/>'}, 1),
         ("errors", {"a.xml": '<testsuite name="s" tests="3" failures="0" errors="2"/>'}, 1),
         ("malformed xml", {"a.xml": "<testsuite name="}, 1),
         ("wrong root", {"a.xml": "<html/>"}, 1),
         ("no counts", {"a.xml": '<testsuite name="s"/>'}, 1),
         ("bad count", {"a.xml": '<testsuite name="s" tests="x" failures="0"/>'}, 1),
         ("one good one empty", {"a.xml": GOOD, "b.xml": ""}, 1),
         ("all skipped", {"a.xml": '<testsuite name="s" tests="5" failures="0" errors="0" skipped="5"/>'}, 1),
         ("skipped > tests", {"a.xml": '<testsuite name="s" tests="3" failures="0" errors="0" skipped="5"/>'}, 1),
         ("negative skipped", {"a.xml": '<testsuite name="s" tests="3" failures="0" skipped="-1"/>'}, 1),
         ("failures > executed", {"a.xml": '<testsuite name="s" tests="3" failures="2" errors="0" skipped="2"/>'}, 1),
         ("count disagrees with testcases", {"a.xml": '<testsuite name="s" tests="9" failures="0" errors="0" skipped="0">%s</testsuite>' % TC}, 1),
         ("skip count disagrees with testcases", {"a.xml": '<testsuite name="s" tests="3" failures="0" errors="0" skipped="1">%s</testsuite>' % TC}, 1),
         ("all-skipped across two files", {"a.xml": '<testsuite name="s" tests="2" failures="0" skipped="2"/>', "b.xml": '<testsuite name="t" tests="1" failures="0" skipped="1"/>'}, 1),
         ("legit skip with real coverage", {"a.xml": '<testsuite name="s" tests="4" failures="0" errors="0" skipped="1">%s%s</testsuite>' % (TC, SKIP)}, 0),
         ("passing suite", {"a.xml": GOOD}, 0),
         ("testsuites root", {"a.xml": "<testsuites>%s%s</testsuites>" % (GOOD, GOOD)}, 0)]


def unit_cases():
    miss = 0
    for name, files, want in CASES:
        d = tempfile.mkdtemp()
        target = os.path.join(d, "reports")
        if files is not None:
            os.makedirs(target)
            for f, body in files.items():
                open(os.path.join(target, f), "w").write(body)
        rc = subprocess.run([sys.executable, os.path.join(HERE, "unit_summary.py"), target], capture_output=True, text=True).returncode
        ok = rc == want
        miss += not ok
        print("%s unit_summary %-20s exit %d (want %d)" % ("ok  " if ok else "MISS", name, rc, want))
    return miss


import typing_eval as T


def typing_runs(words, iv_set=T.INTERVALS, over=None):
    over = over or {}
    n = len(T.SENTENCE)
    runs = []
    for iv in iv_set:
        r = {"interval_ms": iv, "taps": n, "taps_expected": n, "pace_ok": True, "words_ok": words, "words_total": len(T.WANT)}
        r.update(over.get(iv, {}))
        runs.append(r)
    return runs


def role(name, statuses, sha=None, words=None, runs=None, sentence=T.SENTENCE):
    m = R.load_manifest()
    legs = {n: {"status": statuses.get(n, "pass"), "evidence": {}} for n in R.required_for(m, name)}
    if "continuous_typing" in legs and (words is not None or runs is not None):
        legs["continuous_typing"]["evidence"] = {"sentence": sentence, "runs": runs if runs is not None else typing_runs(words)}
    return {"role": name, "app_sha": sha, "legs": legs}


def verdict_cases():
    m = R.load_manifest()
    b, c = "b" * 40, "c" * 40
    full = lambda **o: {"baseline": o.get("baseline", role("baseline", {}, b, 10)), "candidate": o.get("candidate", role("candidate", {}, c, 12)),
                        "reference": role("reference", {}, None, 14), "release": role("release", {})}
    cases = [("all pass", full(), "pass"),
             ("candidate leg inconclusive", full(candidate=role("candidate", {"model_corrupt": "inconclusive"}, c, 12)), "fail"),
             ("baseline leg fail", full(baseline=role("baseline", {"glide": "fail"}, b, 10)), "fail"),
             ("crash gate fail", full(candidate=role("candidate", {"crash_anr_gate": "fail"}, c, 12)), "fail"),
             ("wrong candidate sha", full(candidate=role("candidate", {}, b, 12)), "fail"),
             ("candidate types worse", full(candidate=role("candidate", {}, c, 9)), "fail"),
             ("no typing evidence", full(candidate=role("candidate", {}, c, None)), "fail"),
             ("candidate worse at one interval only", full(candidate=role("candidate", {}, c, runs=typing_runs(12, over={80: {"words_ok": 9}}))), "fail"),
             ("missing interval run", full(candidate=role("candidate", {}, c, runs=typing_runs(12, (60, 100)))), "fail"),
             ("duplicate interval run", full(candidate=role("candidate", {}, c, runs=typing_runs(12, (60, 80, 80, 100)))), "fail"),
             ("extra interval run", full(baseline=role("baseline", {}, b, runs=typing_runs(10, (60, 80, 100, 120)))), "fail"),
             ("partial run (taps short)", full(candidate=role("candidate", {}, c, runs=typing_runs(12, over={60: {"taps": 40}}))), "fail"),
             ("mismatched sentence", full(baseline=role("baseline", {}, b, 10, sentence="hello world ")), "fail"),
             ("mismatched taps_expected", full(baseline=role("baseline", {}, b, runs=typing_runs(10, over={100: {"taps_expected": 50, "taps": 50}}))), "fail"),
             ("mismatched word denominators", full(candidate=role("candidate", {}, c, runs=typing_runs(12, over={60: {"words_total": 20}}))), "fail"),
             ("zero denominator", full(baseline=role("baseline", {}, b, runs=typing_runs(0, over={60: {"words_total": 0}}))), "fail"),
             ("negative numerator", full(baseline=role("baseline", {}, b, runs=typing_runs(-1))), "fail"),
             ("numerator > denominator", full(candidate=role("candidate", {}, c, runs=typing_runs(99))), "fail"),
             ("non-integer counts", full(candidate=role("candidate", {}, c, runs=typing_runs("12"))), "fail"),
             ("boolean count", full(candidate=role("candidate", {}, c, runs=typing_runs(True))), "fail"),
             ("pace not proven", full(candidate=role("candidate", {}, c, runs=typing_runs(12, over={100: {"pace_ok": False}}))), "fail"),
             ("baseline missing entirely", {k: v for k, v in full().items() if k != "baseline"}, "fail")]
    miss = 0
    typing_from = [n for n, _, _ in cases].index("candidate types worse")
    for i, (name, found, want) in enumerate(cases):
        v = V.decide(m, found, b, c, "swiftkey", True)
        got, cs = v["verdict"], v["compare_continuous_typing"]["status"]
        bad = got != want or (i >= typing_from and cs == "pass") or (name == "all pass" and cs != "pass")
        miss += bad
        print("%s verdict %-38s %s (want %s); typing comparison %s" % ("MISS" if bad else "ok  ", name, got, want, cs))
    f = full(); del f["release"]
    got = V.decide(m, f, b, c, "swiftkey", True)["verdict"]
    miss += got != "fail"
    print("%s verdict %-28s %s (want fail)" % ("ok  " if got == "fail" else "MISS", "missing release role", got))
    return miss


def pace_cases():
    n = len(T.SENTENCE)
    miss = 0
    def downs(gaps, start=1000):
        out = [start]
        for g in gaps:
            out.append(out[-1] + g)
        return out
    for iv in T.INTERVALS:
        jit = [iv + (i % 5) - 1 for i in range(n - 1)]          # 0-4 ms late, as the injector runs idle
        stall = list(jit); stall[30] = iv + 45; stall[31] = iv - 40  # one host stall then catch-up
        cases = [("on pace", downs(jit), True),
                 ("one stall + catch-up", downs(stall), True),
                 ("deliberately slow x1.5", downs([int(iv * 1.5)] * (n - 1)), False),
                 ("slow by 12 ms per key", downs([iv + 12] * (n - 1)), False),
                 ("fast by 10 ms per key", downs([iv - 10] * (n - 1)), False),
                 ("many outliers", downs([iv + (30 if i % 4 == 0 else 0) for i in range(n - 1)]), False),
                 ("long stall", downs([iv] * 40 + [iv + 200] + [iv] * (n - 42)), False),
                 ("taps missing", downs(jit)[:-5], False),
                 ("no times", None, False)]
        for name, d, want in cases:
            got = T.pace_check(iv, d, n)[0]
            miss += got != want
            print("%s pace %3d ms %-24s ok=%s (want %s)" % ("ok  " if got == want else "MISS", iv, name, got, want))
    # Driver path: Typer-format output of a deliberately slow 100 ms run -> parse_taps -> rejected.
    import run_legs as RL
    for iv, real in ((100, 150), (60, 61)):
        t, lines = 5000, []
        for i in range(n):
            lines.append("TAP %d 100,200 at %d%s" % (i, t, "" if i == 0 else " gap %d" % real))
            t += real
        ok = T.pace_check(iv, RL.parse_taps("\n".join(lines)), n)[0]
        want = real - iv <= 3
        miss += ok != want
        print("%s pace driver parse: requested %d ms, injected every %d ms -> ok=%s (want %s)" % ("ok  " if ok == want else "MISS", iv, real, ok, want))
    return miss


if __name__ == "__main__":
    miss = unit_cases() + verdict_cases() + pace_cases()
    print("unit_summary/verdict tests: %d misses" % miss)
    sys.exit(1 if miss else 0)
