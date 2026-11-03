#!/usr/bin/env bash
# Summarizes a Bayit Voice logcat capture for the Phase 2 acceptance checks.
#
# Capture (keeps running; stop with Ctrl+C):
#   adb logcat -v time -s WakeWordService:* AssistSession:* OpenWakeWord:* AndroidRuntime:E > wake.log
# Report:
#   tools/wake_report.sh wake.log
set -euo pipefail

log="${1:?usage: wake_report.sh <logcat file>}"

awk '
/WakeWordService.*Listening for/   { starts++ }
/Wake word detected/               { detections++; match($0, /score [0-9.]+/); scores = scores " " substr($0, RSTART + 6, RLENGTH - 6) }
/Pipeline error: stt-no-text/      { nospeech++ }
/Microphone silenced/              { silenced++ }
/FATAL EXCEPTION/                  { crashes++ }
/stats: cpu/ {
    stats++
    match($0, /cpu [0-9.]+/);            cpu = substr($0, RSTART + 4, RLENGTH - 4) + 0
    match($0, /ran on [0-9.]+/);         gate = substr($0, RSTART + 7, RLENGTH - 7) + 0
    match($0, /pss [0-9]+/);             pss = substr($0, RSTART + 4, RLENGTH - 4) + 0
    cpuSum += cpu; gateSum += gate
    if (cpu > cpuMax) cpuMax = cpu
    if (pss > pssMax) pssMax = pss
    if (first == "") first = $1 " " $2
    last = $1 " " $2
}
END {
    printf "Period (stats lines):  %s  ->  %s  (%d minutes)\n", first, last, stats
    printf "Service starts:        %d\n", starts
    printf "Wake word detections:  %d   scores:%s\n", detections, scores
    printf "No-speech sessions:    %d   (after a detection: likely false triggers)\n", nospeech
    printf "Mic silenced events:   %d\n", silenced
    printf "Crashes:               %d\n", crashes
    if (stats > 0) {
        printf "CPU average:           %.1f%%   (max %.1f%%, target < 5%%)\n", cpuSum / stats, cpuMax
        printf "Models ran on:         %.0f%% of frames on average\n", gateSum / stats
        printf "Memory (PSS) max:      %d MB\n", pssMax
    }
}' "$log"
