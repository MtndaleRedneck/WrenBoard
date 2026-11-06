Real AGP 8.10.1 / Gradle 8.14.3 output metadata, copied as produced (JSON and redirect text only; no APK
bytes). Captured 2026-11-06 on Tab's box:
- real-f4288fe/: the WrenBoard app at f4288fe3204d38aac401b5185b7a0666c2372bd1 (submodules as the workflow
  inits them), `VERSION_CODE=1 VERSION_NAME=parity-f4288fe ./gradlew assembleStableDebug
  -Pandroid.injected.build.abi=x86_64`: BUILD SUCCESSFUL, APK only under build/intermediates/apk/stable/debug/
  (143,296,403 bytes, lib/x86_64 only), nothing under build/outputs/apk (tree.txt). This is run 2's cause.
- real-4094f96/: the same for the baseline head 4094f96e1e0ce789ce727263fad392482e8f8051 (BUILD SUCCESSFUL 1m06s,
  143,156,664 bytes, lib/x86_64 only, nothing under build/outputs/apk). Its outputFile is appb-stable-debug.apk
  because AGP names the file after the checkout directory (this local one was appb/; the workflow's is app/).
- probe-*/: a minimal app with the same plugin versions, flavors stable + unstable (applicationIdSuffix
  .unstable) and jniLibs for x86_64 and arm64-v8a:
  probe-injected (assembleStableDebug with the flag: intermediates, lib/x86_64 only), probe-plain (clean
  assembleStableDebug without the flag: outputs), probe-stale (plain build, then the flag build without
  clean: a stale APK stays in outputs/ and the redirect points at intermediates/; tree.txt has the mtimes),
  probe-unstable (assembleUnstableDebug: the wrong-variant metadata).
