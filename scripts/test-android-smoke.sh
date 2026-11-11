#!/bin/sh
set -eu

capture_failure() {
    result="$1"
    if [ "$result" -ne 0 ]; then
        for pid in $(adb shell pidof dev.pocketconsole dev.pocketconsole:three_ds 2>/dev/null || true); do
            case "$pid" in ''|*[!0-9]*) continue ;; esac
            adb shell kill -3 "$pid" || true
        done
        adb shell sleep 2 || true
        adb logcat -d -v threadtime > android-failure-logcat.txt 2>&1 || true
        adb shell dumpsys activity activities > android-failure-activities.txt 2>&1 || true
    fi
    python3 scripts/stop-ci-emulator.py || true
    exit "$result"
}
trap 'capture_failure $?' EXIT

run_instrumentation() {
    result=0
    timeout --kill-after=10 180 adb shell am instrument -w -r "$2" > "$1" 2>&1 || result=$?
    cat "$1"
    return "$result"
}

adb shell wm size 720x1280
adb shell wm density 320
adb install artifacts/outputs/apk/debug/app-debug.apk
adb install artifacts/outputs/smoke-tests/ds-test.apk
adb shell dumpsys SurfaceFlinger | grep -i GLES || true
./scripts/capture-android-ui.sh
run_instrumentation android-native-smoke.log dev.pocketconsole.test/dev.pocketconsole.DsSmokeInstrumentation
grep 'DS_ANDROID_SMOKE_PASS' android-native-smoke.log
adb exec-out run-as dev.pocketconsole cat cache/smoke-player.png > android-player.png
base64 -w0 android-player.png | sed 's/^/DS_SCREENSHOT_BASE64:/'
printf '\n'

# The emulator can verify DS/UI while lacking Azahar's required GLES 3.2.
gles="$(adb shell getprop ro.opengles.version | tr -d '[:space:]')"
case "$gles" in
    ''|*[!0-9]*)
        echo "THREEDS_ANDROID_SMOKE_UNKNOWN_GLES: invalid or missing capability" | tee three-ds-android-smoke.log
        exit 1 ;;
esac
if [ "$gles" -lt 196610 ]; then
    echo "THREEDS_ANDROID_SMOKE_NOT_RUN_GLES: reported=$gles required=196610; physical-device acceptance pending" | tee three-ds-android-smoke.log
    exit 0
fi

adb install -r artifacts/outputs/smoke-tests/three-ds-test.apk
run_instrumentation three-ds-android-smoke.log dev.pocketconsole.test/dev.pocketconsole.ThreeDsSmokeInstrumentation
grep 'THREEDS_ANDROID_SMOKE_PASS' three-ds-android-smoke.log
adb exec-out run-as dev.pocketconsole cat cache/three-ds-player-portrait.png > three-ds-player-portrait.png
adb exec-out run-as dev.pocketconsole cat cache/three-ds-player-landscape.png > three-ds-player-landscape.png
base64 -w0 three-ds-player-portrait.png | sed 's/^/THREEDS_PORTRAIT_BASE64:/'
printf '\n'
base64 -w0 three-ds-player-landscape.png | sed 's/^/THREEDS_LANDSCAPE_BASE64:/'
printf '\n'
