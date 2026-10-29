#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
PJAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
rm -rf out && mkdir -p out/classes out/dex out/res
javac -source 8 -target 8 -cp "$PJAR" -d out/classes src/com/wrenboard/bkproto/*.java
"$BT/d8" --min-api 21 --lib "$PJAR" --output out/dex out/classes/com/wrenboard/bkproto/*.class
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/bk-unaligned.apk -I "$PJAR" --manifest AndroidManifest.xml out/res.zip
(cd out/dex && zip -q -X ../bk-unaligned.apk classes.dex)
"$BT/zipalign" -f 4 out/bk-unaligned.apk out/bk-aligned.apk
keytool -genkeypair -keystore out/debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
"$BT/apksigner" sign --ks out/debug.keystore --ks-pass pass:android --key-pass pass:android --out out/bkproto.apk out/bk-aligned.apk
echo "BUILT: $(ls -la out/bkproto.apk)"
