#!/usr/bin/env python3
"""Signer regressions (run 2: the reference job failed "signer [] (rc 0)" because CI's newest build-tools,
37.0.0, prints "V3.0 Signer: certificate SHA-256 digest:" where the parser knew only 34.x's "Signer #1
certificate SHA-256 digest:"). Fixtures in testdata/apksigner/ are real apksigner outputs: the SwiftKey
1236402240 APK under build-tools 34.0.0 and 37.0.0 (parity-run2/diagnosis/), and a throwaway local key
signed v1+v2, v3, and with two signers, each under 34.0.0 and 37.0.0. No network, no real APK."""
import io, os, re, shutil, subprocess, sys, tempfile, contextlib
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import apksig
import fetch_swiftkey as F
import check_apk

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

FX = os.path.join(HERE, "testdata", "apksigner")
def fx(name): return open(os.path.join(FX, name)).read()
SK34, SK37 = fx("swiftkey-1236402240_bt34.0.0.txt"), fx("swiftkey-1236402240_bt37.0.0.txt")
PIN = F.EXPECT
LOCAL_A = "ef584547d01a651448d3d2e1e9d900da1f878d3c6fa6a29e93fc363730742818"
STAMP = "3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d"
OLD_REGEX = r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$"  # v3.2's fetch_swiftkey.py


def outcome(rc, out, pin=PIN):
    try:
        return "pass:" + apksig.check_signer(rc, out, pin)[0]
    except apksig.SignerError as e:
        return e.category


def fake_sdk(root, versions, rev=None, body="true", exe=True):
    shutil.rmtree(root, ignore_errors=True)
    for v in versions:
        d = os.path.join(root, "build-tools", v); os.makedirs(d)
        open(os.path.join(d, "source.properties"), "w").write("Pkg.UserSrc=false\nPkg.Revision=%s\n" % (rev or v))
        for n in ("aapt2", "apksigner"):
            p = os.path.join(d, n); open(p, "w").write("#!/bin/sh\n%s\n" % body); os.chmod(p, 0o755 if exe else 0o644)
    return root


def main():
    tmp = tempfile.mkdtemp(prefix="signer-")
    try:
        # 1. the run-2 regression, on the real outputs
        check(sorted(set(re.findall(OLD_REGEX, SK37, re.M))) == [], "v3.2's regex finds no signer in the real 37.0.0 output (the run-2 'signer [] (rc 0)')")
        check(outcome(0, SK34) == "pass:" + PIN, "real SwiftKey output, build-tools 34.0.0 ('Signer #1 certificate'): one app signer = pin")
        check(outcome(0, SK37) == "pass:" + PIN, "real SwiftKey output, build-tools 37.0.0 ('V3.0 Signer: certificate'): one app signer = pin")
        for name, out in (("34.0.0", SK34), ("37.0.0", SK37)):
            ev = apksig.app_signer(0, out)[1]
            check(ev["source_stamp"] == [STAMP] and STAMP not in ev["old_form"] + ev["new_form"],
                  "real %s output: the Play source stamp is recognised and not counted as a signer" % name)
        # 2. the other real forms (throwaway local key)
        for f in ("localkey-v12_bt34.0.0.txt", "localkey-v12_bt37.0.0.txt", "localkey-v3_bt34.0.0.txt", "localkey-v3_bt37.0.0.txt"):
            check(outcome(0, fx(f), LOCAL_A) == "pass:" + LOCAL_A, "real %s: its one signer is read" % f)
            check(outcome(0, fx(f)) == "wrong", "real %s against the SwiftKey pin: wrong signer" % f)
        for f in ("localkey-two_bt34.0.0.txt", "localkey-two_bt37.0.0.txt"):
            check(outcome(0, fx(f), LOCAL_A) == "multiple", "real %s (two signers, 'Signer #2' / 'V2 Signer #2:'): multiple" % f)
        check(outcome(1, fx("unsigned_bt37.0.0.txt")) == "nonzero_exit", "real unsigned APK under 37.0.0 (DOES NOT VERIFY, exit 1): nonzero_exit")
        # 3. rejections
        stamp34 = "\n".join(l for l in SK34.splitlines() if l.startswith("Source Stamp"))
        stamp37 = "\n".join(l for l in SK37.splitlines() if l.startswith("Source Stamp"))
        pin_as_stamp = "Source Stamp Signer: certificate SHA-256 digest: %s\n" % PIN
        cases = [
            ("source stamp only, 34.x form", 0, stamp34, "missing"),
            ("source stamp only, 37.x form", 0, stamp37, "missing"),
            ("the pin as a source stamp, no app signer", 0, pin_as_stamp, "missing"),
            ("empty output", 0, "", "missing"),
            ("nonzero exit with a valid 37.x signer", 1, SK37, "nonzero_exit"),
            ("nonzero exit with a valid 34.x signer", 2, SK34, "nonzero_exit"),
            ("both forms in one output", 0, SK34 + SK37, "ambiguous"),
            ("unrecognised digest line", 0, SK37 + "Signer certificate SHA-256 digest: %s\n" % PIN, "ambiguous"),
            ("uppercase digest", 0, "V2 Signer: certificate SHA-256 digest: %s\n" % PIN.upper(), "ambiguous"),
            ("truncated digest", 0, "V3.0 Signer: certificate SHA-256 digest: %s\n" % PIN[:63], "ambiguous"),
            ("digest with trailing junk", 0, "Signer #1 certificate SHA-256 digest: %s x\n" % PIN, "ambiguous"),
            ("a second signer, 37.x form", 0, SK37 + "V2 Signer: certificate SHA-256 digest: %s\n" % ("cd" * 32), "multiple"),
            ("a second signer, 34.x form", 0, SK34 + "Signer #2 certificate SHA-256 digest: %s\n" % ("cd" * 32), "multiple"),
            ("a wrong signer, 37.x form", 0, SK37.replace(PIN, "ab" * 32), "wrong"),
            ("a wrong signer, 34.x form", 0, SK34.replace(PIN, "ab" * 32), "wrong"),
        ]
        for why, rc, out, want in cases:
            got = outcome(rc, out)
            check(got == want, "rejects %s: %s (got %s)" % (why, want, got))
        check(outcome(0, SK37 + SK37.replace("V3.0", "V3.1")) == "pass:" + PIN, "the same certificate on V3.0 and V3.1 lines is one distinct signer")
        # 4. the pin: exactly build-tools 37.0.0, never the highest, never a fallback
        sdk = os.path.join(tmp, "sdk")
        image = ["34.0.0", "35.0.0", "35.0.1", "36.0.0", "36.1.0", "37.0.0"]
        fake_sdk(sdk, image + ["99.0.0"])
        check(apksig.build_tool("apksigner", sdk) == os.path.join(sdk, "build-tools", "37.0.0", "apksigner"),
              "with 34.0.0..37.0.0 and a newer 99.0.0 present, the pinned 37.0.0 is used (not the highest)")
        for vers, kw, why in ((image[:-1], {}, "runner image without 37.0.0"), (["37.0.0"], {"rev": "37.0.1"}, "37.0.0 directory whose source.properties says 37.0.1"),
                              (["37.0.0"], {"exe": False}, "37.0.0 apksigner not executable"), ([], {}, "no build-tools at all")):
            fake_sdk(sdk, vers, **kw)
            try:
                apksig.build_tool("apksigner", sdk); check(False, "fails closed: " + why)
            except apksig.SignerError as e:
                check(e.category == "build_tools_missing", "fails closed: %s (%s)" % (why, e))
        old = os.environ.pop("ANDROID_HOME", None); os.environ.pop("ANDROID_SDK_ROOT", None)
        try:
            apksig.build_tool("apksigner"); check(False, "fails closed when ANDROID_HOME is unset")
        except apksig.SignerError:
            check(True, "fails closed when ANDROID_HOME is unset")
        # 5. fetch_swiftkey.verify_split with the real outputs played back by a stand-in apksigner
        apkf = os.path.join(tmp, "s.apk"); open(apkf, "wb").write(b"PK")
        def stand_in(out, rc=0, versions=("37.0.0",)):
            f = os.path.join(tmp, "out.txt"); open(f, "w").write(out)
            fake_sdk(sdk, versions)
            bt = os.path.join(sdk, "build-tools", "37.0.0")
            if os.path.isdir(bt):
                open(os.path.join(bt, "aapt2"), "w").write("#!/bin/sh\necho \"package: name='%s' versionCode='1236402240' versionName='9.13.18.4'\"\n" % F.PKG)
                open(os.path.join(bt, "apksigner"), "w").write("#!/bin/sh\ncat '%s'\nexit %d\n" % (f, rc))
            os.environ["ANDROID_HOME"] = sdk
            try:
                return "pass:" + F.verify_split(apkf)
            except F.FetchError as e:
                return "fail:" + str(e)
        check(stand_in(SK37) == "pass:1236402240", "verify_split() passes the real 37.0.0 output (the run-2 APK)")
        check(stand_in(SK34) == "pass:1236402240", "verify_split() passes the real 34.0.0 output")
        for why, args in (("37.0.0 output with exit 1", (SK37, 1)), ("source stamp only", (stamp37,)),
                          ("pinned build-tools absent (34.0.0..36.1.0 only)", (SK37, 0, image[:-1])),
                          ("two signers", (fx("localkey-two_bt37.0.0.txt"),))):
            r = stand_in(*args)
            check(r.startswith("fail:"), "verify_split() fails closed: %s (%s)" % (why, r[5:120]))
        # 6. check_apk.py uses the same pin and reader
        rel = "ad41ab713027aca87389019721d102d67cacaae349cc625aa14f76240801f7fc"
        def ca(out, rc=0, versions=("37.0.0",)):
            stand_in(out, rc, versions)
            bt = os.path.join(sdk, "build-tools", "37.0.0")
            if os.path.isdir(bt):
                open(os.path.join(bt, "aapt2"), "w").write("#!/bin/sh\necho \"package: name='com.wrenboard.keyboard' versionCode='6' versionName='1.5.0'\"\necho \"native-code: 'x86_64'\"\n")
            try:
                return check_apk.check(apkf, "com.wrenboard.keyboard", "1.5.0", "6", "x86_64", rel)[1]
            except SystemExit as e:
                return ["exit: %s" % e]
        check(ca("V2 Signer: certificate SHA-256 digest: %s\n" % rel) == [], "check_apk: release APK signer in the 37.x form passes")
        check(ca("Signer #1 certificate SHA-256 digest: %s\n" % rel) == [], "check_apk: release APK signer in the 34.x form passes")
        for why, args in (("the pin only as a source stamp", ("Source Stamp Signer: certificate SHA-256 digest: %s\n" % rel,)),
                          ("pinned build-tools absent", ("V2 Signer: certificate SHA-256 digest: %s\n" % rel, 0, image[:-1])),
                          ("two signers", ("V2 Signer #1: certificate SHA-256 digest: %s\nV2 Signer #2: certificate SHA-256 digest: %s\n" % (rel, "cd" * 32),)),
                          ("nonzero exit", ("V2 Signer: certificate SHA-256 digest: %s\n" % rel, 1))):
            p = ca(*args)
            check(bool(p), "check_apk fails: %s (%s)" % (why, "; ".join(p)[:140]))
        if old is not None:
            os.environ["ANDROID_HOME"] = old
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("test_signer: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    if fails:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
