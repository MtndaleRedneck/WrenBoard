#!/usr/bin/env python3
"""Continuous-typing evidence: pace bounds for one run, and the matched baseline/candidate comparison.

Pace bounds (enforced per requested interval, 60 / 80 / 100 ms):
  The injector (typer/Typer.java) schedules key-down n at the absolute deadline t0 + n*interval and
  polls in sleeps of at most 5 ms, so on an idle device every down lands 0-5 ms after its deadline and
  the gap between consecutive downs is the requested interval to within about +/-5 ms. An x86_64
  emulator on a shared hosted runner adds host scheduling latency: a few ms routinely and, rarely, a
  stall of some tens of ms (a GC or a host preemption). Because deadlines are absolute, one stall makes
  one long gap followed by one short catch-up gap; it does not slow the rest of the run. The bounds:
    - every tap reported, and one gap per consecutive pair of taps (complete record);
    - MEDIAN gap within [interval - 3, interval + 8] ms;
    - MEAN pace over the run, (last down - first down) / (taps - 1), within [interval - 2, interval + 6] ms;
    - at least 95 % of gaps within interval +/- 15 ms (GAP_TOL_MS);
    - no gap longer than interval + 60 ms (MAX_STALL_MS) and none shorter than 0.
  A run outside any bound is "pace_ok": false and the leg fails; a slower-than-requested sequence can
  therefore never stand in for the requested pace. These bounds are set from the injector's design, not
  loosened to fit a measured run; the first real run's gaps are recorded in full for review.

Comparison (compare): baseline and candidate must carry matched evidence - the same synthetic
sentence, exactly one complete run for each of 60, 80 and 100 ms, the same tap and word denominators,
integer counts with 0 <= words_ok <= words_total, words_total > 0, and pace_ok on every run. Then
accuracy is compared interval by interval: candidate words_ok / words_total must be >= the baseline's
at every interval. Anything unmatched is "inconclusive" (never a pass).
"""
import statistics

# The fixed synthetic sentence (typos included on purpose) and the words autocorrect should produce.
SENTENCE = "im sure teh brwon dog cant find its bone adn i jsut recieve thier form from hello becuase "
WANT = "I'm sure the brown dog can't find its bone and I just receive their form from hello because ".split()

INTERVALS = (60, 80, 100)
GAP_TOL_MS = 15
OUTLIER_SHARE = 0.05
MAX_STALL_MS = 60
MEDIAN_LO, MEDIAN_HI = -3, 8
MEAN_LO, MEAN_HI = -2, 6


def _int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def pace_check(interval_ms, downs, taps_expected):
    """downs: key-down uptimes (ms) in tap order, as Typer printed them. Returns (ok, facts)."""
    p = []
    if not _int(interval_ms) or interval_ms <= 0:
        return False, {"problems": ["bad requested interval %r" % (interval_ms,)]}
    if not isinstance(downs, list) or not all(_int(d) for d in downs):
        return False, {"problems": ["key-down times missing or not integers"]}
    if len(downs) != taps_expected or taps_expected < 2:
        p.append("%d of %d taps reported" % (len(downs), taps_expected))
    gaps = [b - a for a, b in zip(downs, downs[1:])]
    facts = {"interval_ms": interval_ms, "gaps_ms": gaps, "bounds": {
        "median": [interval_ms + MEDIAN_LO, interval_ms + MEDIAN_HI], "mean": [interval_ms + MEAN_LO, interval_ms + MEAN_HI],
        "gap_tol": GAP_TOL_MS, "outlier_share_max": OUTLIER_SHARE, "max_gap": interval_ms + MAX_STALL_MS}}
    if len(gaps) < 1:
        p.append("no gaps recorded")
        facts["problems"] = p
        return False, facts
    med = statistics.median(gaps)
    mean = (downs[-1] - downs[0]) / float(len(gaps))
    out = [g for g in gaps if abs(g - interval_ms) > GAP_TOL_MS]
    facts.update({"median_ms": med, "mean_ms": round(mean, 2), "min_ms": min(gaps), "max_ms": max(gaps),
                  "outside_tol": len(out)})
    if not interval_ms + MEDIAN_LO <= med <= interval_ms + MEDIAN_HI:
        p.append("median gap %s ms outside %s" % (med, facts["bounds"]["median"]))
    if not interval_ms + MEAN_LO <= mean <= interval_ms + MEAN_HI:
        p.append("mean pace %.2f ms outside %s" % (mean, facts["bounds"]["mean"]))
    if len(out) > OUTLIER_SHARE * len(gaps):
        p.append("%d of %d gaps outside +/-%d ms" % (len(out), len(gaps), GAP_TOL_MS))
    if max(gaps) > interval_ms + MAX_STALL_MS:
        p.append("a gap of %d ms exceeds %d" % (max(gaps), interval_ms + MAX_STALL_MS))
    if min(gaps) < 0:
        p.append("negative gap")
    facts["problems"] = p
    return not p, facts


def _runs_by_interval(ev, sentence, role):
    """Validate one role's continuous_typing evidence; return ({interval: run}, problems)."""
    p = []
    if not isinstance(ev, dict):
        return {}, ["%s: no continuous_typing evidence" % role]
    if ev.get("sentence") != sentence:
        p.append("%s: sentence differs from the harness sentence" % role)
    runs = ev.get("runs")
    if not isinstance(runs, list) or not runs:
        return {}, p + ["%s: no runs" % role]
    by = {}
    for r in runs:
        iv = r.get("interval_ms") if isinstance(r, dict) else None
        if iv in by:
            p.append("%s: duplicate run for %r ms" % (role, iv)); continue
        by[iv] = r
    if set(by) != set(INTERVALS):
        p.append("%s: intervals %s, expected exactly %s" % (role, sorted(by, key=str), list(INTERVALS)))
    for iv, r in by.items():
        ok, tot, taps, texp = r.get("words_ok"), r.get("words_total"), r.get("taps"), r.get("taps_expected")
        if not all(_int(v) for v in (ok, tot, taps, texp)):
            p.append("%s/%s: counts are not integers" % (role, iv)); continue
        if tot <= 0 or ok < 0 or ok > tot:
            p.append("%s/%s: invalid words %r/%r" % (role, iv, ok, tot))
        if texp != len(sentence) or taps != texp:
            p.append("%s/%s: partial run, %r of %r taps (sentence has %d)" % (role, iv, taps, texp, len(sentence)))
        if r.get("pace_ok") is not True:
            p.append("%s/%s: pace not proven" % (role, iv))
    return by, p


def compare(base_ev, cand_ev, sentence, words_total_expected):
    b, pb = _runs_by_interval(base_ev, sentence, "baseline")
    c, pc = _runs_by_interval(cand_ev, sentence, "candidate")
    problems = pb + pc
    per = []
    if not problems:
        for iv in INTERVALS:
            rb, rc = b[iv], c[iv]
            if rb["words_total"] != rc["words_total"] or rb["words_total"] != words_total_expected:
                problems.append("%s ms: word denominators differ (%s vs %s, expected %s)" % (iv, rb["words_total"], rc["words_total"], words_total_expected))
                continue
            ab, ac = rb["words_ok"] / float(rb["words_total"]), rc["words_ok"] / float(rc["words_total"])
            per.append({"interval_ms": iv, "baseline": [rb["words_ok"], rb["words_total"]], "candidate": [rc["words_ok"], rc["words_total"]],
                        "baseline_acc": round(ab, 4), "candidate_acc": round(ac, 4), "candidate_not_worse": ac >= ab})
    if problems:
        return {"status": "inconclusive", "problems": problems, "per_interval": per}
    worse = [x["interval_ms"] for x in per if not x["candidate_not_worse"]]
    return {"status": "fail" if worse else "pass", "per_interval": per,
            "problems": ["candidate less accurate than baseline at %s ms" % worse] if worse else []}
