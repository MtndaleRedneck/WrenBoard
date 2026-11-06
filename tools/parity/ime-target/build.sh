#!/usr/bin/env bash
# Builds the parity test-input app with the SDK command-line tools (no gradle).
# Output: $1 (default $RUNNER_TEMP/imetarget/imetarget.apk), signed with a throwaway key made here.
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-${RUNNER_TEMP:?}/imetarget/imetarget.apk}
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
PJAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
W=$(mktemp -d)
mkdir -p "$W/classes" "$W/dex"
javac -source 8 -target 8 -cp "$PJAR" -d "$W/classes" "$HERE"/src/com/wrenboard/imetarget/*.java
"$BT/d8" --min-api 26 --lib "$PJAR" --output "$W/dex" "$W"/classes/com/wrenboard/imetarget/*.class
"$BT/aapt2" link -o "$W/u.apk" -I "$PJAR" --manifest "$HERE/AndroidManifest.xml"
(cd "$W/dex" && zip -q -X ../u.apk classes.dex)
"$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
keytool -genkeypair -keystore "$W/t.jks" -alias target -keyalg RSA -keysize 2048 -validity 30 \
  -storepass parity1 -keypass parity1 -dname "CN=Parity test target,O=Test,C=US" 2>/dev/null
mkdir -p "$(dirname "$OUT")"
"$BT/apksigner" sign --ks "$W/t.jks" --ks-pass pass:parity1 --key-pass pass:parity1 --out "$OUT" "$W/a.apk"
"$BT/apksigner" verify "$OUT"
rm -rf "$W"
echo "IMETARGET APK: $(sha256sum "$OUT")"
