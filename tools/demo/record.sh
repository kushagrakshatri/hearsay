#!/usr/bin/env bash
# Records launch-video footage on the CI emulator: the real floating mic and real on-device models,
# fed scripted speech (tools/demo/audio/*.pcm) through the debug-only demo mic. Output: footage/.
set -u
P=dev.hearsay.debug
N=dev.offlineflow
A=tools/demo/audio
OUT=footage; mkdir -p $OUT
DEMO=/sdcard/Android/data/$P/files/demo
log() { echo "$*" | tee -a $OUT/timeline.txt; }
now() { date +%s.%N; }

adb install -r "$1" || exit 1
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 1; done
adb shell settings put global hide_error_dialogs 1
adb shell cmd uimode night no
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell pm grant $P android.permission.POST_NOTIFICATIONS 2>/dev/null
# Clean status bar for filming: full battery, strong signal, 9:41.
adb shell settings put global sysui_demo_allowed 1
bar() {
  adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
  adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
  adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
  adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none >/dev/null
  adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
}

# Models: pushed straight into app storage (same files and paths the in-app downloader writes).
adb shell am start -n $P/$N.SettingsActivity >/dev/null; sleep 4; adb shell am force-stop $P
push_model() { # dir file
  adb push models/$2 /data/local/tmp/$2 >/dev/null && adb shell chmod 644 /data/local/tmp/$2
  adb shell run-as $P mkdir -p files/models/$1
  adb shell run-as $P cp /data/local/tmp/$2 files/models/$1/$2 && adb shell rm /data/local/tmp/$2
}
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do push_model parakeet-tdt-0.6b-v2-int8 $f; done
push_model llm Qwen3.5-0.8B-Q4_K_M.gguf
adb shell run-as $P mkdir -p shared_prefs
adb shell "run-as $P sh -c 'cat > shared_prefs/hearsay.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="setup_done" value="true" />
    <string name="model">PARAKEET_V2</string>
    <string name="cleanup">QWEN35_0_8B</string>
    <int name="bubble_x" value="42" />
    <int name="bubble_dy" value="-470" />
</map>
XML
adb shell run-as $P ls -lR files/models | tee $OUT/models.txt

# Floating mic on, over the system keyboard.
adb shell ime reset
adb shell am start -n $P/$N.SettingsActivity >/dev/null; sleep 3
adb shell settings put secure enabled_accessibility_services $P/$N.bubble.BubbleService
adb shell settings put secure accessibility_enabled 1; sleep 3
# The service binds only to a running app; re-toggle until it shows as bound.
for i in 1 2 3; do
  adb shell dumpsys accessibility | grep -i "Bound services" | grep -qi "floating mic" && break
  adb shell settings put secure enabled_accessibility_services null; sleep 1
  adb shell settings put secure enabled_accessibility_services $P/$N.bubble.BubbleService; sleep 3
done
adb shell dumpsys accessibility | grep -iA2 "Bound services" | tee $OUT/a11y.txt

chat() { # name m1 [m2 ...]
  local name=$1; shift; local args=(); local i=1
  for m in "$@"; do args+=(--es m$i "'$m'"); i=$((i+1)); done
  adb shell am start -f 0x10008000 -n $P/$N.demo.DemoActivity --es scene chat --es name "$name" "${args[@]}" >/dev/null; sleep 2.5
}
# No uiautomator here: it registers a UiAutomation, which unbinds the floating mic's accessibility service.
field() { sleep 2; }  # DemoActivity focuses its field and opens the keyboard itself
fieldtext() { adb shell cat $DEMO/field.txt 2>/dev/null | tr '\n' '~'; }
waitfield() { # before timeout: until the text changes and holds still for a second
  local end=$(( $(date +%s) + $2 )) last=; while [ $(date +%s) -lt $end ]; do
    local t; t=$(fieldtext); [ "$t" != "$1" ] && [ "$t" = "$last" ] && return; last=$t; sleep 1; done; }
say() { # clip: press the mic for the clip's length while it plays into the demo mic
  local clip=$1 before; before=$(fieldtext)
  local xy=; for i in $(seq 1 20); do xy=$(adb shell cat $DEMO/bubble.txt 2>/dev/null); [ -n "$xy" ] && break; sleep 0.5; done
  [ -z "$xy" ] && { log "WARN no bubble.txt"; xy="1000 1500"; }
  sleep 0.8
  adb push $A/$clip.pcm $DEMO/next.tmp >/dev/null && adb shell mv $DEMO/next.tmp $DEMO/next.pcm
  local ms=$(( $(stat -c%s $A/$clip.pcm) / 32 + 600 ))
  log "say $clip, hold ${ms} ms at $xy"
  adb shell input swipe $xy $xy $ms
  local t1; t1=$(now)
  # The emulator thinks ~10x slower than a phone: film the first seconds of thinking, pause the camera
  # through the rest, and start a new segment once the text lands. The edit marks the cut.
  [ -n "${SEG:-}" ] && { sleep 6; stopseg; }
  waitfield "$before" 400
  log "text $clip after $(echo "$(now) - $t1" | bc) s: $(fieldtext)"
  [ -n "${SEG:-}" ] && { K=$((K+1)); startseg; sleep 1.5; }
}
startseg() { adb shell rm -f /sdcard/seg.mp4; adb shell screenrecord --bit-rate 16000000 /sdcard/seg.mp4 & REC=$!; sleep 0.5; log "-- ${SEG}_$K"; }
stopseg() { sleep 0.5; adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 2; adb pull /sdcard/seg.mp4 $OUT/${SEG}_$K.mp4 >/dev/null; }
rec() { bar; SEG=$1; K=1; log "== $1"; startseg; sleep 1; }
stop() { sleep 1; stopseg; SEG=; }
send() { adb shell input tap $(adb shell cat $DEMO/send.txt); }

# Warm up: first load of both models is slow, and isn't the point.
chat Maya "warming up"; field Message; say plane; adb shell input keyevent BACK

chat Maya "dinner this week?" "that new place on Santana Row finally opened"
rec chat; field Message; say chat; sleep 2.5; send; sleep 2.5; stop

adb shell am start -f 0x10008000 -n $P/$N.demo.DemoActivity --es scene mail >/dev/null; sleep 2.5
rec mail; field "Compose email"; say mail; sleep 3
adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; sleep 1.5
say edit; sleep 3.5; stop

chat Siobhan "did you get a chance to finish the deck?" "Aoife wants to see it before Monday too"
rec names; field Message; say names; sleep 2.5; send; sleep 2.5; stop

chat Maya "text me when you land"
rec plane
adb shell cmd statusbar expand-settings; sleep 2
adb shell cmd connectivity airplane-mode enable
adb shell am broadcast -a com.android.systemui.demo -e command network -e airplane show -e wifi hide >/dev/null; sleep 2.5
adb shell cmd statusbar collapse; sleep 1.5
field Message; say plane; sleep 2.5; send; sleep 2.5; stop
adb shell cmd connectivity airplane-mode disable

adb logcat -d -s HearsayBubble:* > $OUT/bubble-log.txt 2>&1
adb logcat -d > $OUT/full-log.txt 2>&1 || true
adb logcat -d -b crash > $OUT/crash-log.txt 2>&1 || true
ls -l $OUT
