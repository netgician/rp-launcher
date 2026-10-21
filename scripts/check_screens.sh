#!/bin/sh
# Photographs chosen screens in Demo mode, to check a change by eye (not for the stores).
# Run on an emulator by .github/workflows/screen-check.yml; needs a test build installed.
#   SCREENS="timer-pick timer-ring:9" SHOT_LANG=es sh scripts/check_screens.sh
# Each entry is a StoreShots screen name, optionally ":seconds" to wait before the picture.
# "-locked" screens turn the screen off right after opening, to test what shows over the lock screen.
set -e
PKG=com.netgician.rplauncher
SHOT_LANG="${SHOT_LANG:-en}"
OUT="screen-check/$SHOT_LANG"
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" 0 || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
# The emulator's own launcher sometimes stops responding in the background and covers the
# screen with a dialog; RP Launcher is the home screen here, so it isn't needed.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true

adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 1000
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo notifications -e visible false

n=1
for entry in $SCREENS; do
  name=$(echo "$entry" | cut -d: -f1)
  wait=$(echo "$entry" | cut -s -d: -f2)
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard || true
  adb shell am start -n "$PKG/.MainActivity" -a "$PKG.STORE_SHOT" --es screen "$name" --es lang "$SHOT_LANG" >/dev/null
  case "$name" in
    *-locked) sleep 1; adb shell input keyevent KEYCODE_SLEEP ;;
  esac
  # "battery-low": the battery pretends to run down to 9 percent, unplugged.
  case "$name" in
    battery-low) adb shell dumpsys battery unplug; adb shell dumpsys battery set level 9 ;;
  esac
  # "-asleep": deep sleep (Doze), as a phone left locked on a table.
  case "$name" in
    *asleep*) adb shell dumpsys deviceidle force-idle >/dev/null || true ;;
  esac
  sleep 2
  adb shell dumpsys alarm | grep -B2 -A6 "$PKG" > "$OUT/$n-$name-alarms.txt" || true
  sleep "${wait:-4}"
  adb exec-out screencap -p > "$OUT/$n-$name.png"
  echo "$n-$name.png"
  case "$name" in
    *asleep*) adb shell dumpsys deviceidle unforce >/dev/null || true ;;
    battery-low) adb shell dumpsys battery reset ;;
  esac
  # A ring screen left open would cover the next picture.
  case "$name" in
    *-ring*) adb shell input keyevent KEYCODE_BACK; sleep 1 ;;
  esac
  n=$((n + 1))
done
