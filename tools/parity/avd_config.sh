#!/usr/bin/env bash
# Pre-emulator-launch step: write the validated display inputs into the AVD before it boots, so the
# device's physical size and density are the requested ones (run_legs.py reads them back and gates on
# them). Values arrive only as env (WM_SIZE, WM_DENSITY, NAV, already checked by validate_inputs.py)
# and are re-checked here; nothing is interpolated from workflow expressions.
# Usage: avd_config.sh AVD_NAME
set -euo pipefail
avd="${1:-}"
[[ "$avd" =~ ^[A-Za-z0-9_]{1,40}$ ]] || { echo "::error::bad AVD name"; exit 1; }
[[ "${WM_SIZE:-}" =~ ^([0-9]{3,4})x([0-9]{3,4})$ ]] || { echo "::error::bad WM_SIZE"; exit 1; }
w="${BASH_REMATCH[1]}"; h="${BASH_REMATCH[2]}"
[[ "${WM_DENSITY:-}" =~ ^[1-9][0-9]{2}$ ]] || { echo "::error::bad WM_DENSITY"; exit 1; }
[[ "${NAV:-}" == gesture || "${NAV:-}" == 3button ]] || { echo "::error::bad NAV"; exit 1; }
cfg="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$avd.avd/config.ini"
[ -f "$cfg" ] || { echo "::error::AVD config not found: $cfg"; exit 1; }
tmp="$(mktemp)"
grep -v -E '^[[:space:]]*(hw\.lcd\.width|hw\.lcd\.height|hw\.lcd\.density|hw\.mainKeys)[[:space:]]*=' "$cfg" > "$tmp" || true
# hw.mainKeys=no: no hardware keys, so the software navigation bar exists for the nav overlay (3-button
# or gesture, chosen at run time by run_legs.py from NAV).
printf 'hw.lcd.width=%s\nhw.lcd.height=%s\nhw.lcd.density=%s\nhw.mainKeys=no\n' "$w" "$h" "$WM_DENSITY" >> "$tmp"
mv "$tmp" "$cfg"
echo "AVD $avd display config (nav $NAV applied after boot):"
grep -E '^(hw\.lcd\.|hw\.mainKeys)' "$cfg"
