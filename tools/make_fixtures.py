#!/usr/bin/env python3
"""Generates import-regression fixtures for the emulator test (test-only data, never shipped).
Usage: make_fixtures.py <outdir>"""
import io, os, struct, sys, zipfile, time

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

COLOR_KEYS = """primary on_primary primary_container on_primary_container inverse_primary
secondary on_secondary secondary_container on_secondary_container
tertiary on_tertiary tertiary_container on_tertiary_container
background on_background surface on_surface surface_variant on_surface_variant surface_tint inverse_surface inverse_on_surface
error on_error error_container on_error_container outline outline_variant scrim
surface_bright surface_dim surface_container surface_container_high surface_container_highest surface_container_low surface_container_lowest
keyboard_surface keyboard_surface_dim keyboard_container keyboard_container_variant on_keyboard_container keyboard_press keyboard_container_pressed on_keyboard_container_pressed""".split()
DARK = {"background", "surface", "surface_dim", "surface_container", "surface_container_low", "surface_container_lowest",
        "surface_container_high", "surface_container_highest", "surface_bright", "surface_variant", "keyboard_surface",
        "keyboard_surface_dim", "keyboard_container", "keyboard_container_variant", "scrim", "primary_container",
        "secondary_container", "tertiary_container", "error_container", "inverse_surface", "keyboard_press",
        "keyboard_container_pressed", "outline", "outline_variant"}

def toml(theme_id, name="Fixture theme"):
    lines = ['# Format version: 1', f'name = "{name}"', 'author = "ci-fixture"', f'id = "{theme_id}"', 'version = 1',
             'description = "test fixture"', '', '[colors]']
    for k in COLOR_KEYS:
        if k.startswith("on_") or k in ("inverse_primary", "inverse_on_surface"):
            c = "#FFFFFFFF"
        elif k in ("primary", "secondary", "tertiary", "error", "surface_tint"):
            c = "#FF8800FF"
        elif k in DARK:
            c = "#101010FF"
        else:
            c = "#FFFFFFFF"
        lines.append(f'{k} = "{c}"')
    return "\n".join(lines) + "\n"

def version_entry():
    return struct.pack("<Bq", 1, int(time.time() * 1000))

def theme_zip(theme_id):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_STORED) as z:
        z.writestr("FUTOKeyboardTheme_Version", version_entry())
        z.writestr("theme.txt", toml(theme_id))
    return b.getvalue()

def w(name, data):
    open(os.path.join(out, name), "wb").write(data)

CFG_VERSION = "FUTOKeyboardSettings_CfgExportVersion"

def cfg(entries, corrupt_last_crc=False):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_STORED) as z:
        z.writestr(CFG_VERSION, struct.pack("<Bq", 1, int(time.time() * 1000)))
        for n, d in entries:
            z.writestr(n, d)
    data = bytearray(b.getvalue())
    if corrupt_last_crc:
        # flip one byte of the last entry's payload, keeping its stored CRC (CRC now mismatches)
        last = entries[-1][1]
        idx = bytes(data).rfind(last)
        assert idx > 0
        data[idx] ^= 0xFF
    return bytes(data)

legit_theme = theme_zip("wrenfixture1")
w("theme_legit.zip", legit_theme)
w("theme_traversal.zip", theme_zip("../pwned_by_theme_id"))
w("theme_badid_slash.zip", theme_zip("a/b/c1"))
# valid restore: one theme file
w("cfg_valid.cfg", cfg([("themes/restored1.zip", legit_theme)]))
# valid structure, CRC-valid, but the LAST entry is not a parseable personal dictionary
w("cfg_badtype_last.cfg", cfg([("themes/shouldnotland.zip", legit_theme), ("userdictionary.json", b"{this is not a list")]))
# CRC-bad last entry
w("cfg_crcbad_last.cfg", cfg([("themes/shouldnotland2.zip", legit_theme), ("themes/last.zip", b"PAYLOAD" * 50)], corrupt_last_crc=True))
# traversal in LAST entry
w("cfg_traversal_last.cfg", cfg([("themes/shouldnotland3.zip", legit_theme), ("themes/../../pwned_by_restore.txt", b"x")]))
# traversal via another domain name
w("cfg_traversal_rime.cfg", cfg([("rime/../../../pwned_by_rime.txt", b"x")]))
print("fixtures:", sorted(os.listdir(out)))
