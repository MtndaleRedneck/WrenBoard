#!/bin/bash
# Host test for the shipped DictionarySwap.kt. Needs kotlinc on PATH.
set -e
OUT=${1:-/tmp/swaptest}; mkdir -p "$OUT"
D=$(cd "$(dirname "$0")/../.." && pwd)
kotlinc "$D/java/src/org/futo/inputmethod/latin/uix/DictionarySwap.kt" "$D/tools/restore-swap-test/SwapTest.kt" -include-runtime -d "$OUT/t.jar" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/t.jar" SwapTestKt
