#!/bin/sh
# Photographs RP Launcher's screens in Demo mode (made-up people) for the app stores.
# Run on an emulator by .github/workflows/screenshots.yml; needs a test build installed.
set -e
PKG=com.netgician.rplauncher
# SHOT_LANG=en (default) or es: which language the app shows, and which store listing gets them.
SHOT_LANG="${SHOT_LANG:-en}"
if [ "$SHOT_LANG" = es ]; then OUT=fastlane/metadata/android/es-ES/images/phoneScreenshots; else OUT=fastlane/metadata/android/en-US/images/phoneScreenshots; fi
mkdir -p "$OUT"
rm -f "$OUT"/*.png

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" 0 || true

# A tidy status bar: 10:00, full battery, full signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 1000
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false

shot() {
  adb shell am start -n "$PKG/.MainActivity" -a "$PKG.STORE_SHOT" --es screen "$2" --es lang "$SHOT_LANG" >/dev/null
  sleep "${3:-4}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "$1.png: $2"
}

shot 1 home 8
shot 2 conversation
shot 3 call 5
adb shell input keyevent KEYCODE_HOME
shot 4 picture 5
shot 5 recents
shot 6 sos 3
shot 7 home-night
shot 8 checklist
