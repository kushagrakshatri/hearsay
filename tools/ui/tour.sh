#!/usr/bin/env bash
# Screenshot tour of every screen and keyboard state, light and dark. Runs on the CI emulator.
set -u
P=dev.offlineflow
UI="python3 tools/ui/ui.py"
OUT=shots; mkdir -p $OUT
n=0
# Emulator system apps (Pixel Launcher) sometimes ANR on CI; dismiss the dialog before any shot.
unstick() { python3 -c 'import sys; sys.path.insert(0, "tools/ui"); import ui; ui.unstick()'; }
shot() { sleep ${2:-1.5}; unstick; n=$((n+1)); adb exec-out screencap -p > "$OUT/$(printf %02d $n)-$1.png"; echo "shot $1"; }
start() { adb shell am force-stop $P; adb shell am start -n $P/.SettingsActivity >/dev/null; sleep 3; }
scroll_shots() { for i in 1 2 3 4 5 6 7; do $UI up; shot "$1-scroll$i" 0.5; done; }

adb install -r "$1" || exit 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global hide_error_dialogs 1

for mode in light dark; do
  adb shell cmd uimode night $([ $mode = dark ] && echo yes || echo no); sleep 2
  adb shell pm clear $P >/dev/null
  adb shell ime disable $P/.ime.OfflineFlowIME >/dev/null 2>&1
  # Guided setup, step by step.
  start; shot $mode-setup-welcome
  $UI tapnoscroll "Get started"; shot $mode-setup-mic
  $UI tapnoscroll "Not now"; shot $mode-setup-models
  $UI tapnoscroll "Later"; shot $mode-setup-enable
  $UI tapnoscroll "Not now"; shot $mode-setup-switch
  $UI tapnoscroll "Not now"; shot $mode-setup-try
  $UI tapnoscroll "Done"; shot $mode-settings-unset
  scroll_shots $mode-settings
  $UI tap "Show"; shot $mode-advanced-open
done

# Make it usable: mic, keyboard, small speech model, cleanup off. Then the keyboard states.
adb shell cmd uimode night no; sleep 1
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell ime enable $P/.ime.OfflineFlowIME
adb shell ime set $P/.ime.OfflineFlowIME
start
$UI tap "Download Whisper"
for i in $(seq 1 80); do sleep 3; python3 - <<'PY' && break
import sys; sys.path.insert(0, "tools/ui"); import ui; sys.exit(0 if ui.find("Ready to dictate") else 1)
PY
done
$UI tap "Basic rules only"
start; shot settings-ready
$UI tapnoscroll "Try it"; shot keyboard-idle 2.5
$UI hold "Microphone" 4000; shot keyboard-listening 1.5; sleep 4; shot keyboard-after 2
$UI tapnoscroll "Writing style"; shot keyboard-style-casual 1
adb shell cmd uimode night yes; sleep 2; start
$UI tapnoscroll "Try it"; shot dark-keyboard-idle 2.5
echo "done: $n shots"
