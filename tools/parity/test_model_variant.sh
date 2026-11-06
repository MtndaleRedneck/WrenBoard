#!/usr/bin/env bash
# Local regression for model_variant.sh: builds all three broken-model variants from a test APK that
# carries a fake encoder, and checks each variant's encoder entry (random / 1000 bytes / absent), that
# every other entry is unchanged, that the output verifies with apksigner, and that a bad mode fails.
# Needs ANDROID_HOME (build-tools), keytool, zip/unzip. Usage: test_model_variant.sh ANY_TEST.apk
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${1:?usage: test_model_variant.sh ANY_TEST.apk}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
E=assets/honorable_sturgeon/model_fp32.pte
fail=0; ok() { echo "PASS $*"; }; no() { echo "FAIL $*"; fail=1; }
cp "$SRC" "$W/base.apk"; mkdir -p "$W/a/assets/honorable_sturgeon"
head -c 200000 /dev/urandom > "$W/a/$E"
(cd "$W/a" && zip -q -0 "$W/base.apk" "$E")
ORIG=$(unzip -p "$W/base.apk" "$E" | sha256sum | cut -d' ' -f1)
others() { unzip -Z1 "$1" | grep -v -e '^META-INF/' -e "^$E\$" | sort | while read -r n; do printf '%s %s\n' "$n" "$(unzip -p "$1" "$n" | sha256sum | cut -c1-16)"; done; }
others "$W/base.apk" > "$W/others.base"
for m in corrupt truncated missing; do
  if bash "$HERE/model_variant.sh" "$W/base.apk" "$m" "$W/$m.apk" > "$W/$m.log" 2>&1; then ok "$m: builder exit 0 ($(grep 'MODEL VARIANT' "$W/$m.log"))"; else no "$m: builder failed: $(tail -3 "$W/$m.log")"; continue; fi
  others "$W/$m.apk" > "$W/others.$m"
  cmp -s "$W/others.base" "$W/others.$m" && ok "$m: every other entry unchanged" || no "$m: other entries changed"
  "$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)/apksigner" verify "$W/$m.apk" >/dev/null 2>&1 && ok "$m: apksigner verify" || no "$m: apksigner verify"
  case $m in
    corrupt) S=$(unzip -p "$W/$m.apk" "$E" | wc -c); H=$(unzip -p "$W/$m.apk" "$E" | sha256sum | cut -d' ' -f1)
             [ "$S" -eq 200000 ] && [ "$H" != "$ORIG" ] && ok "corrupt: same size ($S), different bytes" || no "corrupt: size $S hash same=$([ "$H" = "$ORIG" ] && echo y)";;
    truncated) S=$(unzip -p "$W/$m.apk" "$E" | wc -c); [ "$S" -eq 1000 ] && ok "truncated: encoder is 1000 bytes" || no "truncated: $S bytes";;
    missing) unzip -Z1 "$W/$m.apk" | grep -qxF "$E" && no "missing: entry still present" || ok "missing: encoder entry absent";;
  esac
done
bash "$HERE/model_variant.sh" "$W/base.apk" bogus "$W/x.apk" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "bad mode rejected (exit $rc)" || no "bad mode exit $rc"
cp "$SRC" "$W/noenc.apk"
bash "$HERE/model_variant.sh" "$W/noenc.apk" missing "$W/y.apk" >/dev/null 2>&1; rc=$?
[ "$rc" -ne 0 ] && ok "APK without an encoder rejected (exit $rc)" || no "APK without an encoder accepted"
echo "RESULT $([ $fail -eq 0 ] && echo PASS || echo FAIL)"; exit $fail
