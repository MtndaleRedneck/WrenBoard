#!/usr/bin/env python3
"""Local positive and negative tests for validate_inputs.py (no GitHub needed). Exit 1 on any miss."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from validate_inputs import validate

GOOD = {"IN_APP_BASELINE_SHA": "a" * 40, "IN_APP_CANDIDATE_SHA": "0123456789abcdef0123456789abcdef01234567",
        "IN_REFERENCE": "swiftkey", "IN_RELEASE_TAG": "v1.5.0", "IN_RELEASE_ASSET": "WrenBoard-1.5.apk",
        "IN_WM_SIZE": "1080x2400", "IN_WM_DENSITY": "420", "IN_NAV": "gesture"}
POSITIVE = [{}, {"IN_REFERENCE": "none"}, {"IN_NAV": "3button"}, {"IN_RELEASE_TAG": "none"},
            {"IN_RELEASE_TAG": "none", "IN_RELEASE_ASSET": ""}, {"IN_WM_SIZE": "720x1280", "IN_WM_DENSITY": "240"},
            {"IN_WM_SIZE": "1600x3200", "IN_WM_DENSITY": "640"}]
NEGATIVE = [("IN_APP_BASELINE_SHA", "main"), ("IN_APP_BASELINE_SHA", "A" * 40), ("IN_APP_CANDIDATE_SHA", "a" * 39),
            ("IN_APP_CANDIDATE_SHA", "a" * 40 + "\n"), ("IN_APP_CANDIDATE_SHA", "$(id)"), ("IN_APP_BASELINE_SHA", ""),
            ("IN_REFERENCE", "gboard"), ("IN_REFERENCE", ""), ("IN_NAV", "3button; ls"),
            ("IN_RELEASE_TAG", "latest"), ("IN_RELEASE_TAG", "v9.9.9"), ("IN_RELEASE_TAG", "v1.5.0;id"),
            ("IN_RELEASE_ASSET", "*.apk"), ("IN_RELEASE_ASSET", "../x.apk"), ("IN_RELEASE_ASSET", "WrenBoard-1.6.apk"),
            ("IN_RELEASE_ASSET", ""), ("IN_WM_SIZE", "2400x1080"), ("IN_WM_SIZE", "1080x2400 && id"), ("IN_WM_SIZE", "100x200"),
            ("IN_WM_SIZE", "1080X2400"), ("IN_WM_DENSITY", "9999"), ("IN_WM_DENSITY", "-1"), ("IN_WM_DENSITY", "420dpi"),
            ("IN_WM_DENSITY", "")]


def main():
    miss = 0
    for p in POSITIVE:
        out, errs = validate(dict(GOOD, **p))
        ok = not errs
        miss += not ok
        print("%s accept %s %s" % ("ok  " if ok else "MISS", p, errs))
    for k, val in NEGATIVE:
        out, errs = validate(dict(GOOD, **{k: val}))
        ok = bool(errs) and k[3:].lower() not in out
        miss += not ok
        print("%s reject %s=%r" % ("ok  " if ok else "MISS", k, val))
    print("validate_inputs tests: %d positive, %d negative, %d misses" % (len(POSITIVE), len(NEGATIVE), miss))
    return 1 if miss else 0


if __name__ == "__main__":
    sys.exit(main())
