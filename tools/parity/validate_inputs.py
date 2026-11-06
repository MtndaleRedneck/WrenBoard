#!/usr/bin/env python3
"""Validate the parity workflow's dispatch inputs. Every input reaches this script through an
environment variable (never through workflow expressions inside script source) and is checked
against a strict pattern, enumeration or numeric range. Valid values are written to
GITHUB_OUTPUT; any invalid value stops the run with exit 2.
"""
import json, os, re, sys

SHA = re.compile(r"[0-9a-f]{40}")
TAG = re.compile(r"v(\d{1,3})\.(\d{1,3})\.(\d{1,3})")
ASSET = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.apk")
SIZE = re.compile(r"(\d{3,4})x(\d{3,4})")
INT = re.compile(r"\d{1,4}")

ENUMS = {"reference": ("none", "swiftkey"), "nav": ("gesture", "3button")}


def validate(env):
    errs, out = [], {}

    def get(k):
        return env.get("IN_" + k.upper(), "")

    for k in ("app_baseline_sha", "app_candidate_sha"):
        v = get(k)
        if SHA.fullmatch(v):
            out[k] = v
        else:
            errs.append("%s must be a full 40-character lowercase commit SHA" % k)
    for k, allowed in ENUMS.items():
        v = get(k)
        if v in allowed:
            out[k] = v
        else:
            errs.append("%s must be one of %s" % (k, ", ".join(allowed)))
    tag, asset = get("release_tag"), get("release_asset")
    expect = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "release-expect.json")))
    if tag == "none":
        out["release_tag"], out["release_asset"] = "none", ""   # the asset input is unused and ignored
    elif TAG.fullmatch(tag) and tag in expect:
        out["release_tag"] = tag
        if ASSET.fullmatch(asset) and asset == expect[tag]["asset"]:
            out["release_asset"] = asset
        else:
            errs.append("release_asset must be exactly %s for %s" % (expect[tag]["asset"], tag))
    else:
        errs.append("release_tag must be none or a release listed in release-expect.json (%s)" % ", ".join(k for k in expect if not k.startswith("_")))
    m = SIZE.fullmatch(get("wm_size"))
    if m and 720 <= int(m.group(1)) <= 1600 and 1280 <= int(m.group(2)) <= 3200 and int(m.group(1)) < int(m.group(2)):
        out["wm_size"] = get("wm_size")
    else:
        errs.append("wm_size must be WIDTHxHEIGHT, portrait, width 720-1600, height 1280-3200")
    d = get("wm_density")
    if INT.fullmatch(d) and 240 <= int(d) <= 640:
        out["wm_density"] = d
    else:
        errs.append("wm_density must be an integer from 240 to 640")
    return out, errs


def main():
    out, errs = validate(os.environ)
    if errs:
        for e in errs:
            print("::error::invalid input: %s" % e)
        return 2
    print(json.dumps(out, indent=1))
    gh = os.environ.get("GITHUB_OUTPUT")
    if gh:
        with open(gh, "a") as f:
            for k, v in out.items():
                f.write("%s=%s\n" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main())
