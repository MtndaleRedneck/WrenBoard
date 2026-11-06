#!/usr/bin/env bash
# Makes a TEST copy of a debug APK whose swipe encoder is broken, to check that WrenBoard disables
# neural swipe without crashing and falls back to legacy gesture decoding.
# Usage: model_variant.sh IN.apk corrupt|truncated|missing OUT.apk
#   corrupt   - encoder replaced by random bytes of the same size (passes SwipeModelGate, fails to load)
#   truncated - encoder cut to 1000 bytes (below SwipeModelGate.MIN_MODEL_BYTES)
#   missing   - encoder entry removed from the APK (asset open fails)
# The copy is re-signed with a throwaway key and stays in RUNNER_TEMP; it is never uploaded.
set -euo pipefail
IN=$1; MODE=$2; OUT=$3
case "$MODE" in corrupt|truncated|missing) ;; *) echo "mode must be corrupt, truncated or missing" >&2; exit 2 ;; esac
: "${ANDROID_HOME:?}"
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
W=$(mktemp -d)
cp "$IN" "$W/in.apk"
mapfile -t HITS < <(unzip -Z1 "$W/in.apk" | grep -E '(^|/)honorable_sturgeon/model_fp32\.pte$' || true)
[ "${#HITS[@]}" -eq 1 ] || { echo "expected exactly one encoder entry, found ${#HITS[@]}" >&2; exit 1; }
E=${HITS[0]}
mkdir -p "$W/x"
(cd "$W/x" && unzip -q "$W/in.apk" "$E")
SZ=$(stat -c%s "$W/x/$E")
[ "$SZ" -gt 1000 ] || { echo "encoder is only $SZ bytes" >&2; exit 1; }
OLD=$(sha256sum "$W/x/$E" | cut -d' ' -f1)
zip -q -d "$W/in.apk" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.EC' 'META-INF/*.DSA' 'META-INF/MANIFEST.MF' || true
if [ "$MODE" = missing ]; then
  zip -q -d "$W/in.apk" "$E"
  if unzip -Z1 "$W/in.apk" | grep -qxF "$E"; then echo "encoder entry still present" >&2; exit 1; fi
  NEW=absent
else
  if [ "$MODE" = corrupt ]; then head -c "$SZ" /dev/urandom > "$W/x/$E.new"; else head -c 1000 "$W/x/$E" > "$W/x/$E.new"; fi
  mv "$W/x/$E.new" "$W/x/$E"
  NEW=$(sha256sum "$W/x/$E" | cut -d' ' -f1)
  [ "$OLD" != "$NEW" ] || { echo "encoder unchanged" >&2; exit 1; }
  zip -q -d "$W/in.apk" "$E"
  (cd "$W/x" && zip -q -0 -X "$W/in.apk" "$E")
fi
"$BT/zipalign" -p -f 4 "$W/in.apk" "$W/aligned.apk"
keytool -genkeypair -keystore "$W/k.jks" -alias variant -keyalg RSA -keysize 2048 -validity 30 \
  -storepass parity1 -keypass parity1 -dname "CN=Parity test variant,O=Test,C=US" 2>/dev/null
"$BT/apksigner" sign --ks "$W/k.jks" --ks-pass pass:parity1 --key-pass pass:parity1 --out "$OUT" "$W/aligned.apk"
"$BT/apksigner" verify "$OUT"
if [ "$MODE" = missing ]; then NSZ=0; else NSZ=$(stat -c%s "$W/x/$E"); fi
echo "MODEL VARIANT $MODE: $E $SZ bytes sha256 $OLD -> $NSZ bytes sha256 $NEW"
rm -rf "$W"
