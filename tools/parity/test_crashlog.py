#!/usr/bin/env python3
"""Local regression tests for crashlog.py (no device). Exit 0 only if every case behaves."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crashlog as C

P = "com.wrenboard.keyboard"
fails = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails.append(m)

# Java crash: the package is named only on the "Process:" line, ADJACENT to the exception, never on it.
CRASH = """--------- beginning of crash
1762412001.100  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
1762412001.100  4321  4321 E AndroidRuntime: Process: com.wrenboard.keyboard, PID: 4321
1762412001.101  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: decoder gone
1762412001.101  4321  4321 E AndroidRuntime: \tat org.futo.inputmethod.latin.SwipeDecoderDictionary.getOrInitDecoder(SwipeDecoderDictionary.kt:359)
1762412001.101  4321  4321 E AndroidRuntime: \tat org.futo.inputmethod.latin.DictionaryFacilitatorImpl.getSuggestionResults(DictionaryFacilitatorImpl.java:893)
1762412050.000  7777  7777 E AndroidRuntime: FATAL EXCEPTION: main
1762412050.000  7777  7777 E AndroidRuntime: Process: com.example.other, PID: 7777
1762412050.001  7777  7777 E AndroidRuntime: java.lang.NullPointerException
"""
# Native crash of the keyboard: crash_dump's pid differs from the app's; the tombstone header names it.
NATIVE = """1762412100.000  9001  9001 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
1762412100.000  9001  9001 F DEBUG   : pid: 4400, tid: 4410, name: Thread-7  >>> com.wrenboard.keyboard <<<
1762412100.001  9001  9001 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
1762412100.002  9001  9001 F DEBUG   :       #00 pc 000000000012a3b4  /data/app/lib/x86_64/libjni_latinime.so
"""
# A crash entry whose header names nothing; the pid is tied to the package only by the events buffer.
UNNAMED = """1762412200.000  5555  5555 E AndroidRuntime: FATAL EXCEPTION: InputThread
1762412200.001  5555  5555 E AndroidRuntime: java.lang.OutOfMemoryError
"""
EVENTS = """1762412200.002  1000  1200 I am_crash: [0,5555,com.wrenboard.keyboard,-1,java.lang.OutOfMemoryError,,,]
1762412300.000  1000  1200 I am_anr  : [0,6000,com.wrenboard.keyboard,-1,Input dispatching timed out]
1762412301.000  1000  1200 I am_proc_start: [0,6001,10123,com.example.other,activity,{}]
"""
MAIN = """1762412300.001  1000  1200 E ActivityManager: ANR in com.wrenboard.keyboard
1762412300.001  1000  1200 E ActivityManager: PID: 6000
1762412300.001  1000  1200 E ActivityManager: Reason: Input dispatching timed out
1762412400.000  1000  1200 E ActivityManager: ANR in com.example.other
"""

fs = C.findings(P, CRASH + NATIVE + UNNAMED, EVENTS, MAIN)
java = [f for f in fs if f["pid"] == 4321]
check(len(java) == 1 and "IllegalStateException" in java[0]["text"] and "SwipeDecoderDictionary.kt:359" in java[0]["text"],
      "java crash kept whole (exception + stack) though the package is only on the adjacent Process: line")
check(java and java[0]["lines"] == 5, "java crash block has all 5 lines")
check(any(f["kind"] == "native" and "SIGSEGV" in f["text"] and "libjni_latinime" in f["text"] for f in fs), "native tombstone attributed with its signal and frames")
check(any(f["pid"] == 5555 and "OutOfMemoryError" in f["text"] and not f["named_in_block"] for f in fs), "unnamed crash block attributed through the am_crash pid")
check(any(f["buffer"] == "events" and f["tag"] == "am_anr" for f in fs), "am_anr event kept")
check(any(f["buffer"] == "main" and "Input dispatching timed out" in f["text"] for f in fs), "ANR-in block with its reason kept from main")
check(not any("com.example.other" in f["text"] for f in fs), "other packages' crashes and ANRs are not attributed to the keyboard")
check(len(C.findings(P, "", "", "")) == 0, "empty buffers give no findings")
W = [("setup", 1762412000.0, 1762412001.05), ("leg:glide", 1762412001.05, 1762412150.0), ("leg:model_corrupt", 1762412150.0, 1762412250.0)]
C.attribute(fs, W)
check(java and java[0]["window"] == "leg:glide", "java crash attributed to the leg window it happened in")
check(any(f["pid"] == 5555 and f["window"] == "leg:model_corrupt" for f in fs), "OOM crash attributed to model_corrupt window")
check(any(f["window"] == "between_windows" for f in fs), "a finding outside every window is labelled, not dropped")
print("RESULT", "FAIL" if fails else "PASS", len(fails), "failures")
sys.exit(1 if fails else 0)
