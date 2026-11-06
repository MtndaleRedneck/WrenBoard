#!/usr/bin/env python3
"""Verify an APK's identity before it is installed: package, version, native ABI and signer.
Prints the readback and exits 1 on any mismatch, missing tool or unreadable APK.
Usage: check_apk.py APK --package P [--version-name V] [--version-code C] [--abi A] [--signer SHA256]
       check_apk.py --release TAG --dir DIR     (exactly one file named as in release-expect.json)
"""
import argparse, glob, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import apksig


def build_tool(name):
    """The pinned build-tools copy (apksig.BUILD_TOOLS); a missing pin is a failed check, never a fallback."""
    try:
        return apksig.build_tool(name)
    except apksig.SignerError as e:
        raise SystemExit("APK CHECK FAIL: %s" % e)


def readback(apk):
    b = subprocess.run([build_tool("aapt2"), "dump", "badging", apk], capture_output=True, text=True, timeout=120)
    s = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", apk], capture_output=True, text=True, timeout=120)
    info = {"badging_rc": b.returncode, "verify_rc": s.returncode}
    m = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", b.stdout)
    if m:
        info.update(package=m.group(1), version_code=m.group(2), version_name=m.group(3))
    m = re.search(r"native-code: (.*)", b.stdout)
    info["abis"] = re.findall(r"'([^']+)'", m.group(1)) if m else []
    # Strict reader shared with fetch_swiftkey.py: both apksigner forms, the source stamp never counted,
    # exactly one distinct app signer (else signers is empty and signer_error says why).
    try:
        info["signers"] = [apksig.app_signer(s.returncode, s.stdout)[0]]
    except apksig.SignerError as e:
        info["signers"], info["signer_error"] = [], str(e)
    return info


def check(apk, package, version_name=None, version_code=None, abi=None, signer=None):
    if not os.path.isfile(apk) or os.path.getsize(apk) == 0:
        return {"apk": os.path.basename(apk)}, ["APK %s is missing or empty" % apk]
    info = readback(apk)
    info["apk"] = os.path.basename(apk)
    p = []
    if info["badging_rc"] != 0:
        p.append("aapt2 could not read the APK")
    if info["verify_rc"] != 0:
        p.append("apksigner verify failed")
    if info.get("package") != package:
        p.append("package %r, expected %r" % (info.get("package"), package))
    if version_name is not None and info.get("version_name") != version_name:
        p.append("versionName %r, expected %r" % (info.get("version_name"), version_name))
    if version_code is not None and info.get("version_code") != version_code:
        p.append("versionCode %r, expected %r" % (info.get("version_code"), version_code))
    if abi is not None and info["abis"] and abi not in info["abis"]:
        p.append("native code %s lacks %s" % (info["abis"], abi))
    if abi is not None and not info["abis"]:
        p.append("no native-code line; cannot confirm %s" % abi)
    if signer is not None and info["signers"] != [signer.lower()]:
        p.append("signer %s, expected exactly %s%s" % (info["signers"], signer.lower(),
                 " (%s)" % info["signer_error"] if info.get("signer_error") else ""))
    return info, p


def main():
    a = argparse.ArgumentParser()
    a.add_argument("apk", nargs="?")
    a.add_argument("--package"); a.add_argument("--version-name"); a.add_argument("--version-code")
    a.add_argument("--abi"); a.add_argument("--signer")
    a.add_argument("--release"); a.add_argument("--dir"); a.add_argument("--json")
    o = a.parse_args()
    if o.release:
        exp = json.load(open(os.path.join(HERE, "release-expect.json"))).get(o.release)
        if not exp:
            print("RELEASE CHECK FAIL: %s is not listed in release-expect.json" % o.release); return 1
        files = sorted(os.listdir(o.dir)) if o.dir and os.path.isdir(o.dir) else []
        if files != [exp["asset"]]:
            print("RELEASE CHECK FAIL: expected exactly [%s], found %s" % (exp["asset"], files)); return 1
        info, p = check(os.path.join(o.dir, exp["asset"]), exp["package"], exp["version_name"], exp["version_code"], exp["abi"], exp["signer_sha256"])
    else:
        if not o.apk or not o.package:
            a.error("APK and --package are required")
        info, p = check(o.apk, o.package, o.version_name, o.version_code, o.abi, o.signer)
    info["problems"] = p
    print(json.dumps(info, indent=1))
    if o.json:
        with open(o.json, "w") as f:
            json.dump(info, f, indent=1)
    print("APK CHECK: %s" % ("PASS" if not p else "FAIL: " + "; ".join(p)))
    return 0 if not p else 1


if __name__ == "__main__":
    sys.exit(main())
