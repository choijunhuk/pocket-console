#!/usr/bin/env bash
set -euo pipefail
capture_failure() {
  status=$?
  trap - EXIT
  if (( status != 0 )); then
    timeout 15s adb logcat -d -b all > switch-sdk-crash-logcat.txt 2>&1 || true
    timeout 15s adb shell dumpsys activity processes > switch-sdk-processes.txt 2>&1 || true
    timeout 15s adb shell dumpsys activity activities > switch-sdk-activities.txt 2>&1 || true
  fi
  exit "$status"
}
trap capture_failure EXIT
apk=tests/android-switch-sdk/app/build/outputs/apk/debug/app-debug.apk
test_apk=tests/android-switch-sdk/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell getprop ro.product.cpu.abilist | tee switch-sdk-abilist.txt
adb shell getprop ro.dalvik.vm.native.bridge | tee switch-sdk-nativebridge.txt
grep -q arm64-v8a switch-sdk-abilist.txt
grep -q libndk_translation switch-sdk-nativebridge.txt
adb install --abi arm64-v8a "$apk"
adb install -t "$test_apk"
adb shell dumpsys package dev.pocketconsole.sdkprobe | grep -E 'primaryCpuAbi|nativeLibraryDir' | tee switch-sdk-installed-abi.txt
grep -q arm64-v8a switch-sdk-installed-abi.txt
adb shell pm list instrumentation | grep 'dev.pocketconsole.sdkprobe.test/dev.pocketconsole.SwitchSdkSmokeInstrumentation'
timeout 180s adb shell am instrument -w -r dev.pocketconsole.sdkprobe.test/dev.pocketconsole.SwitchSdkSmokeInstrumentation | tee switch-sdk-smoke.log
adb logcat -d -s PocketSwitchSdk:* > switch-sdk-logcat.txt
grep -q JNI_LINK_PASS switch-sdk-smoke.log
if grep -q NRO_FRAME_INPUT_PASS switch-sdk-smoke.log; then
  echo NRO_FRAME_INPUT_PASS | tee switch-sdk-status.txt
  adb exec-out run-as dev.pocketconsole.sdkprobe cat cache/switch-sdk-first-frame.png > switch-sdk-first-frame.png
  adb exec-out run-as dev.pocketconsole.sdkprobe cat cache/switch-sdk-a-pressed.png > switch-sdk-a-pressed.png
elif grep -q NRO_NOT_RUN_UNSUPPORTED_GPU switch-sdk-smoke.log; then
  echo NRO_NOT_RUN_UNSUPPORTED_GPU | tee switch-sdk-status.txt
else
  echo NRO_SMOKE_FAILED_OR_UNKNOWN_GPU | tee switch-sdk-status.txt
  exit 1
fi
