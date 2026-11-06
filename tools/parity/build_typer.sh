#!/usr/bin/env bash
# Compiles typer/Typer.java to a dex with the SDK's javac + d8. Output: $1 (default $RUNNER_TEMP/typer/typer.dex).
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-${RUNNER_TEMP:?}/typer/typer.dex}
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
PJAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
W=$(mktemp -d)
javac -source 8 -target 8 -cp "$PJAR" -d "$W/classes" "$HERE/typer/Typer.java"
"$BT/d8" --min-api 26 --lib "$PJAR" --output "$W" "$W"/classes/parity/*.class
mkdir -p "$(dirname "$OUT")"
cp "$W/classes.dex" "$OUT"
rm -rf "$W"
echo "TYPER DEX: $(sha256sum "$OUT")"
