#!/usr/bin/env python3
"""APK discovery regressions (run 2: both app builds said BUILD SUCCESSFUL, then the glob
build/outputs/apk/stable/debug/*.apk found 0 APKs). Each case rebuilds a build/ tree from real AGP 8.10.1
metadata in testdata/agp/ (the real app at f4288fe and a probe with the same plugin versions); the APKs
are small synthetic zips made here (no real APK bytes anywhere). No gradle, no network."""
import glob, json, os, re, shutil, subprocess, sys, tempfile, time, zipfile
import yaml
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import apk_output as AO

fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

FX = os.path.join(HERE, "testdata", "agp")
RED = "intermediates/apk_ide_redirect_file/%s/create%sApkListingFileRedirect/redirect.txt"
APP = "com.wrenboard.keyboard"


def fake_apk(path, libs=("lib/x86_64/libjni_latinime.so",), dex=True, manifest=True):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        if manifest: z.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
        if dex: z.writestr("classes.dex", b"dex\n035\x00")
        for l in libs: z.writestr(l, b"\x7fELF")


def tree(base, case, entries, stamp_ns, apks=None, variant="stableDebug", age=None):
    """entries: {relpath under build/: fixture file in testdata/agp/<case>/}; apks: {relpath: kwargs}.
    age: {relpath: True} marks a file as older than the stamp (stale)."""
    b = os.path.join(base, "build"); shutil.rmtree(b, ignore_errors=True)
    for rel, fxname in entries.items():
        p = os.path.join(b, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
        shutil.copyfile(os.path.join(FX, case, fxname) if not fxname.startswith("=") else os.devnull, p)
        if fxname.startswith("="):
            open(p, "w").write(fxname[1:])
    for rel, kw in (apks or {}).items():
        fake_apk(os.path.join(b, rel), **kw)
    for dp, dn, fn in os.walk(b):
        for f in fn:
            rel = os.path.relpath(os.path.join(dp, f), b)
            t = stamp_ns - 60 * 10**9 if (age and rel in age) else stamp_ns + 5 * 10**9
            os.utime(os.path.join(dp, f), ns=(t, t))
    return b


def outcome(b, stamp_ns, variant="stableDebug", app=APP):
    try:
        apk, ev = AO.resolve(b, variant, app, "x86_64", stamp_ns)
        return "pass:" + ev["location"] + ":" + os.path.basename(apk), ev
    except AO.Discovery as e:
        return e.category, str(e)


def main():
    tmp = tempfile.mkdtemp(prefix="apkdisc-")
    S = time.time_ns()
    red = RED % ("stableDebug", "StableDebug")
    I_META, O_META = "intermediates/apk/stable/debug/output-metadata.json", "outputs/apk/stable/debug/output-metadata.json"
    I_APK, O_APK = "intermediates/apk/stable/debug/app-stable-debug.apk", "outputs/apk/stable/debug/app-stable-debug.apk"
    P_I_APK, P_O_APK = I_APK.replace("app-", "probe-"), O_APK.replace("app-", "probe-")
    try:
        # 1. the demonstrated cases pass
        real = {red: "redirect.txt", I_META: "intermediates_apk_stable_debug_output-metadata.json"}
        b = tree(tmp, "real-f4288fe", real, S, {I_APK: {}})
        got, ev = outcome(b, S)
        check(got == "pass:intermediates:app-stable-debug.apk", "real f4288fe injected-ABI build: resolves intermediates/apk/stable/debug/app-stable-debug.apk (%s)" % got)
        check(glob.glob(os.path.join(b, "outputs/apk/stable/debug/*.apk")) == [], "the same tree: v3.2's glob outputs/apk/stable/debug/*.apk finds nothing (run 2's 'found 0')")
        check(ev.get("version_name") == "parity-f4288fe" and ev.get("native") == ["lib/x86_64/libjni_latinime.so"], "evidence records versionName and the lib/x86_64 libraries")
        if os.path.isdir(os.path.join(FX, "real-4094f96")):
            bname = json.load(open(os.path.join(FX, "real-4094f96", "intermediates_apk_stable_debug_output-metadata.json")))["elements"][0]["outputFile"]
            rb = tree(tmp, "real-4094f96", real, S, {"intermediates/apk/stable/debug/" + bname: {}})
            got, _ = outcome(rb, S)
            check(got == "pass:intermediates:" + bname, "real 4094f96 (baseline) injected-ABI build: resolves the intermediates APK (%s)" % got)
        b = tree(tmp, "probe-injected", {red: "redirect.txt", I_META: "intermediates_apk_stable_debug_output-metadata.json"}, S, {P_I_APK: {}})
        check(outcome(b, S)[0] == "pass:intermediates:probe-stable-debug.apk", "probe, injected ABI: intermediates case")
        b = tree(tmp, "probe-plain", {red: "redirect.txt", O_META: "outputs_apk_stable_debug_output-metadata.json"}, S, {P_O_APK: {}})
        check(outcome(b, S)[0] == "pass:outputs:probe-stable-debug.apk", "probe, no injected ABI: outputs case")
        # 2. stale: the probe built plain, then with the flag, no clean (real redirect and metadata)
        st = {red: "redirect.txt", I_META: "intermediates_apk_stable_debug_output-metadata.json", O_META: "outputs_apk_stable_debug_output-metadata.json"}
        b = tree(tmp, "probe-stale", st, S, {P_I_APK: {}, P_O_APK: {}}, age={O_META: 1, P_O_APK: 1})
        got, ev = outcome(b, S)
        check(got == "pass:intermediates:probe-stable-debug.apk" and sorted(x["path"] for x in ev["stale_ignored"]) == sorted([O_META, P_O_APK]),
              "stale outputs/ APK left by an earlier build is ignored and recorded; the redirect's fresh APK is used (%s)" % got)
        check([os.path.relpath(p, b) for p in glob.glob(os.path.join(b, "outputs/apk/stable/debug/*.apk"))] == [P_O_APK],
              "the same tree: v3.2's glob would have taken the stale outputs/ APK")
        for age, why in (({P_I_APK: 1}, "the chosen APK predates the build stamp"), ({I_META: 1}, "the metadata predates the build stamp"),
                         ({red: 1}, "the redirect predates the build stamp")):
            b = tree(tmp, "probe-stale", st, S, {P_I_APK: {}, P_O_APK: {}}, age=dict(age, **{O_META: 1, P_O_APK: 1}))
            got = outcome(b, S)[0]
            check(got == "stale", "rejects: %s -> stale (%s)" % (why, got))
        # 3. ambiguous
        b = tree(tmp, "probe-stale", st, S, {P_I_APK: {}, P_O_APK: {}})
        check(outcome(b, S)[0] == "ambiguous", "rejects a second APK from this same build in the other variant directory -> ambiguous")
        b = tree(tmp, "real-f4288fe", real, S, {I_APK: {}, I_APK.replace(".apk", "-2.apk"): {}})
        check(outcome(b, S)[0] == "ambiguous", "rejects a second fresh APK beside the listed one -> ambiguous")
        meta = json.load(open(os.path.join(FX, "real-f4288fe", "intermediates_apk_stable_debug_output-metadata.json")))
        def with_meta(m, apks=None):
            e = {red: "redirect.txt", I_META: "=" + json.dumps(m)}
            return tree(tmp, "real-f4288fe", e, S, apks if apks is not None else {I_APK: {}})
        m2 = json.loads(json.dumps(meta)); m2["elements"].append(dict(m2["elements"][0], outputFile="other.apk"))
        check(outcome(with_meta(m2), S)[0] == "ambiguous", "rejects metadata listing two elements -> ambiguous")
        m2 = json.loads(json.dumps(meta)); m2["elements"][0]["type"] = "ONE_OF_MANY"
        check(outcome(with_meta(m2), S)[0] == "ambiguous", "rejects a ONE_OF_MANY (split) element -> ambiguous")
        m2 = json.loads(json.dumps(meta)); m2["elements"] = []
        check(outcome(with_meta(m2), S)[0] == "ambiguous", "rejects metadata with no element -> ambiguous")
        b = tree(tmp, "real-f4288fe", {red: "=#- File Locator -\nlistingFile=../../../apk/stable/debug/output-metadata.json\nlistingFile=../../../../outputs/apk/stable/debug/output-metadata.json\n",
                                        I_META: "intermediates_apk_stable_debug_output-metadata.json"}, S, {I_APK: {}})
        check(outcome(b, S)[0] == "bad_metadata", "rejects a redirect with two listingFile lines -> bad_metadata")
        # 4. wrong variant (real unstable metadata)
        un = {RED % ("unstableDebug", "UnstableDebug"): "redirect.txt", "intermediates/apk/unstable/debug/output-metadata.json": "intermediates_apk_unstable_debug_output-metadata.json"}
        b = tree(tmp, "probe-unstable", un, S, {"intermediates/apk/unstable/debug/probe-unstable-debug.apk": {}})
        check(outcome(b, S)[0] == "missing", "an unstableDebug-only build has no stableDebug redirect -> missing")
        check(outcome(b, S, "unstableDebug", APP + ".unstable")[0] == "pass:intermediates:probe-unstable-debug.apk", "the same tree resolves when unstableDebug is the variant asked for (the redirect is variant-bound)")
        b = tree(tmp, "probe-unstable", {red: "=#- File Locator -\nlistingFile=../../../apk/unstable/debug/output-metadata.json\n",
                                          "intermediates/apk/unstable/debug/output-metadata.json": "intermediates_apk_unstable_debug_output-metadata.json"}, S,
                 {"intermediates/apk/unstable/debug/probe-unstable-debug.apk": {}})
        check(outcome(b, S)[0] == "wrong_variant", "rejects a stableDebug redirect pointing at the unstable directory -> wrong_variant")
        b = tree(tmp, "probe-unstable", {red: "redirect.txt", I_META: "intermediates_apk_unstable_debug_output-metadata.json"}, S, {I_APK: {}})
        shutil.copyfile(os.path.join(FX, "real-f4288fe", "redirect.txt"), os.path.join(b, red)); os.utime(os.path.join(b, red), ns=(S + 10**9, S + 10**9))
        check(outcome(b, S)[0] == "wrong_variant", "rejects the real unstableDebug metadata (applicationId .unstable) in the stable directory -> wrong_variant")
        m2 = json.loads(json.dumps(meta)); m2["variantName"] = "stableRelease"
        check(outcome(with_meta(m2), S)[0] == "wrong_variant", "rejects metadata whose variantName is not stableDebug -> wrong_variant")
        # 5. wrong ABI
        m2 = json.loads(json.dumps(meta)); m2["elements"][0]["filters"] = [{"filterType": "ABI", "value": "arm64-v8a"}]
        check(outcome(with_meta(m2), S)[0] == "wrong_abi", "rejects an element filtered to arm64-v8a -> wrong_abi")
        m2 = json.loads(json.dumps(meta)); m2["elements"][0]["filters"] = [{"filterType": "ABI", "value": "x86_64"}]
        check(outcome(with_meta(m2), S)[0].startswith("pass"), "accepts an element filtered to exactly ABI x86_64")
        for libs, why in ((("lib/x86_64/a.so", "lib/arm64-v8a/a.so"), "an APK also carrying lib/arm64-v8a (the flag did not take)"),
                          (("lib/arm64-v8a/a.so",), "an APK with arm64 code only"), ((), "an APK with no native code")):
            b = tree(tmp, "real-f4288fe", real, S, {I_APK: {"libs": libs}})
            check(outcome(b, S)[0] == "wrong_abi", "rejects %s -> wrong_abi" % why)
        # 6. not an APK
        m2 = json.loads(json.dumps(meta)); m2["elements"][0]["outputFile"] = "app-stable-debug.aab"
        check(outcome(with_meta(m2, {}), S)[0] == "not_apk", "rejects an outputFile that is not *.apk -> not_apk")
        b = tree(tmp, "real-f4288fe", dict(real, **{I_APK: "=<html>not a zip</html>"}), S)
        check(outcome(b, S)[0] == "not_apk", "rejects an outputFile that is not a zip -> not_apk")
        b = tree(tmp, "real-f4288fe", real, S, {I_APK: {"dex": False}})
        check(outcome(b, S)[0] == "not_apk", "rejects a zip without classes.dex -> not_apk")
        m2 = json.loads(json.dumps(meta)); m2["artifactType"]["type"] = "BUNDLE"
        check(outcome(with_meta(m2), S)[0] == "bad_metadata", "rejects non-APK artifact metadata -> bad_metadata")
        # 7. traversal
        for tgt, why in (("../../../../../../../etc/output-metadata.json", "a listingFile climbing out of build/"),
                         ("/etc/output-metadata.json", "an absolute listingFile"),
                         ("../../../apk/stable/debug/../../../../outputs/apk/stable/debug/x/../../../../../../x/output-metadata.json", "a listingFile that normalises outside the variant dirs")):
            b = tree(tmp, "real-f4288fe", {red: "=#- File Locator -\nlistingFile=%s\n" % tgt, I_META: "intermediates_apk_stable_debug_output-metadata.json"}, S, {I_APK: {}})
            got = outcome(b, S)[0]
            check(got in ("traversal", "wrong_variant"), "rejects %s -> %s" % (why, got))
        m2 = json.loads(json.dumps(meta)); m2["elements"][0]["outputFile"] = "../../../../outputs/x.apk"
        check(outcome(with_meta(m2), S)[0] == "traversal", "rejects an outputFile with a path -> traversal")
        b = tree(tmp, "real-f4288fe", real, S, {})
        ext = os.path.join(tmp, "elsewhere.apk"); fake_apk(ext); os.utime(ext, ns=(S + 10**9, S + 10**9))
        os.symlink(ext, os.path.join(b, I_APK))
        check(outcome(b, S)[0] == "traversal", "rejects a symlinked APK -> traversal")
        b = tree(tmp, "real-f4288fe", {red: "redirect.txt"}, S)
        alt = os.path.join(tmp, "alt"); shutil.rmtree(alt, ignore_errors=True); os.makedirs(alt)
        shutil.copyfile(os.path.join(FX, "real-f4288fe", "intermediates_apk_stable_debug_output-metadata.json"), os.path.join(alt, "output-metadata.json"))
        fake_apk(os.path.join(alt, "app-stable-debug.apk"))
        os.makedirs(os.path.join(b, "intermediates/apk/stable"), exist_ok=True); os.symlink(alt, os.path.join(b, "intermediates/apk/stable/debug"))
        check(outcome(b, S)[0] == "traversal", "rejects a symlinked variant directory -> traversal")
        b = tree(tmp, "real-f4288fe", {I_META: "intermediates_apk_stable_debug_output-metadata.json"}, S, {I_APK: {}})
        check(outcome(b, S)[0] == "missing", "rejects a build with no listing redirect -> missing (no fallback search)")
        # 8. the CLI: copy, evidence, and nothing left behind on failure
        b = tree(tmp, "real-f4288fe", real, S, {I_APK: {}})
        stamp = os.path.join(tmp, "build.stamp"); open(stamp, "w").close(); os.utime(stamp, ns=(S, S))
        dest, evj = os.path.join(tmp, "app-debug.apk"), os.path.join(tmp, "ev.json")
        def cli(*extra):
            return subprocess.run([sys.executable, os.path.join(HERE, "apk_output.py"), "--build-dir", b, "--variant", "stableDebug",
                                   "--application-id", APP, "--abi", "x86_64", "--not-before", stamp, "--copy-to", dest, "--json", evj] + list(extra),
                                  capture_output=True, text=True, timeout=60)
        r = cli(); e = json.load(open(evj))
        check(r.returncode == 0 and os.path.isfile(dest) and e["ok"] and e["sha256"] == AO.sha256(os.path.join(b, I_APK)),
              "apk_output.py copies the resolved APK and records its sha256 (exit %d)" % r.returncode)
        r = cli()
        check(r.returncode == 1 and "already exists" in r.stdout, "apk_output.py refuses to overwrite an existing destination")
        os.remove(dest); os.utime(os.path.join(b, I_APK), ns=(S - 10**10, S - 10**10)); r = cli()
        check(r.returncode == 1 and not os.path.exists(dest) and json.load(open(evj))["category"] == "stale", "apk_output.py exits 1, writes no copy and records the category on failure")
        # 9. the workflow uses it, with the flag kept and no glob
        wf = yaml.safe_load(open(os.path.join(ROOT, ".github", "workflows", "parity.yml")))
        steps = [s for s in wf["jobs"]["app"]["steps"] if "assembleStableDebug" in s.get("run", "")]
        run = steps[0]["run"] if len(steps) == 1 else ""
        lines = run.splitlines()
        gi = [i for i, l in enumerate(lines) if "./gradlew" in l]
        ti = [i for i, l in enumerate(lines) if "build.stamp" in l and "touch" in l]
        ai = [i for i, l in enumerate(lines) if "apk_output.py" in l]
        check(len(steps) == 1 and "-Pandroid.injected.build.abi=x86_64" in run, "the build step keeps -Pandroid.injected.build.abi=x86_64")
        check("*.apk" not in run and "nullglob" not in run and "find " not in run, "the build step has no APK glob or find")
        check(len(gi) == 1 and len(ti) == 1 and len(ai) == 1 and ti[0] < gi[0] < ai[0], "stamp, then gradle, then apk_output.py, in that order")
        check(ai and all(x in lines[ai[0]] for x in ("--variant stableDebug", "--application-id com.wrenboard.keyboard", "--abi x86_64", "--copy-to \"$RUNNER_TEMP/app/app-debug.apk\"")),
              "apk_output.py is bound to stableDebug, com.wrenboard.keyboard and x86_64")
        check("check_apk.py \"$RUNNER_TEMP/app/app-debug.apk\" --package com.wrenboard.keyboard --abi x86_64" in run, "check_apk.py still checks the copied APK")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("test_apk_output: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    if fails:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
