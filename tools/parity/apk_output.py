#!/usr/bin/env python3
"""Find the APK a just-finished `assemble<Variant>` produced, from AGP's own variant-bound output
metadata, and copy exactly that file. Replaces v3.2's glob build/outputs/apk/stable/debug/*.apk, which
found nothing in run 2: with -Pandroid.injected.build.abi=x86_64 AGP 8.10.1 writes the APK under
build/intermediates/apk/<flavor>/<type>/ (reproduced with a probe and with the real app at f4288fe;
testdata/agp/), and without the flag under build/outputs/apk/<flavor>/<type>/.

The chain, with no search beyond two fixed directories:
  1. build/intermediates/apk_ide_redirect_file/<variant>/create<Variant>ApkListingFileRedirect/redirect.txt
     (AGP's listing redirect) names exactly one listingFile=<relative path>;
  2. that path must resolve, without symlinks, to one of the two variant directories'
     output-metadata.json (outputs/apk/<flavor>/<type>/ or intermediates/apk/<flavor>/<type>/);
  3. the metadata must be version 3, artifactType APK, the expected applicationId and variantName,
     elementType File and exactly one SINGLE element, no filter other than ABI=<abi>, and an outputFile
     that is a plain *.apk file name in that same directory;
  4. that file must be a regular non-symlink zip with AndroidManifest.xml and classes.dex whose native
     libraries are only lib/<abi>/*.so (at least one);
  5. redirect, metadata and APK must all be newer than --not-before (a stamp written just before
     gradle), and the other variant directory must hold no metadata or APK that new (else ambiguous).
Anything else exits 1 with a category: missing, stale, ambiguous, wrong_variant, wrong_abi, not_apk,
traversal, bad_metadata. Evidence (paths relative to the build directory, sizes, sha256) goes to --json.
Usage: apk_output.py --build-dir build --variant stableDebug --application-id ID --abi x86_64
                     --not-before STAMP --copy-to DEST [--json EVIDENCE]
"""
import argparse, hashlib, json, os, re, shutil, stat, sys, zipfile

VARIANT = re.compile(r"^([a-z][a-z0-9]{0,30})(Debug|Release)$")
APK_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,120}\.apk$")
APP_ID = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
ABIS = ("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
MAX_SMALL = 65536


class Discovery(Exception):
    def __init__(self, category, detail):
        Exception.__init__(self, "%s: %s" % (category, detail))
        self.category = category


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def regular(path, what, root):
    """lstat-check that path is a regular file reached with no symlink anywhere under root."""
    rel = os.path.relpath(path, root)
    if rel.startswith("..") or os.path.isabs(rel):
        raise Discovery("traversal", "%s %s is outside the build directory" % (what, path))
    cur = root
    for part in rel.split(os.sep):
        cur = os.path.join(cur, part)
        try:
            st = os.lstat(cur)
        except FileNotFoundError:
            raise Discovery("missing", "%s %s does not exist" % (what, rel))
        if stat.S_ISLNK(st.st_mode):
            raise Discovery("traversal", "%s path component %s is a symlink" % (what, os.path.relpath(cur, root)))
    if not stat.S_ISREG(st.st_mode):
        raise Discovery("missing", "%s %s is not a regular file" % (what, rel))
    return st


def resolve(build_dir, variant, app_id, abi, not_before_ns):
    m = VARIANT.match(variant or "")
    if not m:
        raise ValueError("variant must be <flavor>Debug|Release, got %r" % variant)
    if abi not in ABIS or not APP_ID.match(app_id or ""):
        raise ValueError("bad abi or application id")
    flavor, btype = m.group(1), m.group(2).lower()
    root = os.path.realpath(build_dir)
    if not os.path.isdir(root):
        raise Discovery("missing", "no build directory %s" % build_dir)
    allowed = {"outputs": os.path.join(root, "outputs", "apk", flavor, btype),
               "intermediates": os.path.join(root, "intermediates", "apk", flavor, btype)}
    ev = {"variant": variant, "application_id": app_id, "abi": abi, "not_before_ns": not_before_ns}
    red = os.path.join(root, "intermediates", "apk_ide_redirect_file", variant,
                       "create%s%sApkListingFileRedirect" % (variant[0].upper(), variant[1:]), "redirect.txt")
    st = regular(red, "listing redirect", root)
    if st.st_size > 4096:
        raise Discovery("bad_metadata", "listing redirect is %d bytes" % st.st_size)
    if st.st_mtime_ns < not_before_ns:
        raise Discovery("stale", "listing redirect predates this build")
    body = [l.strip() for l in open(red, encoding="utf-8").read().splitlines()]
    body = [l for l in body if l and not l.startswith("#")]
    if len(body) != 1 or not body[0].startswith("listingFile="):
        raise Discovery("bad_metadata", "listing redirect must hold exactly one listingFile= line, has %r" % body[:3])
    target = body[0][len("listingFile="):]
    if not target or os.path.isabs(target) or "\\" in target or "\x00" in target:
        raise Discovery("traversal", "listingFile %r is not a relative path" % target)
    listing = os.path.normpath(os.path.join(os.path.dirname(red), target))
    ev["redirect"] = {"path": os.path.relpath(red, root), "listingFile": target}
    which = [k for k, d in allowed.items() if listing == os.path.join(d, "output-metadata.json")]
    if not which:
        rel = os.path.relpath(listing, root)
        if rel.startswith("..") or os.path.isabs(rel):
            raise Discovery("traversal", "listingFile resolves outside the build directory: %s" % rel)
        raise Discovery("wrong_variant", "listingFile resolves to %s, not %s's output-metadata.json" % (rel, variant))
    which = which[0]
    st = regular(listing, "output metadata", root)
    if st.st_size > MAX_SMALL:
        raise Discovery("bad_metadata", "output metadata is %d bytes" % st.st_size)
    if st.st_mtime_ns < not_before_ns:
        raise Discovery("stale", "output metadata %s predates this build" % os.path.relpath(listing, root))
    try:
        meta = json.load(open(listing, encoding="utf-8"))
    except ValueError as e:
        raise Discovery("bad_metadata", "output metadata is not JSON: %s" % e)
    if not isinstance(meta, dict) or meta.get("version") != 3 or not isinstance(meta.get("artifactType"), dict) \
            or meta["artifactType"].get("type") != "APK" or meta.get("elementType") != "File":
        raise Discovery("bad_metadata", "not AGP v3 APK output metadata")
    if meta.get("variantName") != variant or meta.get("applicationId") != app_id:
        raise Discovery("wrong_variant", "metadata is for %r / %r, expected %r / %r"
                        % (meta.get("variantName"), meta.get("applicationId"), variant, app_id))
    els = meta.get("elements")
    if not isinstance(els, list) or len(els) != 1 or not isinstance(els[0], dict):
        raise Discovery("ambiguous", "metadata lists %s elements, expected exactly one" % (len(els) if isinstance(els, list) else "no"))
    el = els[0]
    if el.get("type") != "SINGLE":
        raise Discovery("ambiguous", "element type %r, expected SINGLE (a split or universal set is not one APK)" % el.get("type"))
    filters = el.get("filters")
    if not isinstance(filters, list) or any(f != {"filterType": "ABI", "value": abi} for f in filters):
        raise Discovery("wrong_abi", "element filters %r (only ABI=%s allowed)" % (filters, abi))
    name = el.get("outputFile")
    if not isinstance(name, str) or not APK_NAME.match(name) or name in (".", ".."):
        raise Discovery("traversal" if isinstance(name, str) and ("/" in name or ".." in name) else "not_apk",
                        "outputFile %r is not a plain .apk file name" % name)
    apk = os.path.join(os.path.dirname(listing), name)
    st = regular(apk, "APK", root)
    if st.st_mtime_ns < not_before_ns:
        raise Discovery("stale", "APK %s predates this build" % os.path.relpath(apk, root))
    with open(apk, "rb") as f:
        if f.read(4) != b"PK\x03\x04":
            raise Discovery("not_apk", "%s is not a zip" % name)
    try:
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
    except zipfile.BadZipFile as e:
        raise Discovery("not_apk", "%s: %s" % (name, e))
    if "AndroidManifest.xml" not in names or "classes.dex" not in names:
        raise Discovery("not_apk", "%s lacks AndroidManifest.xml or classes.dex" % name)
    libdirs = sorted(set(n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2))
    sos = [n for n in names if n.startswith("lib/%s/" % abi) and n.endswith(".so")]
    if libdirs != [abi] or not sos:
        raise Discovery("wrong_abi", "native code under lib/%s, expected only lib/%s/*.so" % (libdirs, abi))
    # The other variant directory, and the rest of this one: nothing from this build may compete.
    seen = []
    for k, d in allowed.items():
        if not os.path.isdir(d) or os.path.islink(d):
            continue
        for n in sorted(os.listdir(d)):
            p = os.path.join(d, n)
            if p == apk or p == listing or not (n.endswith(".apk") or n == "output-metadata.json"):
                continue
            s2 = os.lstat(p)
            fresh = s2.st_mtime_ns >= not_before_ns
            seen.append({"path": os.path.relpath(p, root), "fresh": fresh})
            if fresh:
                raise Discovery("ambiguous", "%s is also from this build" % os.path.relpath(p, root))
    ev.update(location=which, metadata=os.path.relpath(listing, root), apk=os.path.relpath(apk, root),
              apk_bytes=st.st_size, native=sorted(sos), stale_ignored=seen,
              version_code=el.get("versionCode"), version_name=el.get("versionName"))
    return apk, ev


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--build-dir", required=True); a.add_argument("--variant", required=True)
    a.add_argument("--application-id", required=True); a.add_argument("--abi", required=True)
    a.add_argument("--not-before", required=True, help="stamp file written just before gradle")
    a.add_argument("--copy-to", required=True); a.add_argument("--json")
    o = a.parse_args()
    ev, created = {}, False
    try:
        if not os.path.isfile(o.not_before):
            raise Discovery("missing", "no build stamp %s" % o.not_before)
        apk, ev = resolve(o.build_dir, o.variant, o.application_id, o.abi, os.stat(o.not_before).st_mtime_ns)
        if os.path.lexists(o.copy_to):
            raise Discovery("ambiguous", "destination %s already exists" % o.copy_to)
        shutil.copyfile(apk, o.copy_to)
        created = True
        ev["sha256"] = sha256(o.copy_to)
        if ev["sha256"] != sha256(apk):
            raise Discovery("not_apk", "copy does not match the source")
        ev["ok"] = True
    except (Discovery, ValueError, OSError) as e:
        ev.update(ok=False, category=getattr(e, "category", "error"), error=str(e))
        if created and os.path.isfile(o.copy_to):
            os.remove(o.copy_to)  # never leave a copy behind a failed check
    if o.json:
        os.makedirs(os.path.dirname(os.path.abspath(o.json)), exist_ok=True)
        with open(o.json, "w") as f:
            json.dump(ev, f, indent=1)
    if not ev.get("ok"):
        print("::error::APK discovery failed: %s" % ev["error"])
        return 1
    print("APK DISCOVERY: %s (%s, %d bytes, sha256 %s)" % (ev["apk"], ev["location"], ev["apk_bytes"], ev["sha256"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
