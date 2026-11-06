#!/usr/bin/env python3
"""The one pinned Android build-tools version and the strict app-signer reader, shared by
fetch_swiftkey.py (every SwiftKey split) and check_apk.py (release and app APKs).

Pin: build-tools 37.0.0. The GitHub ubuntu-24.04 and ubuntu-26.04 runner images (20261101) ship
37.0.0, 36.1.0, 36.0.0, 35.0.1, 35.0.0 and 34.0.0. Before v3.3 the harness took whichever version
sorted highest; run 2 got 37.0.0 while the local check had used 34.0.0, and the two print the
signer in different forms. Now exactly build-tools/37.0.0 is used, its source.properties must say
Pkg.Revision=37.0.0, and a missing or different directory fails closed: there is no fallback.

apksigner verify --print-certs, real outputs (testdata/apksigner/):
  34.x/35.x  "Signer #1 certificate SHA-256 digest: <hex>"
             "Source Stamp Signer certificate SHA-256 digest: <hex>"   (Play's stamp, not a signer)
  37.x       "V2 Signer: certificate SHA-256 digest: <hex>", "V3.0 Signer: ...", "V2 Signer #2: ..."
             "Source Stamp Signer: certificate SHA-256 digest: <hex>"
app_signer() accepts either app-signer form and nothing else: exit 0, no unrecognised SHA-256 line,
not both forms at once, at least one app-signer line, and exactly one distinct digest. The source
stamp is recognised and never counted. check_signer() then requires that digest to equal the pin.
"""
import os, re

BUILD_TOOLS = "37.0.0"
OLD_FORM = re.compile(r"^Signer #([1-9][0-9]*) certificate SHA-256 digest: ([0-9a-f]{64})$")
NEW_FORM = re.compile(r"^V([1-4](?:\.[0-9])?) Signer(?: #([1-9][0-9]*))?: certificate SHA-256 digest: ([0-9a-f]{64})$")
STAMP_FORM = re.compile(r"^Source Stamp Signer:? certificate SHA-256 digest: ([0-9a-f]{64})$")


class SignerError(Exception):
    def __init__(self, category, detail):
        Exception.__init__(self, "%s: %s" % (category, detail))
        self.category = category


def build_tool(name, root=None):
    """Path of NAME in the pinned build-tools directory; raises SignerError(build_tools_missing)."""
    if name not in ("aapt2", "apksigner"):
        raise ValueError(name)
    root = root or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        raise SignerError("build_tools_missing", "ANDROID_HOME is not set")
    d = os.path.join(root, "build-tools", BUILD_TOOLS)
    p = os.path.join(d, name)
    props = os.path.join(d, "source.properties")
    rev = None
    if os.path.isfile(props):
        m = re.search(r"^Pkg\.Revision=(\S+)\s*$", open(props).read(), re.M)
        rev = m.group(1) if m else None
    if rev != BUILD_TOOLS or not os.path.isfile(p) or not os.access(p, os.X_OK):
        raise SignerError("build_tools_missing", "pinned build-tools %s not present (%s: revision %s, %s %s)"
                          % (BUILD_TOOLS, d, rev, name, "present" if os.path.isfile(p) else "absent"))
    return p


def app_signer(rc, out):
    """The one distinct app-signer SHA-256 in apksigner --print-certs output, plus the evidence.
    Raises SignerError with category nonzero_exit, ambiguous, missing or multiple."""
    old, new, stamp, unknown = [], [], [], []
    for line in out.splitlines():
        l = line.rstrip("\r\n")
        if "SHA-256" not in l.upper():
            continue
        m = OLD_FORM.match(l)
        if m:
            old.append(m.group(2)); continue
        m = NEW_FORM.match(l)
        if m:
            new.append(m.group(3)); continue
        m = STAMP_FORM.match(l)
        if m:
            stamp.append(m.group(1)); continue
        unknown.append(l[:160])
    ev = {"rc": rc, "old_form": old, "new_form": new, "source_stamp": stamp, "unrecognised": unknown}
    if rc != 0:
        raise SignerError("nonzero_exit", "apksigner exited %d" % rc)
    if unknown:
        raise SignerError("ambiguous", "unrecognised SHA-256 line(s) %s" % unknown[:3])
    if old and new:
        raise SignerError("ambiguous", "both the 34.x and the 37.x signer forms in one output")
    distinct = sorted(set(old + new))
    if not distinct:
        raise SignerError("missing", "no app-signer line (source stamp lines: %d)" % len(stamp))
    if len(distinct) > 1:
        raise SignerError("multiple", "%d distinct app signers %s" % (len(distinct), distinct))
    return distinct[0], ev


def check_signer(rc, out, expect):
    """app_signer() and the pin: returns the digest, raises SignerError (wrong) on any other one."""
    got, ev = app_signer(rc, out)
    if got != expect.lower():
        raise SignerError("wrong", "app signer %s, expected %s" % (got, expect.lower()))
    return got, ev
