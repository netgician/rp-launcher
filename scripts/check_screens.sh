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
  sleep "${wait:-4}"
  adb exec-out screencap -p > "$OUT/$n-$name.png"
  echo "$n-$name.png"
  n=$((n + 1))
done
