#!/usr/bin/env python3
"""Fetch Microsoft SwiftKey (com.touchtype.swiftkey) at run time as the reference keyboard.

The download comes from APKPure, a third-party mirror (a disclosed source; the certificate check
below proves the bytes are SwiftKey's signed build, not anything about the mirror's terms).
Everything stays in $RUNNER_TEMP/sk, outside the workspace; nothing here is committed or uploaded.

Fails closed (exit 1) unless: the mirror names a download, the bundle's manifest names
com.touchtype.swiftkey, the base APK and an ABI split for the emulator are present, every APK that
will be installed has package com.touchtype.swiftkey, one shared versionCode, and exactly one signer
equal to SwiftKey's certificate. Writes install-list.txt only after every check passes.
Usage: fetch_swiftkey.py [--abi x86_64] [--selftest]
"""
import argparse, glob, json, os, re, subprocess, sys, urllib.parse, urllib.request, zipfile

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
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        raise FetchError("ANDROID_HOME is not set")
    c = sorted(glob.glob(os.path.join(root, "build-tools", "*", name)),
               key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.split(os.sep)[-2])])
    if not c:
        raise FetchError("%s not found" % name)
    return c[-1]


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
    signers = sorted(set(s.lower() for s in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", out)))
    if rc != 0 or signers != [EXPECT]:
        raise FetchError("%s: signer %s (rc %d), expected exactly %s" % (os.path.basename(path), signers, rc, EXPECT))
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


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--abi", default="x86_64", choices=["x86_64", "arm64-v8a"])
    a.add_argument("--selftest", action="store_true")
    o = a.parse_args()
    if o.selftest:
        selftest(); return 0
    out = os.path.join(os.environ.get("RUNNER_TEMP") or sys.exit("RUNNER_TEMP is not set"), "sk")
    try:
        info = fetch(out, o.abi)
    except (FetchError, OSError, ValueError, subprocess.TimeoutExpired) as e:
        print("::error::SwiftKey fetch failed: %s" % e)
        if os.path.exists(os.path.join(out, "install-list.txt")):
            os.remove(os.path.join(out, "install-list.txt"))
        return 1
    print("SWIFTKEY READY %s" % json.dumps(info))
    return 0


if __name__ == "__main__":
    sys.exit(main())
