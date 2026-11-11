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
    output="$1"
    shift
    timeout --kill-after=10 180 adb shell am instrument -w -r "$@" > "$output" 2>&1 || result=$?
    cat "$output"
    return "$result"
}

adb shell wm size 720x1280
adb shell wm density 320
adb install "${POCKET_ANDROID_APK:-artifacts/outputs/apk/debug/app-debug.apk}"
adb install -t artifacts/outputs/smoke-tests/ds-test.apk
adb shell dumpsys SurfaceFlinger | grep -i GLES || true
./scripts/capture-android-ui.sh
if [ "${POCKET_RELEASE_SMOKE:-}" = "1" ]; then
    run_instrumentation android-native-smoke.log -e pocketExportScreenshot true \
        dev.pocketconsole.test/dev.pocketconsole.DsSmokeInstrumentation
else
    run_instrumentation android-native-smoke.log dev.pocketconsole.test/dev.pocketconsole.DsSmokeInstrumentation
fi
grep 'DS_ANDROID_SMOKE_PASS' android-native-smoke.log
if [ "${POCKET_RELEASE_SMOKE:-}" = "1" ]; then
    screenshot_base64="$(sed -n 's/^INSTRUMENTATION_STATUS: stream=DS_SCREENSHOT_BASE64://p' android-native-smoke.log | tail -n 1)"
    test -n "$screenshot_base64"
    printf '%s' "$screenshot_base64" | base64 -d > android-player.png
    python3 -c 'from pathlib import Path; data=Path("android-player.png").read_bytes(); assert data.startswith(bytes.fromhex("89504e470d0a1a0a")) and data.endswith(bytes.fromhex("49454e44ae426082"))'
    echo DS_SCREENSHOT_FROM_INSTRUMENTATION_PASS
else
    adb exec-out run-as dev.pocketconsole cat cache/smoke-player.png > android-player.png
    base64 -w0 android-player.png | sed 's/^/DS_SCREENSHOT_BASE64:/'
    printf '\n'
fi

if [ "${POCKET_RELEASE_SMOKE:-}" = "1" ]; then
    echo "THREEDS_ANDROID_SMOKE_NOT_RUN_EXPERIMENTAL: 3DS execution is pending" | tee three-ds-android-smoke.log
    exit 0
fi

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

adb install -r -t artifacts/outputs/smoke-tests/three-ds-test.apk
run_instrumentation three-ds-android-smoke.log dev.pocketconsole.test/dev.pocketconsole.ThreeDsSmokeInstrumentation
grep 'THREEDS_ANDROID_SMOKE_PASS' three-ds-android-smoke.log
if [ "${POCKET_RELEASE_SMOKE:-}" != "1" ]; then
    adb exec-out run-as dev.pocketconsole cat cache/three-ds-player-portrait.png > three-ds-player-portrait.png
    adb exec-out run-as dev.pocketconsole cat cache/three-ds-player-landscape.png > three-ds-player-landscape.png
    base64 -w0 three-ds-player-portrait.png | sed 's/^/THREEDS_PORTRAIT_BASE64:/'
    printf '\n'
    base64 -w0 three-ds-player-landscape.png | sed 's/^/THREEDS_LANDSCAPE_BASE64:/'
    printf '\n'
fi
