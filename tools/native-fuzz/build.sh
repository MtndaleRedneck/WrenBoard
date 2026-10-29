#!/bin/bash
# usage: build.sh <jni-src-root> <out-binary>   (jni-src-root = native/jni/src)
set -eu
SRC="$1"; OUT="$2"; D="$(dirname "$OUT")"; mkdir -p "$D/obj"
H="$(cd "$(dirname "$0")" && pwd)"
CF="-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -w -I$SRC/ggml -I$SRC -DGGML_USE_K_QUANTS"
for f in ggml ggml-alloc ggml-quants ggml-backend; do
  [ "$D/obj/$f.o" -nt "$SRC/ggml/$f.c" ] || gcc $CF -std=gnu11 -c "$SRC/ggml/$f.c" -o "$D/obj/$f.o"
done
[ "$D/obj/whisper.o" -nt "$SRC/ggml/whisper.cpp" ] || g++ $CF -std=gnu++17 -c "$SRC/ggml/whisper.cpp" -o "$D/obj/whisper.o"
g++ $CF -std=gnu++17 -c "$H/test_native.cpp" -o "$D/obj/test.o"
g++ -fsanitize=address,undefined "$D"/obj/*.o -lm -lpthread -o "$OUT"
