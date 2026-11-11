#!/bin/sh
set -eu

previous_night="$(adb shell cmd uimode night | tr -d '\r' | sed -n 's/^Night mode: //p')"
previous_font="$(adb shell settings get system font_scale | tr -d '\r')"
restore() {
    if [ -n "$previous_night" ]; then adb shell cmd uimode night "$previous_night"; fi
    if [ "$previous_font" = null ]; then
        adb shell settings delete system font_scale
    else
        adb shell settings put system font_scale "$previous_font"
    fi
}
trap 'result=$?; restore || true; exit "$result"' EXIT

adb shell cmd uimode night no
adb shell settings put system font_scale 1.0
adb shell am start -W -n dev.pocketconsole/.MainActivity
adb shell sleep 1
adb exec-out screencap -p > library-light.png
base64 -w0 library-light.png | sed 's/^/LIBRARY_LIGHT_BASE64:/'

adb shell cmd uimode night yes
adb shell settings put system font_scale 1.3
adb shell am start -W -n dev.pocketconsole/.MainActivity
adb shell sleep 1
adb exec-out screencap -p > library-dark-font.png
base64 -w0 library-dark-font.png | sed 's/^/LIBRARY_DARK_FONT_BASE64:/'
echo ANDROID_UI_CAPTURE_PASS
