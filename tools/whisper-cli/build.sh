#!/usr/bin/env bash
# Builds whisper-cli against the vendored bundled engine (native/jni/src/ggml).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
G="$ROOT/native/jni/src/ggml"
mkdir -p "$ROOT/tools/whisper-cli/out"
cd "$ROOT/tools/whisper-cli/out"
for f in ggml ggml-alloc ggml-backend ggml-quants; do
  gcc -O2 -pthread -I"$G" -I"$ROOT/native/jni/src" -c "$G/$f.c" -o "$f.o"
done
g++ -O2 -pthread -I"$G" -I"$ROOT/native/jni/src" -c "$G/whisper.cpp" -o whisper.o
g++ -O2 -pthread -I"$G" -I"$ROOT/native/jni/src" -c "$ROOT/tools/whisper-cli/main.cpp" -o main.o
g++ -O2 -pthread main.o whisper.o ggml.o ggml-alloc.o ggml-backend.o ggml-quants.o -o whisper-cli
echo "BUILT-WHISPER-CLI: $(ls -la whisper-cli)"
