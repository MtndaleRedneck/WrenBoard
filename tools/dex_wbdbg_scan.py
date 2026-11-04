#!/usr/bin/env python3
"""DEX const-string site scanner for the WrenBoard 1.5 exact-head exclusion proof.

Implements the accepted metric (rev32 method reference): decoded const-string and
const-string/jumbo INSTRUCTION SITES via the androguard decoded-operand API
(Instruction21c/31c get_string()), never textual get_output parsing. Site identity is
instruction offset + method signature + decoded literal context; R8 synthetic owner
names are unstable and never used for identity.

Modes (tools/dex_marker_policy.txt):
  control  - hooked build positive control: every EXCLUDED_HOOK marker MUST be detected;
             a missing expected hook is a control defect, not a pass.
  final    - cleanup optimized exact head: every EXCLUDED_HOOK and EXCLUDED_LEGACY marker
             MUST be ABSENT from all DEX strings and decoded code; each RETAINED baseline
             literal MUST appear in a method that also loads const-string "WBDBG".

Exit codes: 0 = policy verdict pass; 1 = policy verdict fail; 2 = scanner/dependency/
parse error (fail closed; a scanner error is never a policy result). No continue-on-error.

Usage: dex_wbdbg_scan.py --apk <path> --mode control|final [--policy path]
"""
import argparse, hashlib, io, sys, zipfile

try:
    from loguru import logger
    logger.remove()  # silence androguard debug logs
except Exception:
    pass

def load_policy(path):
    hooks, legacy, retained = [], [], []
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        kind, _, marker = line.partition("\t")
        if kind == "EXCLUDED_HOOK":
            hooks.append(marker)
        elif kind == "EXCLUDED_LEGACY":
            legacy.append(marker)
        elif kind == "RETAINED":
            retained.append(marker)
        else:
            raise ValueError(f"unknown policy row: {line!r}")
    if not hooks or not retained:
        raise ValueError("policy missing EXCLUDED_HOOK or RETAINED rows")
    return hooks, legacy, retained

def dex_blobs(apk):
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".dex"))
        if not names:
            raise ValueError("no classes*.dex in APK")
        for n in names:
            yield n, z.read(n)

def collect_sites(blob):
    """All decoded const-string(-jumbo) sites: (offset, class, method sig, string)."""
    from androguard.core.dex import DEX, Kind
    d = DEX(blob)
    sites = []
    for cls in d.get_classes():
        for m in cls.get_methods():
            code = m.get_code()
            if code is None:
                continue
            off = 0
            sig = f"{cls.get_name()}->{m.get_name()}{m.get_descriptor()}"
            for ins in code.get_bc().get_instructions():
                try:
                    if ins.get_name() in ("const-string", "const-string/jumbo") \
                            and ins.get_kind() == Kind.STRING:
                        sites.append((off, sig, ins.get_string()))
                except Exception as e:
                    raise ValueError(f"decode error at {sig} off={off}: {e}")
                off += ins.get_length()
    all_strings = [str(s) for s in d.get_strings()]
    return sites, all_strings

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--mode", required=True, choices=["control", "final"])
    ap.add_argument("--policy", default="tools/dex_marker_policy.txt")
    a = ap.parse_args()
    try:
        hooks, legacy, retained = load_policy(a.policy)
        apk_sha = hashlib.sha256(open(a.apk, "rb").read()).hexdigest()
        print(f"APK {a.apk} SHA256 {apk_sha}")
        all_sites, all_strings = [], []
        for name, blob in dex_blobs(a.apk):
            print(f"DEX {name} SHA256 {hashlib.sha256(blob).hexdigest()}")
            s, st = collect_sites(blob)
            all_sites += [(name,) + t for t in s]
            all_strings += st
    except Exception as e:
        print(f"SCANNER-ERROR: {e}", file=sys.stderr)
        sys.exit(2)

    fail = False
    def marker_hits(marker):
        in_strings = [s for s in all_strings if marker in s]
        in_code = [(n, off, sig, s) for (n, off, sig, s) in all_sites if marker in s]
        return in_strings, in_code

    if a.mode == "control":
        for mk in hooks:
            ins, inc = marker_hits(mk)
            ok = bool(ins or inc)
            print(f"CONTROL EXCLUDED_HOOK {mk!r}: {'detected' if ok else 'MISSING-CONTROL-DEFECT'} (strings={len(ins)} codesites={len(inc)})")
            fail |= not ok
    else:
        for mk in hooks + legacy:
            ins, inc = marker_hits(mk)
            ok = not ins and not inc
            print(f"FINAL EXCLUDED {mk!r}: {'absent' if ok else 'PRESENT-VIOLATION strings=%d codesites=%d' % (len(ins), len(inc))}")
            fail |= not ok
        # Site metric: distinct (dex, method, instruction OFFSET) sites loading
        # const-string "WBDBG". Merged contexts (one method holding several baseline
        # literals with ONE WBDBG load) count once, by construction.
        wbdbg_sites = [(n, off, sig) for (n, off, sig, s) in all_sites if s == "WBDBG"]
        def site_context(sig):
            return [s for (_, _, sg, s) in all_sites if sg == sig]
        context_of = {}
        for (n, off, sig) in wbdbg_sites:
            ctx = [x for x in site_context(sig) if x != "WBDBG"]
            context_of[(n, off, sig)] = ctx
            bounded = "; ".join(repr(x[:40]) for x in ctx[:6])
            print(f"  WBDBG-SITE dex={n} off={off:#x} {sig} context: {bounded}")
        def context_matches(row, ctx):
            return any(alt in c for alt in row.split("|") for c in ctx)
        matched_sites = 0
        unmatched_sites = []
        for key in wbdbg_sites:
            if any(context_matches(r, context_of[key]) for r in retained):
                matched_sites += 1
            else:
                unmatched_sites.append(key)
        matched_rows = 0
        for r in retained:
            ok = any(context_matches(r, context_of[k]) for k in wbdbg_sites)
            matched_rows += 1 if ok else 0
            if not ok:
                print(f"FINAL RETAINED {r!r}: MISSING (no WBDBG site carries this context)")
                fail = True
        for (n, off, sig) in unmatched_sites:
            print(f"FINAL WBDBG-SITE UNMATCHED dex={n} off={off:#x} {sig}: no retained baseline context - new or changed site, needs review")
            fail = True
        print(f"FINAL RETAINED baseline: {matched_sites} distinct WBDBG sites matched, {matched_rows}/{len(retained)} contexts; exactly 9 sites required, count alone is not proof")
        fail |= len(wbdbg_sites) != 9 or matched_sites != 9
    print("VERDICT:", "FAIL" if fail else "PASS", f"mode={a.mode}")
    sys.exit(1 if fail else 0)

if __name__ == "__main__":
    main()
