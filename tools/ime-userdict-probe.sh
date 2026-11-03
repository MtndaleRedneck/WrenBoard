# Sourced by ime-verify.sh AFTER ime-learn-restore.sh (needs predict_slots/precond/type_letters/edit_text).
# USER-DICTIONARY PROVIDER PROBE - synthetic made-up words ONLY, pure provider research for the
# SwiftKey-import MAYBE discussion. No importer code, no private data, every inserted row removed.
# Answers, with evidence: E4 raw provider duplicate semantics; E1 whether a provider word becomes
# suggestible at all (actual benefit mechanism); E2 whether a word already in the base dictionary
# changes rank/capitalization when added to the personal dictionary (the overlap-value question).
note "== user-dictionary provider probe (synthetic words only; insert/query/delete via shell) =="
UDURI="content://user_dictionary/words"
udq() { adb shell "content query --uri $UDURI --projection word:frequency:locale:shortcut --where \"word like 'zqprobe%'\"" 2>&1 | tr -d '\r'; }
udins() { adb shell "content insert --uri $UDURI --bind word:s:$1 --bind frequency:i:$2 ${3:+--bind locale:s:$3}" >/dev/null 2>&1; }
udclear() { adb shell "content delete --uri $UDURI --where \"word like 'zqprobe%'\"" >/dev/null 2>&1; }
# strip capture, same mechanism as wb12-check's sugg(): WBDBG logcat -> key=value parse
# NOTE: suggestion logging is counts-only (SUGG [n=N]) - this probe's suggestibility reads
# return counts, not words, and it needs a rework before any future run. Parked with the
# SwiftKey-import MAYBE decision.
udsugg() { adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' > shots/_uds.txt; python3 tools/wb12_kv.py shots/_uds.txt sugg; }

udclear   # clean slate even if a previous probe died mid-run
note "probe baseline provider rows (zqprobe%): $(udq | grep -c Row)"

# --- E4: raw provider duplicate semantics (drives whether future dedupe must be pre-insert) ---
udins zqprobedup 77 en_US
udins zqprobedup 77 en_US
N1=$(udq | grep -c "zqprobedup")
udins zqprobedup 250 en_US
N2=$(udq | grep -c "zqprobedup")
note "E4: same word+locale inserted twice -> $N1 row(s); then different frequency -> $N2 row(s)"
[ "$N1" = 1 ] && [ "$N2" = 1 ] && note "E4 RESULT: provider REPLACES on identical word+locale (re-insert updates, no dup rows)" \
  || { [ "$N2" = 3 ] && note "E4 RESULT: provider keeps DUPLICATE rows (any future import must dedupe BEFORE insert)" \
  || note "E4 RESULT: other pattern ($N1/$N2) - inspect: $(udq | grep zqprobedup | tr '\n' ';')"; }
udq | grep zqprobedup | sed 's/^/    E4 rows: /' | tee -a "$R"

# --- E1: unknown made-up word - does a provider insert make it suggestible? (actual benefit) ---
precond udprobe1 || note "INCONCLUSIVE(harness): focus not reached for E1"
clear_field; adb logcat -c; type_letters z q p r o b e w; sleep 1.5; S_BEFORE=$(udsugg)
note "E1 before insert, typed 'zqprobew': [$S_BEFORE]"
udins zqprobeword 250 en_US
sleep 2   # observer/debounce: UserDictionaryObserver must see the provider change
clear_field; adb logcat -c; type_letters z q p r o b e w; sleep 1.5; S_AFTER=$(udsugg)
note "E1 after insert, typed 'zqprobew': [$S_AFTER]"
if echo "$S_AFTER" | tr '|' '\n' | grep -qi "zqprobeword" && ! echo "$S_BEFORE" | tr '|' '\n' | grep -qi "zqprobeword"; then
  note "E1 RESULT: provider word BECAME suggestible after insert (transformer path sees the personal dictionary)"
elif echo "$S_AFTER" | tr '|' '\n' | grep -qi "zqprobeword"; then
  note "E1 RESULT-UNCLEAN: word present after but also before? before=[$S_BEFORE]"
else
  note "E1 RESULT: provider word did NOT appear in the strip after insert (before=[$S_BEFORE] after=[$S_AFTER])"
fi

# --- E2: overlap value - ordinary base-dictionary word + capitalization variant ---
# 'banana' is ordinary English, definitely in the base dictionary: does adding it personally change anything?
precond udprobe2 || note "INCONCLUSIVE(harness): focus not reached for E2"
clear_field; adb logcat -c; type_letters b a n a; sleep 1.5; B_BEFORE=$(udsugg)
BEX=$(adb shell "content query --uri $UDURI --projection word --where "word='banana'"" 2>/dev/null | grep -c Row | tr -d '\r')
udins banana 250 en_US
sleep 2
clear_field; adb logcat -c; type_letters b a n a; sleep 1.5; B_AFTER=$(udsugg)
note "E2 'bana' strip before personal add: [$B_BEFORE]"
note "E2 'bana' strip after personal add:  [$B_AFTER]"
[ "$B_BEFORE" = "$B_AFTER" ] && note "E2 RESULT: no strip change for an already-known word (at this prefix)" \
  || note "E2 RESULT: strip CHANGED for an already-known word - personal add has visible effect even on overlap"
# capitalization: personal mixed-case entry should surface with ITS case
udins zqProbeCorp 250 en_US
sleep 2
clear_field; adb logcat -c; type_letters z q p r o b e c; sleep 1.5; C_AFTER=$(udsugg)
note "E2b typed 'zqprobec' after inserting 'ZqCorp'-style 'zqProbeCorp': [$C_AFTER]"
echo "$C_AFTER" | tr '|' '\n' | grep -q "zqProbeCorp" && note "E2b RESULT: personal capitalization preserved in the strip" \
  || note "E2b RESULT: personal capitalization NOT surfaced as-is (strip: [$C_AFTER])"

udclear
[ "${BEX:-0}" = 0 ] && adb shell "content delete --uri $UDURI --where \"word='banana'\"" >/dev/null 2>&1
note "probe cleanup: remaining zqprobe rows: $(udq | grep -c Row); 'banana' pre-existed=$BEX (removed only if we inserted it)"
F0=$(adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION"); note "FATAL EXCEPTION count at end of probe: $F0"
