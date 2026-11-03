#!/usr/bin/env bash
# Qualification-only emitted digital output, never a device audibility test.
set -eu
if [ "${WB_AUDIO_CAPTURE:-0}" != 1 ]; then exec bash tools/wb12-check.sh "$@"; fi
mkdir -p shots
pactl list short sinks > shots/audio_sinks.txt
pactl list sink-inputs > shots/audio_sink_inputs_before.txt
ffmpeg -nostdin -hide_banner -loglevel warning -f pulse -i wren_capture.monitor -ac 2 -ar 48000 -y shots/emitted_audio.wav >shots/audio_capture.log 2>&1 &
AUDIO_PID=$!
MONITOR_PID=""
cleanup() {
  rc=$?
  trap - EXIT
  pactl list sink-inputs > shots/audio_sink_inputs_after.txt || true
  if [ -n "$MONITOR_PID" ]; then kill "$MONITOR_PID" 2>/dev/null || true; wait "$MONITOR_PID" 2>/dev/null || true; fi
  kill -INT "$AUDIO_PID" 2>/dev/null || true
  set +e
  wait "$AUDIO_PID"
  recorder_rc=$?
  printf 'harness_rc=%s\nrecorder_rc=%s\n' "$rc" "$recorder_rc" > shots/audio_exit_status.txt
  # SIGINT may finalize a usable WAV with nonzero ffmpeg status. Keep both results;
  # artifacts still require header/duration/PCM/baseline inspection, never auto-green.
  exit "$rc"
}
trap cleanup EXIT
# Fail before the baseline if the monitor cannot open. Background launch alone proves nothing.
sleep 2
kill -0 "$AUDIO_PID"
[ -s shots/emitted_audio.wav ]
(
  while true; do
    date -u +%Y-%m-%dT%H:%M:%S.%NZ
    pactl list sink-inputs
    sleep 0.5
  done
) > shots/audio_sink_inputs_during.txt 2>&1 &
MONITOR_PID=$!
date -u +%Y-%m-%dT%H:%M:%S.%NZ > shots/audio_baseline_start.txt
sleep 5
kill -0 "$AUDIO_PID"
date -u +%Y-%m-%dT%H:%M:%S.%NZ > shots/audio_events_start.txt
# wb12-check only mkdirs shots and truncates results/.dead/.tmo, not the audio files.
bash tools/wb12-check.sh "$@"
