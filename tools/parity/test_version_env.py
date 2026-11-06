#!/usr/bin/env python3
"""Regression for the first run's configure failure (versionCode -1): every step of parity.yml that invokes
gradle must run with VERSION_CODE a positive integer and VERSION_NAME = parity-<short app SHA>, the env
names the app's build.gradle reads (and build.yml sets). Static check of the workflow plus a run of the
version-name step and the guard script with good and bad values. No gradle, no network."""
import os, re, subprocess, sys, tempfile
import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)


def bash(script, env, timeout=20):
    e = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}; e.update(env)
    return subprocess.run(["bash", "-c", script], env=e, capture_output=True, text=True, timeout=timeout, cwd=HERE)


def main():
    wf = yaml.safe_load(open(os.path.join(ROOT, ".github", "workflows", "parity.yml")))
    gradle_steps = 0
    set_step = None
    for jn, job in wf["jobs"].items():
        jenv = job.get("env") or {}
        sets_name = False
        for st in job.get("steps", []):
            run = st.get("run") or ""
            if "VERSION_NAME=parity-" in run and "GITHUB_ENV" in run:
                sets_name, set_step = True, run
            if "gradlew" not in run:
                continue
            gradle_steps += 1
            env = dict(jenv, **(st.get("env") or {}))
            name = st.get("name", "?")
            check(re.fullmatch(r"[1-9][0-9]*", str(env.get("VERSION_CODE", ""))) is not None,
                  "%s / %s: VERSION_CODE is a positive integer (%r)" % (jn, name, env.get("VERSION_CODE")))
            check(sets_name, "%s / %s: an earlier step sets VERSION_NAME=parity-<short SHA> in GITHUB_ENV" % (jn, name))
            first = [l.strip() for l in run.splitlines() if l.strip()][0]
            check(first == "bash ../harness/tools/parity/check_version_env.sh",
                  "%s / %s: the step starts with the version-env guard" % (jn, name))
    check(gradle_steps == 2, "parity.yml has exactly the 2 gradle-invoking steps (unit suite, debug build): %d" % gradle_steps)
    bg = os.path.join(ROOT, "build.gradle")
    if os.path.isfile(bg):
        g = open(bg).read()
        check("System.getenv('VERSION_CODE')" in g and "System.getenv('VERSION_NAME')" in g, "build.gradle reads VERSION_CODE and VERSION_NAME")
    by = os.path.join(ROOT, ".github", "workflows", "build.yml")
    if os.path.isfile(by):
        b = yaml.safe_load(open(by))
        check(any("VERSION_CODE" in (j.get("env") or {}) and "VERSION_NAME" in (j.get("env") or {}) for j in b["jobs"].values()),
              "build.yml sets the same two names as job env")
    sha = "4094f96e1e0ce789ce727263fad392482e8f8051"
    with tempfile.NamedTemporaryFile("r", suffix=".env") as ge:
        r = bash(set_step, {"APP_SHA": sha, "GITHUB_ENV": ge.name})
        check(r.returncode == 0 and open(ge.name).read() == "VERSION_NAME=parity-4094f96\n", "version-name step writes VERSION_NAME=parity-4094f96")
    with tempfile.NamedTemporaryFile("r", suffix=".env") as ge:
        r = bash(set_step, {"APP_SHA": "4094f96; echo pwned", "GITHUB_ENV": ge.name})
        check(r.returncode != 0 and open(ge.name).read() == "", "version-name step refuses a non-SHA APP_SHA and writes nothing")
    guard = "bash check_version_env.sh"
    check(bash(guard, {"VERSION_CODE": "1", "VERSION_NAME": "parity-4094f96"}).returncode == 0, "guard accepts VERSION_CODE=1, VERSION_NAME=parity-4094f96")
    for vc, vn, why in (("", "parity-4094f96", "VERSION_CODE unset"), ("-1", "parity-4094f96", "VERSION_CODE -1 (the first run)"),
                        ("0", "parity-4094f96", "VERSION_CODE 0"), ("1x", "parity-4094f96", "VERSION_CODE not an integer"),
                        ("1", "", "VERSION_NAME unset"), ("1", "0.0.0", "VERSION_NAME 0.0.0 (the first run)"),
                        ("1", "parity-4094f96e1e", "VERSION_NAME with a long SHA")):
        env = {}
        if vc: env["VERSION_CODE"] = vc
        if vn: env["VERSION_NAME"] = vn
        check(bash(guard, env).returncode != 0, "guard rejects " + why)
    print("test_version_env: %s (%d failed)" % ("OK" if not fails else "FAILED", len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
