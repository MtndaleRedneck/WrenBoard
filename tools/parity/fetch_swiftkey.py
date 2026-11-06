#!/usr/bin/env python3
"""Fetch Microsoft SwiftKey (com.touchtype.swiftkey) at run time as the reference keyboard.

The download comes from APKPure, a third-party mirror (a disclosed source; the certificate check
below proves the bytes are SwiftKey's signed build, not anything about the mirror's terms).
Everything stays in $RUNNER_TEMP/sk, outside the workspace; nothing here is committed or uploaded.

Fails closed (exit 1) unless: the mirror names a download, the bundle's manifest names
com.touchtype.swiftkey, the base APK and an ABI split for the emulator are present, every APK that
will be installed has package com.touchtype.swiftkey, one shared versionCode, and exactly one signer
equal to SwiftKey's certificate (apksig.py: pinned build-tools 37.0.0, both apksigner output forms).
Writes install-list.txt only after every check passes.

--source apkpure-universal (the native x86_64 route): the same mirror API asked for x86_64 only lists
plain single APKs. The option takes exactly the one listed APK with the requested --version-code, and
fails closed unless its size matches the listing, its package and versionCode are SwiftKey's and the
requested ones, aapt2 lists x86_64 native code, the APK holds at least one lib/x86_64/*.so and every
such file is an ELF64 x86-64 object (checked from the bytes, not the file name), and it carries exactly
one signer equal to the same certificate pin. No ARM-translated or ABI-less build can pass.
Usage: fetch_swiftkey.py [--abi x86_64] [--source xapk|apkpure-universal] [--version-code N] [--selftest]
"""
import argparse, base64, glob, json, os, re, struct, subprocess, sys, urllib.parse, urllib.request, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apksig

PKG = "com.touchtype.swiftkey"
EXPECT = "0ad0088dfb347a8a515f2d13b17a561d5c3f9773438a207241bae7483c99b76f"  # TouchType Limited
META = "https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=" + PKG
HOSTS = ("download.pureapk.com",)
HDR = {"x-sv": "29", "x-abis": "x86_64,arm64-v8a", "x-gp": "1", "User-Agent": "okhttp/4.9.3"}
OPTIONAL = ("config.en.apk", "config.xxhdpi.apk", "LanguagesBundled.apk", "LanguagesBundled.config.en.apk")


class FetchError(Exception):
    pass


def choose(names, abi):
    """The exact install set: base + ABI split are required, the rest only if present."""
    names = set(names)
    base, split = PKG + ".apk", "config.%s.apk" % abi.replace("-", "_")
    missing = [n for n in (base, split) if n not in names]
    if missing:
        raise FetchError("bundle lacks required %s (has %s)" % (missing, sorted(n for n in names if n.endswith(".apk"))))
    return [base, split] + [n for n in OPTIONAL if n in names]


def tool(name):
    """The pinned build-tools copy of NAME (apksig.BUILD_TOOLS); fails closed when it is not present."""
    try:
        return apksig.build_tool(name)
    except apksig.SignerError as e:
        raise FetchError(str(e))


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    return r.returncode, r.stdout + r.stderr


def verify_split(path, version_code=None):
    """Return the split's versionCode; raise unless package and signer are exactly SwiftKey's."""
    rc, out = run([tool("aapt2"), "dump", "badging", path])
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)'", out)
    if rc != 0 or not m:
        raise FetchError("%s: aapt2 found no package name and numeric versionCode" % os.path.basename(path))
    if m.group(1) != PKG:
        raise FetchError("%s: package %s, expected %s" % (os.path.basename(path), m.group(1), PKG))
    if version_code is not None and m.group(2) != version_code:
        raise FetchError("%s: versionCode %s, base is %s" % (os.path.basename(path), m.group(2), version_code))
    rc, out = run([tool("apksigner"), "verify", "--print-certs", path])
    # apksig.check_signer: the 34.x ("Signer #N certificate") and 37.x ("V2 Signer:", "V3.0 Signer:")
    # app-signer forms, exactly one distinct app signer equal to the pin; the Play source stamp ("Source
    # Stamp Signer") is never a signer; nonzero exit, unrecognised, missing or multiple signers fail.
    try:
        apksig.check_signer(rc, out, EXPECT)
    except apksig.SignerError as e:
        raise FetchError("%s: signer check failed (build-tools %s): %s" % (os.path.basename(path), apksig.BUILD_TOOLS, e))
    return m.group(2)


def get(url, dest=None):
    u = urllib.parse.urlparse(url)
    if u.scheme != "https" or (dest is not None and u.hostname not in HOSTS):
        raise FetchError("refusing download from %s" % u.hostname)
    with urllib.request.urlopen(urllib.request.Request(url, headers=HDR), timeout=300) as r:
        if dest is None:
            return r.read(2 << 20)
        n = 0
        with open(dest, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                n += len(b)
                if n > 600 << 20:
                    raise FetchError("download larger than 600 MB")
                f.write(b)
        return n


def fetch(out, abi):
    os.makedirs(out, exist_ok=True)
    meta = get(META)
    urls = [x.decode() for x in re.findall(rb"https://download\.pureapk\.com/b/XAPK/[\x21-\x7e]+", meta)]
    if not urls:
        raise FetchError("the mirror named no XAPK download for %s" % PKG)
    xapk = os.path.join(out, "sk.xapk")
    print("downloaded %d bytes" % get(urls[0], xapk), flush=True)
    try:
        with zipfile.ZipFile(xapk) as z:
            names = z.namelist()
            if "manifest.json" not in names:
                raise FetchError("bundle has no manifest.json")
            man = json.loads(z.read("manifest.json"))
            if man.get("package_name") != PKG:
                raise FetchError("bundle manifest names %r" % man.get("package_name"))
            keep = choose(names, abi)
            for n in keep:
                z.extract(n, out)
    except zipfile.BadZipFile as e:
        raise FetchError("download is not a valid bundle: %s" % e)
    finally:
        os.remove(xapk)
    paths = [os.path.join(out, n) for n in keep]
    vc = verify_split(paths[0])
    for p in paths[1:]:
        verify_split(p, vc)
    for p in paths:
        print("CERT OK %s" % os.path.basename(p))
    with open(os.path.join(out, "install-list.txt"), "w") as f:
        f.write("\n".join(paths) + "\n")
    info = {"package": PKG, "version_name": man.get("version_name"), "version_code": vc,
            "splits": keep, "signer_sha256": EXPECT, "source": "APKPure mirror"}
    with open(os.path.join(out, "sk-version.json"), "w") as f:
        json.dump(info, f, indent=1)
    return info


UNI_HDR = dict(HDR, **{"x-abis": "x86_64"})
APK_PREFIX = b"https://download.pureapk.com/b/APK/"
EM_X86_64 = 62


def listing(url):
    """(versionCode, size) the mirror states for an APK download URL, from its c= field; None if absent."""
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query).get("c", [""])[0].split("|")
    if len(q) < 3:
        return None
    try:
        f = urllib.parse.parse_qs(base64.b64decode(q[2] + "=" * (-len(q[2]) % 4)).decode("ascii", "replace"))
    except (ValueError, UnicodeError):
        return None
    vc, sz = f.get("vc", [""])[0], f.get("s", [""])[0]
    return (vc, int(sz)) if vc.isdigit() and sz.isdigit() else None


def apk_urls(meta):
    """Plain-APK download URLs in the mirror's protobuf answer, each cut at its own length prefix (a
    printable-character scan runs into the next field's tag byte, e.g. a trailing 'R')."""
    out, i = [], meta.find(APK_PREFIX)
    while i != -1:
        n = None
        if i >= 2 and meta[i - 2] & 0x80 and not meta[i - 1] & 0x80:
            n = (meta[i - 2] & 0x7f) | (meta[i - 1] << 7)
        elif i >= 1 and not meta[i - 1] & 0x80:
            n = meta[i - 1]
        u = meta[i:i + n] if n else b""
        if n and len(u) == n and re.fullmatch(rb"[\x21-\x7e]+", u):
            out.append(u.decode())
        i = meta.find(APK_PREFIX, i + 1)
    return sorted(set(out))


def pick_universal(meta, version_code):
    """The one APK URL listed for version_code; fails closed on none or several."""
    hits = []
    for u in apk_urls(meta):
        l = listing(u)
        if l and l[0] == version_code:
            hits.append((u, l[1]))
    if len(hits) != 1:
        raise FetchError("the mirror lists %d plain APKs for versionCode %s (need exactly 1)" % (len(hits), version_code))
    return hits[0]


def check_native(path, abi):
    """Raise unless the APK holds lib/<abi>/*.so and every one is an ELF64 object for that machine."""
    if abi != "x86_64":
        raise FetchError("the universal route checks x86_64 only")
    try:
        with zipfile.ZipFile(path) as z:
            libs = [n for n in z.namelist() if n.startswith("lib/x86_64/") and n.endswith(".so")]
            if not libs:
                raise FetchError("%s holds no lib/x86_64/*.so" % os.path.basename(path))
            bad = []
            for n in libs:
                h = z.open(n).read(20)
                if len(h) < 20 or h[:4] != b"\x7fELF" or h[4] != 2 or struct.unpack("<H", h[18:20])[0] != EM_X86_64:
                    bad.append(n)
            if bad:
                raise FetchError("not ELF64 x86-64: %s" % bad[:5])
    except zipfile.BadZipFile as e:
        raise FetchError("download is not a valid APK: %s" % e)
    rc, out = run([tool("aapt2"), "dump", "badging", path])
    nc = re.search(r"^native-code: (.*)$", out, re.M)
    if rc != 0 or not nc or "'x86_64'" not in nc.group(1):
        raise FetchError("aapt2 lists no x86_64 native code (%s)" % (nc.group(1) if nc else "none"))
    return sorted(os.path.basename(n) for n in libs)


def fetch_universal(out, abi, version_code):
    os.makedirs(out, exist_ok=True)
    meta = get_hdr(META, UNI_HDR)
    url, size = pick_universal(meta, version_code)
    apk = os.path.join(out, "com.touchtype.swiftkey.apk")
    try:
        n = get_hdr(url, UNI_HDR, apk)
        print("downloaded %d bytes" % n, flush=True)
        if n != size:
            raise FetchError("downloaded %d bytes, the listing states %d" % (n, size))
        libs = check_native(apk, abi)
        vc = verify_split(apk)
        if vc != version_code:
            raise FetchError("versionCode %s, requested %s" % (vc, version_code))
    except BaseException:
        if os.path.exists(apk):
            os.remove(apk)
        raise
    print("CERT OK %s" % os.path.basename(apk))
    with open(os.path.join(out, "install-list.txt"), "w") as f:
        f.write(apk + "\n")
    info = {"package": PKG, "version_code": vc, "splits": [os.path.basename(apk)], "x86_64_libs": libs,
            "signer_sha256": EXPECT, "sha256": sha256(apk), "bytes": n, "source": "APKPure mirror, plain universal APK"}
    with open(os.path.join(out, "sk-version.json"), "w") as f:
        json.dump(info, f, indent=1)
    return info


def sha256(p):
    import hashlib
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def get_hdr(url, hdr, dest=None):
    """get() with the given request headers (the universal route asks the mirror for x86_64 only)."""
    global HDR
    old, HDR = HDR, hdr
    try:
        return get(url, dest)
    finally:
        HDR = old


def selftest():
    ok = choose(["manifest.json", PKG + ".apk", "config.x86_64.apk", "config.en.apk"], "x86_64")
    assert ok == [PKG + ".apk", "config.x86_64.apk", "config.en.apk"], ok
    for bad in ([], ["manifest.json"], [PKG + ".apk"], ["config.x86_64.apk", "config.en.apk"],
                [PKG + ".apk", "config.arm64_v8a.apk"]):
        try:
            choose(bad, "x86_64")
        except FetchError:
            continue
        raise AssertionError("accepted %s" % bad)
    print("fetch_swiftkey selftest: 1 accepted, 5 rejected as expected")
    import test_fetch_swiftkey
    test_fetch_swiftkey.main()


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--abi", default="x86_64", choices=["x86_64", "arm64-v8a"])
    a.add_argument("--source", default="xapk", choices=["xapk", "apkpure-universal"])
    a.add_argument("--version-code", default="")
    a.add_argument("--selftest", action="store_true")
    o = a.parse_args()
    if o.selftest:
        selftest(); return 0
    if o.source == "apkpure-universal" and not re.fullmatch(r"[1-9][0-9]{0,11}", o.version_code):
        a.error("--source apkpure-universal needs --version-code (a positive integer)")
    out = os.path.join(os.environ.get("RUNNER_TEMP") or sys.exit("RUNNER_TEMP is not set"), "sk")
    try:
        info = fetch(out, o.abi) if o.source == "xapk" else fetch_universal(out, o.abi, o.version_code)
    except (FetchError, OSError, ValueError, subprocess.TimeoutExpired) as e:
        print("::error::SwiftKey fetch failed: %s" % e)
        if os.path.exists(os.path.join(out, "install-list.txt")):
            os.remove(os.path.join(out, "install-list.txt"))
        return 1
    print("SWIFTKEY READY %s" % json.dumps(info))
    return 0


if __name__ == "__main__":
    sys.exit(main())
