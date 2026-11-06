#!/usr/bin/env python3
"""Final verdict over every job's results.json. Fails (exit 1) unless every expected role is
present, bound to the requested app SHA where it has one, and passes every required leg, and
the continuous-typing comparison holds. Writes verdict.json and verdict.txt into --out.
"""
import argparse, glob, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import results as R
import typing_eval as T


def typing_evidence(data):
    return ((data or {}).get("legs", {}).get("continuous_typing") or {}).get("evidence")


def decide(m, found, baseline_sha, candidate_sha, reference, release):
    expected = ["baseline", "candidate"] + (["reference"] if reference == "swiftkey" else []) + (["release"] if release else [])
    problems = []
    for role in expected:
        d = found.get(role)
        if d is None:
            problems.append("%s: no results.json (job failed before writing, or was skipped)" % role); continue
        problems += R.gate(m, d, role)
    for role, sha in (("baseline", baseline_sha), ("candidate", candidate_sha)):
        d = found.get(role)
        if d is not None and d.get("app_sha") != sha:
            problems.append("%s: results are for app %r, not the requested %s" % (role, d.get("app_sha"), sha))
    cmp = T.compare(typing_evidence(found.get("baseline")), typing_evidence(found.get("candidate")), T.SENTENCE, len(T.WANT))
    if cmp["status"] != "pass":
        problems.append("verdict/compare_continuous_typing: %s %s" % (cmp["status"], cmp))
    return {"verdict": "pass" if not problems else "fail", "expected_roles": expected,
            "baseline_sha": baseline_sha, "candidate_sha": candidate_sha,
            "compare_continuous_typing": cmp, "problems": problems,
            "roles": {r: {n: v.get("status") for n, v in d.get("legs", {}).items()} for r, d in found.items()}}


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--dir", required=True); a.add_argument("--out", required=True)
    a.add_argument("--baseline-sha", required=True); a.add_argument("--candidate-sha", required=True)
    a.add_argument("--reference", choices=["none", "swiftkey"], required=True)
    a.add_argument("--release", choices=["yes", "no"], required=True)
    o = a.parse_args()
    m = R.load_manifest()
    found = {}
    for p in sorted(glob.glob(os.path.join(o.dir, "**", "results.json"), recursive=True)):
        d = json.load(open(p))
        if d.get("role") in found:
            print("::error::two results files for role %s" % d.get("role")); return 1
        found[d.get("role")] = d
    v = decide(m, found, o.baseline_sha, o.candidate_sha, o.reference, o.release == "yes")
    os.makedirs(o.out, exist_ok=True)
    json.dump(v, open(os.path.join(o.out, "verdict.json"), "w"), indent=1)
    lines = ["VERDICT: %s" % v["verdict"].upper(), "baseline %s / candidate %s" % (o.baseline_sha, o.candidate_sha)]
    for r, legs in sorted(v["roles"].items()):
        lines.append("%-10s %s" % (r, ", ".join("%s=%s" % kv for kv in sorted(legs.items()))))
    lines += ["PROBLEM: " + p for p in v["problems"]]
    open(os.path.join(o.out, "verdict.txt"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0 if v["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
