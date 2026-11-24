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
# 1.10 guard wiring (review findings): raw/display split. The expandable-container gate must read
# the RAW value (its suggestion count picks the container; synthetic starters must not flip it), the
# strip draw sites must read the display copy, and blankGuard must run at write/selection time only.
UIX=java/src/org/futo/inputmethod/latin/uix/UixManager.kt
gate=$(grep -A1 'needToUseExpandableSuggestionUi =' "$UIX" | tr -d ' ')
if printf '%s' "$gate" | grep -q 'suggestedWords.value?.size()' && ! printf '%s' "$gate" | grep -q 'displaySuggestedWords'; then
  echo "lint-ok: expandable gate reads raw suggestions"
else
  echo "LINT-FAIL: expandable gate must read raw suggestedWords.value, not the display copy"; fail=1
fi
draws=$(grep -c 'displaySuggestedWords.value' "$UIX")
if [ "$draws" -ge 5 ]; then
  echo "lint-ok: display copy wired at $draws sites (2 writes + 3 reads expected)"
else
  echo "LINT-FAIL: display copy wired at $draws sites, expected >= 5"; fail=1
fi
calls=$(grep -n 'blankGuard(' "$UIX" | grep -v 'fun blankGuard')
if [ "$(printf '%s\n' "$calls" | wc -l)" -eq 3 ] \
   && printf '%s\n' "$calls" | grep -q 'blankGuard(null)' \
   && printf '%s\n' "$calls" | grep -q 'blankGuard(suggestedWords)' \
   && printf '%s\n' "$calls" | grep -q 'blankGuard(suggestedWords.value)'; then
  echo "lint-ok: blankGuard runs at write/selection time only (3 call sites, none at draw)"
else
  echo "LINT-FAIL: blankGuard call sites changed - expected write-time funnel, input start, selection refresh only:"; printf '%s\n' "$calls"; fail=1
fi
# NavController back-stack access from Java: the stack is Kotlin `val currentBackStack:
# StateFlow<List<NavBackStackEntry>>`, exposed to Java as getCurrentBackStack().getValue().
# A getBackStack() call on the controller does not exist (r25 compile loss: 7-min build wasted).
badstack=$(grep -rn 'getNavController()\.getBackStack()' tests/src --include=*.java || true)
if [ -z "$badstack" ]; then
  echo "lint-ok: no forbidden NavController.getBackStack() accessor (use getCurrentBackStack().getValue())"
else
  echo "LINT-FAIL: NavController exposes getCurrentBackStack().getValue(), not getBackStack():"; printf '%s\n' "$badstack"; fail=1
fi
exit $fail
