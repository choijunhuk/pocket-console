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

capture_library() {
    adb shell am force-stop dev.pocketconsole
    adb shell am start -W -n dev.pocketconsole/.MainActivity
    attempt=0
    while [ "$attempt" -lt 15 ]; do
        if adb shell uiautomator dump /data/local/tmp/pocket-console-ui.xml >/dev/null 2>&1; then
            hierarchy="$(adb exec-out cat /data/local/tmp/pocket-console-ui.xml)"
            if printf '%s' "$hierarchy" | grep -q 'text="내 라이브러리"' \
                && printf '%s' "$hierarchy" | grep -q 'text="파일 가져오기"'; then
                adb exec-out screencap -p > "$1"
                base64 -w0 "$1" | sed "s/^/$2:/"
                printf '\n'
                return
            fi
        fi
        attempt=$((attempt + 1))
        adb shell sleep 1
    done
    echo "Library UI did not become ready before capture" >&2
    return 1
}

adb shell cmd uimode night no
adb shell settings put system font_scale 1.0
capture_library library-light.png LIBRARY_LIGHT_BASE64

adb shell cmd uimode night yes
adb shell settings put system font_scale 1.3
capture_library library-dark-font.png LIBRARY_DARK_FONT_BASE64
echo ANDROID_UI_CAPTURE_PASS
