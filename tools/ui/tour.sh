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
scroll_shots() { for i in 1 2 3; do $UI up; shot "$1-scroll$i" 0.5; done; }

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
  $UI tapnoscroll "Later"; shot $mode-setup-bubble
  $UI tapnoscroll "Use the Hearsay keyboard instead"; shot $mode-setup-enable
  $UI tapnoscroll "Not now"; shot $mode-setup-switch
  $UI tapnoscroll "Not now"; shot $mode-setup-try
  $UI tapnoscroll "Done"; shot $mode-settings-unset
  scroll_shots $mode-settings
  start
  $UI tap "Floating mic"; shot $mode-sheet-bubble; adb shell input keyevent BACK; sleep 1
  $UI tap "Voice"; shot $mode-sheet-voice; adb shell input keyevent BACK; sleep 1
  $UI tap "Cleanup"; shot $mode-sheet-cleanup; adb shell input keyevent BACK; sleep 1
  $UI tap "Writing style"; shot $mode-sheet-style; adb shell input keyevent BACK; sleep 1
  $UI tap "Dictionary"; shot $mode-sheet-dictionary; adb shell input keyevent BACK; sleep 1
done

# Make it usable: mic, keyboard, the light voice model, cleanup off. Then the keyboard states.
adb shell cmd uimode night no; sleep 1
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell ime enable $P/.ime.OfflineFlowIME
start
$UI tap "Cleanup"; $UI tapnoscroll "Off"; adb shell input keyevent BACK; sleep 1
$UI tap "Voice"; $UI tapnoscroll "Light"; $UI tapnoscroll "Download Light"; sleep 3
for i in $(seq 1 80); do sleep 3; python3 - <<'PY' && break
import sys; sys.path.insert(0, "tools/ui"); import ui; sys.exit(1 if ui.find("Downloading") else 0)
PY
done
adb shell input keyevent BACK; sleep 1
start; adb shell ime set $P/.ime.OfflineFlowIME; sleep 2; shot settings-ready
$UI tapnoscroll "Try it"; shot keyboard-idle 2.5
python3 tools/ui/ui.py holdat 0.5 0.855 4000; shot keyboard-listening 1.5; sleep 4; shot keyboard-after 2
python3 tools/ui/ui.py tapat 0.12 0.70; shot keyboard-style-tap 1
adb shell cmd uimode night yes; sleep 2; start; adb shell ime set $P/.ime.OfflineFlowIME; sleep 1
$UI tapnoscroll "Try it"; shot dark-keyboard-idle 2.5
# Floating mic over the system keyboard.
adb shell cmd uimode night no; sleep 1
adb shell ime reset; sleep 1
adb shell settings put secure enabled_accessibility_services $P/$P.bubble.BubbleService
adb shell settings put secure accessibility_enabled 1; sleep 2
start; shot bubble-settings
$UI tapnoscroll "Try it"; shot bubble-over-keyboard 3
python3 tools/ui/ui.py tapat 0.89 0.60; shot bubble-tap 1.5
adb logcat -d -b crash > "$OUT/crash-log.txt" 2>&1 || true
echo "done: $n shots"
