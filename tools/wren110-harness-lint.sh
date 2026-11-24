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
# (26) updater durability ordering (ChatGPT (25) review): the durable pending-install marker
# must outlive the in-memory hand-over. Every retirePendingInstallMarker() call is either a
# cleanup where the staged file is gone (delete within 4 lines before, or attempts.fail within
# 3 lines after) or post-launch cleanup (launchInstaller within 12 lines before, comments
# between). A retire before handOver reopens the stranded-update death window.
SCR=java/src/org/futo/inputmethod/latin/uix/settings/UpdateScreen.kt
retires=$(grep -c 'appContext\.retirePendingInstallMarker()' "$SCR" || true)
if [ "$retires" -ne 5 ]; then
  echo "LINT-FAIL: expected 5 retirePendingInstallMarker sites (3 file-gone + 2 post-launch), found $retires"; fail=1
fi
badretire=0
for n in $(grep -n 'appContext\.retirePendingInstallMarker()' "$SCR" | cut -d: -f1); do
  after=$(sed -n "$((n+1)),$((n+3))p" "$SCR")
  before=$(sed -n "$((n>n-12 ? n-12 : 1)),$((n-1))p" "$SCR")
  if printf '%s' "$after" | grep -q 'attempts\.fail('; then :
  elif printf '%s' "$before" | grep -q '\.delete()'; then :
  elif printf '%s' "$before" | grep -q 'if (launchInstaller('; then :
  else echo "LINT-FAIL: retire at $SCR:$n is neither file-gone cleanup nor post-launch"; badretire=1; fi
done
for n in $(grep -n 'attempts\.handOver(attempt)' "$SCR" | cut -d: -f1); do
  before=$(sed -n "$((n>n-8 ? n-8 : 1)),$((n-1))p" "$SCR")
  if printf '%s' "$before" | grep -q 'retirePendingInstallMarker()'; then
    echo "LINT-FAIL: retire within 8 lines before handOver at $SCR:$n (stranded-update death window)"; badretire=1
  fi
done
if [ "$badretire" -eq 0 ] && [ "$retires" -eq 5 ]; then
  echo "lint-ok: durable marker retires only in failure cleanup or after successful installer launch"
fi
# (28) direct-path durability (ChatGPT (27) review): downloadAndInstall stages its own durable
# marker between handOver and launch, retires it only after launchInstaller(context, file)
# returns true, and retires owned on the failure branch (whose finally deletes the file).
dlaunch=$(grep -n 'if (launchInstaller(context, file)) {' "$SCR" | cut -d: -f1)
dretire=$(grep -n 'context\.retirePendingInstallMarker()' "$SCR" | cut -d: -f1)
downed=$(grep -n 'context\.retirePendingInstallMarkerIfOwned(attempt)' "$SCR" | cut -d: -f1)
if [ -z "$dlaunch" ] || [ -z "$dretire" ] || [ -z "$downed" ]; then
  echo "LINT-FAIL: (28) direct-path marker sites missing (launch=$dlaunch retire=$dretire owned=$downed)"; fail=1
elif [ "$dretire" -lt "$dlaunch" ]; then
  echo "LINT-FAIL: direct-path retire at $SCR:$dretire precedes launch at $dlaunch (stranded-update death window)"; fail=1
else
  dhand=$(grep -n 'if (UpdateStatus.attempts.handOver(attempt)) {' "$SCR" | head -1 | cut -d: -f1)
  if ! sed -n "${dhand},${dlaunch}p" "$SCR" | grep -q 'prefs\[PENDING_INSTALL_ATTEMPT\] = attempt'; then
    echo "LINT-FAIL: direct path stages no durable marker between handOver ($dhand) and launch ($dlaunch)"; fail=1
  else
    echo "lint-ok: direct path stages durable marker pre-launch and retires only post-launch or owned-on-failure"
  fi
fi
# (27)/(28) cold-start ANR fix: the migration primer must not run on the main dispatcher.
LIME=java/src/org/futo/inputmethod/latin/LatinIME.kt
if grep -q 'launchJob { withContext(Dispatchers.IO) { readUnifiedActions() } }' "$LIME" \
  && ! grep -q 'this\.readUnifiedActions()' "$LIME"; then
  echo "lint-ok: unified-actions primer confined to Dispatchers.IO"
else
  echo "LINT-FAIL: readUnifiedActions must run via launchJob + withContext(Dispatchers.IO)"; fail=1
fi
exit $fail
