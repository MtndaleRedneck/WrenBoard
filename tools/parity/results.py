#!/usr/bin/env python3
"""Shared result handling for the parity harness: the leg manifest, explicit leg statuses, and the gate."""
import json, os

STATUSES = ("pass", "fail", "inconclusive")
HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "legs.json")


class ManifestError(Exception):
    pass


def load_manifest(path=MANIFEST):
    with open(path) as f:
        m = json.load(f)
    if tuple(m.get("statuses", ())) != STATUSES:
        raise ManifestError("statuses must be exactly %s" % (STATUSES,))
    roles = set(m.get("roles", ())) | {"verdict"}
    seen = set()
    legs = m.get("legs")
    if not isinstance(legs, list) or not legs:
        raise ManifestError("manifest has no legs")
    for leg in legs:
        n = leg.get("name")
        if not n or n in seen:
            raise ManifestError("missing or duplicate leg name %r" % n)
        seen.add(n)
        r, req = set(leg.get("roles", ())), set(leg.get("required_roles", ()))
        if not r or not r <= roles or not req <= r:
            raise ManifestError("leg %s has bad roles/required_roles" % n)
        if leg.get("hooks", "none") not in ("none", "optional", "required"):
            raise ManifestError("leg %s has bad hooks value" % n)
        if not leg.get("assert"):
            raise ManifestError("leg %s states no assertion" % n)
    return m


def legs_for(m, role, stage=None):
    return [l for l in m["legs"] if role in l["roles"] and (stage is None or l["stage"] == stage)]


def required_for(m, role):
    return [l["name"] for l in m["legs"] if role in l.get("required_roles", ())]


def hook_status(leg, hooks_present, status):
    """A leg that needs debug hooks is inconclusive when the build has none, whatever it measured."""
    if leg.get("hooks") == "required" and not hooks_present:
        return "inconclusive"
    return status


class Results:
    def __init__(self, path, role, kb, app_sha=None):
        self.path = path
        self.data = {"role": role, "kb": kb, "app_sha": app_sha, "legs": {}, "notes": []}
        if os.path.exists(path):
            with open(path) as f:
                old = json.load(f)
            if old.get("role") == role:
                self.data["legs"] = old.get("legs", {})
        self.save()

    def set(self, name, status, **evidence):
        if status not in STATUSES:
            raise ValueError("bad status %r for %s" % (status, name))
        self.data["legs"][name] = {"status": status, "evidence": evidence}
        self.save()
        print("LEG %s: %s %s" % (name, status.upper(), json.dumps(evidence)[:400]), flush=True)

    def note(self, text):
        self.data["notes"].append(text)
        self.save()

    def save(self):
        os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f, indent=1)
        os.replace(tmp, self.path)


def gate(m, data, role):
    """Return the problems that stop this role from passing; an empty list is a pass."""
    problems = []
    legs = data.get("legs", {}) if data else {}
    for name in required_for(m, role):
        st = (legs.get(name) or {}).get("status")
        if st is None:
            problems.append("%s/%s: no result" % (role, name))
        elif st not in STATUSES:
            problems.append("%s/%s: unknown status %r" % (role, name, st))
        elif st != "pass":
            cats = ((legs.get(name) or {}).get("evidence") or {}).get("categories") or []
            tag = " [%s]" % ", ".join(str(c) for c in cats) if isinstance(cats, list) and cats else ""
            problems.append("%s/%s: %s%s" % (role, name, st, tag))
    return problems
