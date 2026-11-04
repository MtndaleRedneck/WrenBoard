#!/usr/bin/env bash
# Builds the minimal ime-target APK with SDK command-line tools only (no gradle).
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
PJAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
echo "build-tools: $BT"
echo "platform jar: $PJAR"
rm -rf out && mkdir -p out/classes out/dex
javac -source 8 -target 8 -cp "$PJAR" -d out/classes src/com/wrenboard/imetarget/*.java
"$BT/d8" --min-api 21 --lib "$PJAR" --output out/dex out/classes/com/wrenboard/imetarget/*.class
"$BT/aapt2" link -o out/imetarget-unaligned.apk -I "$PJAR" --manifest AndroidManifest.xml ${ASSETS_DIR:+-A "$ASSETS_DIR"}
(cd out/dex && zip -q -X ../imetarget-unaligned.apk classes.dex)
"$BT/zipalign" -f 4 out/imetarget-unaligned.apk out/imetarget-aligned.apk
keytool -genkeypair -keystore out/debug.keystore -alias androiddebugkey -keyalg RSA \
  -keysize 2048 -validity 10000 -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
"$BT/apksigner" sign --ks out/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out out/imetarget.apk out/imetarget-aligned.apk
echo "BUILT-IMETARGET: $(ls -la out/imetarget.apk)"
