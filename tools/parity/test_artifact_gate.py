#!/usr/bin/env python3
"""End-to-end check of the public artifact gate, driven by the real parity.yml.

For each of the four upload sites, this test takes the workflow's own filter step (its `run` command,
with RUNNER_TEMP pointed at a scratch dir and the harness path mapped to this checkout) and the
upload step that follows it, then simulates the GitHub runner: it runs the filter, records the step
outcome, evaluates the upload's `if:` condition for that outcome, and, if the upload would run,
lists the files under the upload `path:`. It asserts that a prohibited, disguised or oversized file
never reaches that upload set, and that a clean output still uploads only allowlisted files.
No network, no adb. Requires PyYAML.
"""
import os, re, shutil, subprocess, sys, tempfile
import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WF = os.path.join(REPO, '.github', 'workflows', 'parity.yml')
fails = []

def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        fails.append(msg)

def sites():
    wf = yaml.safe_load(open(WF))
    for jname, job in wf['jobs'].items():
        steps = job.get('steps', [])
        for i, st in enumerate(steps):
            if 'filter_artifacts.sh' in str(st.get('run', '')):
                up = steps[i + 1]
                yield jname, st, up

def upload_runs(cond, step_id, outcome):
    # Only the shapes this workflow uses: "always() && steps.<id>.outcome == 'success'".
    m = re.fullmatch(r"always\(\) && steps\.(\w+)\.outcome == 'success'", cond.strip())
    if not m:
        return None  # unknown / fail-open shape
    return m.group(1) == step_id and outcome == 'success'

def populate(out, kind):
    os.makedirs(os.path.join(out, 'shots'), exist_ok=True)
    open(os.path.join(out, 'shots', 'glide.png'), 'wb').write(b'\x89PNG\r\n\x1a\nfake')
    open(os.path.join(out, 'results.json'), 'w').write('{"ok": true}')
    if kind == 'prohibited':
        open(os.path.join(out, 'app-debug.apk'), 'wb').write(b'PK\x03\x04apk')
        open(os.path.join(out, 'classes.dex'), 'wb').write(b'dex\n035')
        open(os.path.join(out, 'release.jks'), 'wb').write(b'\xfe\xed\xfe\xed')
        os.symlink('/etc/passwd', os.path.join(out, 'link.txt'))
    elif kind == 'disguised':
        open(os.path.join(out, 'shots', 'notreally.png'), 'wb').write(b'PK\x03\x04disguised apk')
    elif kind == 'oversized':
        with open(os.path.join(out, 'huge.log'), 'wb') as f:
            f.truncate(52428801)

def run_site(jname, st, up, kind):
    tmp = tempfile.mkdtemp(prefix='gate-')
    try:
        env = dict(os.environ, RUNNER_TEMP=tmp, ROLE='candidate')
        os.symlink(REPO, os.path.join(tmp, 'harness'))
        cmd = st['run']
        # out dir the step reads: first quoted $RUNNER_TEMP path in the command
        src = re.search(r'filter_artifacts\.sh "([^"]+)"', cmd).group(1)
        src = src.replace('$RUNNER_TEMP', tmp).replace('$ROLE', 'candidate')
        os.makedirs(src, exist_ok=True)
        populate(src, kind)
        r = subprocess.run(['bash', '-c', 'set -e\n' + cmd], cwd=tmp, env=env,
                           capture_output=True, text=True, timeout=60)
        outcome = 'success' if r.returncode == 0 else 'failure'
        runs = upload_runs(str(up.get('if', '')), st.get('id'), outcome)
        path = up['with']['path'].replace('${{ runner.temp }}', tmp).replace('${{ matrix.role }}', 'candidate')
        uploaded = []
        if runs and os.path.isdir(path):
            for d, _, fs in os.walk(path):
                uploaded += [os.path.relpath(os.path.join(d, f), path) for f in fs]
        # Defence in depth: even if the condition were bypassed, the path must hold nothing bad.
        leaked = []
        if os.path.isdir(path):
            for d, _, fs in os.walk(path):
                for f in fs:
                    fp = os.path.join(d, f)
                    head = open(fp, 'rb').read(2)
                    if head == b'PK' or os.path.getsize(fp) > 52428800 or not re.search(r'\.(png|jpg|mp4|webm|txt|log|json|xml)$', f, re.I):
                        leaked.append(os.path.relpath(fp, path))
        return r.returncode, runs, sorted(uploaded), leaked, path != src
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

n = 0
for jname, st, up in sites():
    n += 1
    tag = f'{jname}/{st.get("id")}'
    check(bool(st.get('id')), f'{tag}: filter step has an id')
    check(upload_runs(str(up.get('if', '')), st.get('id'), 'success') is True, f'{tag}: upload gated on the filter step success ({up.get("if")!r})')
    check(up['with'].get('retention-days') == 7, f'{tag}: retention-days is 7')
    for kind in ('prohibited', 'disguised', 'oversized', 'clean'):
        rc, runs, uploaded, leaked, separate = run_site(jname, st, up, kind)
        check(separate, f'{tag}/{kind}: upload path is not the raw output dir')
        check(not leaked, f'{tag}/{kind}: nothing prohibited/disguised/oversized in upload path (leaked={leaked})')
        if kind == 'prohibited':
            # Disallowed types are excluded (not fatal); only allowlisted files are uploaded.
            check(rc == 0 and runs and uploaded == ['results.json', 'shots/glide.png'], f'{tag}/{kind}: only allowlisted files uploaded ({uploaded}, rc={rc})')
        elif kind in ('disguised', 'oversized'):
            check(rc != 0, f'{tag}/{kind}: filter step fails (rc={rc})')
            check(not runs and uploaded == [], f'{tag}/{kind}: upload step skipped and upload set empty (runs={runs}, uploaded={uploaded})')
        else:
            check(rc == 0 and runs and uploaded == ['results.json', 'shots/glide.png'], f'{tag}/{kind}: clean output uploads ({uploaded})')
check(n == 4, f'four upload sites found ({n})')
print('RESULT', 'FAIL' if fails else 'PASS', f'{len(fails)} failures')
sys.exit(1 if fails else 0)
