#!/usr/bin/env bash
# Harness preflight for the 1.10 runtime probes: every UpdateStatus.INSTANCE.<accessor>
# used by the probe tests must resolve to a property declared on object UpdateStatus,
# applying Kotlin's Java-interop naming: `val isX` exposes isX() (never getIsX()),
# everything else exposes getX()/setX(). Catches harness compile errors in seconds
# instead of after the ~6-minute fixture build (r18 loss class). App source untouched.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=java/src/org/futo/inputmethod/latin/uix/settings/UpdateScreen.kt
fail=0
for acc in $(grep -rhoE 'UpdateStatus\.INSTANCE\.[A-Za-z0-9_]+' tests/src/org/futo/inputmethod/latin/*.java | sed 's/.*INSTANCE\.//' | sort -u); do
  case "$acc" in
    is[A-Z]*)
      prop="$acc" ;;
    get[A-Z]*)
      prop="$(printf '%s' "${acc#get}" | sed 's/./\l&/')"
      if printf '%s' "$prop" | grep -qE '^is[A-Z]'; then
        echo "LINT-FAIL: $acc - Kotlin is-prefixed val exposes $prop(), not $acc()"
        fail=1; continue
      fi ;;
    set[A-Z]*)
      prop="$(printf '%s' "${acc#set}" | sed 's/./\l&/')"
      if printf '%s' "$prop" | grep -qE '^is[A-Z]'; then
        echo "LINT-FAIL: $acc - Kotlin is-prefixed var setter is set${prop#is}(), not $acc()"
        fail=1; continue
      fi ;;
    *)
      echo "LINT-FAIL: $acc is not a recognized accessor shape"; fail=1; continue ;;
  esac
  if ! grep -qE "(val|var)[[:space:]]+$prop([[:space:]=:]|$)" "$SRC"; then
    echo "LINT-FAIL: UpdateStatus.$prop (via $acc) not declared in $SRC"
    fail=1
  else
    echo "lint-ok: UpdateStatus.INSTANCE.$acc -> $prop"
  fi
done
exit $fail
