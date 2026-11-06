#!/usr/bin/env python3
"""Offline tests of fetch_swiftkey.py's native x86_64 route (--source apkpure-universal): no network, no
real APK. The mirror answer, the download and the build tools (aapt2, apksigner) are stand-ins; every
check that must fail closed is driven to failure once, and the passing case writes install-list.txt."""
import base64, os, shutil, struct, sys, tempfile, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fetch_swiftkey as F

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)


def url(vc, size, junk=b""):
    c = base64.b64encode(("dev=SwiftKey&t=apk&s=%d&vn=9.0&vc=%s" % (size, vc)).encode()).decode().rstrip("=")
    return ("https://download.pureapk.com/b/APK/X%s?_fn=x&c=1%%7CPERSONALIZATION%%7C%s" % (vc, c)).encode()


def framed(u, junk=b"R\x07"):
    n = len(u)
    pre = bytes([0x32, (n & 0x7f) | 0x80, n >> 7]) if n > 127 else bytes([0x32, n])
    return pre + u + junk


def elf(machine):
    return b"\x7fELF" + bytes([2, 1, 1]) + b"\x00" * 11 + struct.pack("<H", machine) + b"\x00" * 40


def apk(path, libs):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", b"x")
        for n, b in libs.items():
            z.writestr(n, b)


def tools(root, native="'arm64-v8a' 'x86_64'", pkg=F.PKG, vc="1236402240", signer=F.EXPECT):
    bt = os.path.join(root, "build-tools", "99.0.0"); os.makedirs(bt, exist_ok=True)
    for name, body in (("aapt2", "echo \"package: name='%s' versionCode='%s' versionName='9.0'\"\necho \"native-code: %s\"" % (pkg, vc, native)),
                       ("apksigner", "echo 'Signer #1 certificate SHA-256 digest: %s'" % signer)):
        p = os.path.join(bt, name)
        open(p, "w").write("#!/bin/sh\n" + body + "\n"); os.chmod(p, 0o755)
    os.environ["ANDROID_HOME"] = root


def main():
    tmp = tempfile.mkdtemp(prefix="sk-test-")
    try:
        # listing and URL framing
        u = url("1236402240", 1000)
        check(F.listing(u.decode()) == ("1236402240", 1000), "listing() reads versionCode and size from the c= field")
        check(F.listing("https://download.pureapk.com/b/APK/X?c=1") is None, "listing() is None when c= has no fields")
        meta = framed(u) + framed(url("1236336736", 900)) + framed(b"https://download.pureapk.com/b/XAPK/zzz")
        got = F.apk_urls(meta)
        check(got == sorted([u.decode(), url("1236336736", 900).decode()]), "apk_urls() cuts each URL at its length prefix (no trailing 'R')")
        check(F.pick_universal(meta, "1236402240") == (u.decode(), 1000), "pick_universal() takes the one APK for the requested versionCode")
        for m, why in ((meta, "versionCode not listed"), (meta + framed(url("1236402240", 1001)), "two APKs for one versionCode"),
                       (b"", "empty mirror answer")):
            try:
                F.pick_universal(m, "1236402240" if why != "versionCode not listed" else "1")
                check(False, "pick_universal() rejects: " + why)
            except F.FetchError:
                check(True, "pick_universal() rejects: " + why)
        # native-code checks on the bytes
        tools(os.path.join(tmp, "sdk"))
        cases = {"no lib/x86_64": {"lib/arm64-v8a/a.so": elf(183)},
                 "x86_64 dir holds an ARM64 ELF": {"lib/x86_64/a.so": elf(183)},
                 "x86_64 dir holds a 32-bit x86 ELF": {"lib/x86_64/a.so": elf(62)[:4] + b"\x01" + elf(62)[5:]},
                 "x86_64 dir holds a non-ELF file": {"lib/x86_64/a.so": b"not an elf at all, padding padding"},
                 "one good and one ARM file": {"lib/x86_64/a.so": elf(62), "lib/x86_64/b.so": elf(183)}}
        for why, libs in cases.items():
            p = os.path.join(tmp, "c.apk"); apk(p, libs)
            try:
                F.check_native(p, "x86_64"); check(False, "check_native() rejects: " + why)
            except F.FetchError:
                check(True, "check_native() rejects: " + why)
        p = os.path.join(tmp, "good.apk"); apk(p, {"lib/x86_64/a.so": elf(62), "lib/arm64-v8a/a.so": elf(183)})
        check(F.check_native(p, "x86_64") == ["a.so"], "check_native() accepts real x86-64 ELF libraries")
        tools(os.path.join(tmp, "sdk"), native="'arm64-v8a'")
        try:
            F.check_native(p, "x86_64"); check(False, "check_native() rejects aapt2 native-code without x86_64")
        except F.FetchError:
            check(True, "check_native() rejects aapt2 native-code without x86_64")
        # fetch_universal end to end with a stand-in download
        good = open(p, "rb").read()
        def run_case(name, body, size=None, **tk):
            tools(os.path.join(tmp, "sdk"), **tk)
            out = os.path.join(tmp, "out-" + name.replace(" ", "_")); m = framed(url("1236402240", size if size is not None else len(body)))
            def fake(u_, hdr, dest=None):
                check(hdr.get("x-abis") == "x86_64", "[%s] the mirror is asked for x86_64 only" % name)
                if dest is None:
                    return m
                open(dest, "wb").write(body); return len(body)
            F.get_hdr = fake
            try:
                info = F.fetch_universal(out, "x86_64", "1236402240"); return info, out
            except F.FetchError as e:
                return e, out
        info, out = run_case("good", good)
        check(isinstance(info, dict) and open(os.path.join(out, "install-list.txt")).read().strip().endswith("com.touchtype.swiftkey.apk"),
              "fetch_universal() passes a verified native build and writes install-list.txt")
        info2, out2 = run_case("with source stamp", good, signer=F.EXPECT + "'\necho 'Source Stamp Signer certificate SHA-256 digest: " + "32" * 32)
        check(isinstance(info2, dict), "fetch_universal() ignores a Play source-stamp certificate line (not a signer): %s" % (info2 if not isinstance(info2, dict) else "ok"))
        for name, body, size, tk in (("size mismatch", good, len(good) + 1, {}), ("wrong signer", good, None, {"signer": "ab" * 32}),
                                     ("two signers", good, None, {"signer": F.EXPECT + "'\necho 'Signer #2 certificate SHA-256 digest: " + "cd" * 32}),
                                     ("wrong package", good, None, {"pkg": "com.example.other"}),
                                     ("wrong versionCode", good, None, {"vc": "1236336736"}),
                                     ("stamp line only, no signer", good, None, {"signer": "x'\necho 'Source Stamp Signer certificate SHA-256 digest: " + F.EXPECT}),
                                     ("not a zip", b"<html>challenge</html>", None, {})):
            r, out = run_case(name, body, size, **tk)
            check(isinstance(r, F.FetchError) and not os.path.exists(os.path.join(out, "install-list.txt"))
                  and not os.path.exists(os.path.join(out, "com.touchtype.swiftkey.apk")),
                  "fetch_universal() fails closed and deletes the download: %s (%s)" % (name, r))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("test_fetch_swiftkey: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    if fails:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
